package cs.trade.scheduler.archival.s3

import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.archival.ArchivedJobRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import kotlin.time.Instant

/**
 * End-to-end coverage for [S3ArchivalSink] against a real S3-compatible store (MinIO).
 *
 * **Provisioning** mirrors the `EXTERNAL_PG_URL` / `EXTERNAL_RABBIT_HOST` pattern: honours
 * `EXTERNAL_S3_ENDPOINT` (+ `_ACCESS_KEY` / `_SECRET_KEY`) for a shared `scheduler-test-minio`
 * container, and falls back to a MinIO Testcontainer when absent. Manual lifecycle so the
 * env override can short-circuit Docker.
 *
 * Asserts the load-bearing properties of the sink:
 *  1. a batch is written as one JSONL object under `category/<day>/<hash>.jsonl` and reads
 *     back record-for-record;
 *  2. re-archiving the same batch is idempotent — the content-derived key means the retry
 *     overwrites rather than creating a duplicate object;
 *  3. distinct categories land under distinct prefixes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ArchivalSinkIntegrationTest {

    private companion object {
        private val minioImage: DockerImageName = DockerImageName.parse("minio/minio:latest")
        private val externalEndpoint: String? = System.getenv("EXTERNAL_S3_ENDPOINT")?.takeIf { it.isNotBlank() }
        private const val BUCKET = "scheduler-archive-test"
        private const val REGION = "us-east-1"
    }

    private lateinit var verifyClient: S3Client
    private lateinit var sink: S3ArchivalSink
    private var minio: GenericContainer<*>? = null

    @BeforeAll
    fun setUp() {
        val endpoint: String; val accessKey: String; val secretKey: String
        if (externalEndpoint != null) {
            endpoint = externalEndpoint
            accessKey = System.getenv("EXTERNAL_S3_ACCESS_KEY") ?: "minioadmin"
            secretKey = System.getenv("EXTERNAL_S3_SECRET_KEY") ?: "minioadmin"
        } else {
            val tc = GenericContainer(minioImage)
                .withEnv("MINIO_ROOT_USER", "minioadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            tc.start()
            minio = tc
            endpoint = "http://${tc.host}:${tc.getMappedPort(9000)}"
            accessKey = "minioadmin"
            secretKey = "minioadmin"
        }

        // Separate client for test setup (create bucket) + verification (read back).
        verifyClient = S3Client.builder()
            .region(Region.of(REGION))
            .endpointOverride(URI.create(endpoint))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .build()
        runCatching { verifyClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()) }
            // Bucket may already exist from a previous run against an external MinIO.

        // The sink builds its OWN client via the public factory — exercises endpoint
        // override + path-style + static creds the way a real deployment wires it.
        sink = S3ArchivalSink.create(
            bucket = BUCKET,
            region = REGION,
            endpoint = endpoint,
            accessKeyId = accessKey,
            secretAccessKey = secretKey,
        )
    }

    @AfterAll
    fun tearDown() {
        runCatching { sink.close() }
        runCatching { verifyClient.close() }
        runCatching { minio?.stop() }
    }

    @Test
    fun `archives a batch as one JSONL object and reads it back record-for-record`() = runBlocking {
        val category = "job.failed.${System.nanoTime()}"
        val batch = listOf(
            record("a-${System.nanoTime()}"),
            record("b-${System.nanoTime()}"),
            record("c-${System.nanoTime()}"),
        )

        sink.archive(category, batch)

        val keys = listKeys(category)
        assertEquals(1, keys.size, "one batch must produce exactly one object, got $keys")
        assertTrue(keys.single().endsWith(".jsonl"), "object must be a .jsonl file: ${keys.single()}")

        val lines = readJsonl(keys.single())
        assertEquals(batch.size, lines.size, "every record must be one JSONL line")
        // Round-trips identically (compare by id set — order is sink-sorted by id).
        assertEquals(
            batch.map { it.id }.toSortedSet(),
            lines.map { it.id }.toSortedSet(),
            "all record ids must survive the round-trip",
        )
        val one = lines.first { it.id == batch.first().id }
        assertEquals(batch.first().payloadJson, one.payloadJson, "payload must round-trip verbatim")
        assertEquals(batch.first().state, one.state)
    }

    @Test
    fun `re-archiving the same batch is idempotent — same key, single object`() = runBlocking {
        val category = "job.idem.${System.nanoTime()}"
        val batch = listOf(record("x-${System.nanoTime()}"), record("y-${System.nanoTime()}"))

        sink.archive(category, batch)
        sink.archive(category, batch)                 // retry of the same logical batch
        sink.archive(category, batch.reversed())      // different read-order, same content

        assertEquals(
            1, listKeys(category).size,
            "identical content (any order) must collapse to one content-addressed object",
        )
    }

    @Test
    fun `distinct categories land under distinct prefixes`() = runBlocking {
        val stamp = System.nanoTime()
        val succeeded = "job.succeeded.$stamp"
        val cancelled = "job.cancelled.$stamp"

        sink.archive(succeeded, listOf(record("s-$stamp", state = JobState.SUCCEEDED)))
        sink.archive(cancelled, listOf(record("c-$stamp", state = JobState.CANCELLED)))

        assertEquals(1, listKeys(succeeded).size)
        assertEquals(1, listKeys(cancelled).size)
        assertTrue(listKeys(succeeded).single().startsWith("$succeeded/"), "key must be category-prefixed")
        assertTrue(listKeys(cancelled).single().startsWith("$cancelled/"), "key must be category-prefixed")
    }

    // --- helpers ----------------------------------------------------------------------

    private fun listKeys(category: String): List<String> =
        verifyClient.listObjectsV2(
            ListObjectsV2Request.builder().bucket(BUCKET).prefix("$category/").build(),
        ).contents().map { it.key() }

    private fun readJsonl(key: String): List<ArchivedJobRecord> {
        val bytes = verifyClient.getObjectAsBytes(
            GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
        ).asByteArray()
        return bytes.decodeToString().lineSequence()
            .filter { it.isNotBlank() }
            .map { S3ArchivalSink.DEFAULT_JSON.decodeFromString(ArchivedJobRecord.serializer(), it) }
            .toList()
    }

    /** Minimal terminal-row record; only [id], [state] and a stable [updatedAt] matter here. */
    private fun record(id: String, state: JobState = JobState.FAILED): ArchivedJobRecord =
        ArchivedJobRecord(
            id = id,
            state = state,
            queue = "default",
            priority = 0,
            payloadType = "com.example.Demo",
            payloadJson = "{\"id\":\"$id\"}",
            scheduledAt = null,
            attempts = 1,
            maxAttempts = 3,
            timeoutSeconds = null,
            lockedBy = null,
            lockedUntil = null,
            pendingDeps = 0,
            initialPendingDeps = 0,
            version = 2,
            idempotencyKey = null,
            targetNode = null,
            targetTag = null,
            progress = null,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = null,
            durationMs = 42L,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = null,
            createdAt = Instant.parse("2026-05-29T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-29T10:05:00Z"),
        )
}

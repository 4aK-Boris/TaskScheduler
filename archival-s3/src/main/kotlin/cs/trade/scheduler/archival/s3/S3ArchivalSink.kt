package cs.trade.scheduler.archival.s3

import cs.trade.scheduler.core.backend.archival.ArchivalSink
import cs.trade.scheduler.shared.archival.ArchivedJobRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import kotlin.time.toJavaInstant

/**
 * S3-compatible [ArchivalSink] (DESIGN.md 18.7). Writes each retention batch as one
 * JSON-lines object at:
 *
 * ```
 * s3://<bucket>/[<keyPrefix>/]<category>/<YYYY-MM-DD>/<content-hash>.jsonl
 * ```
 *
 * Works against AWS S3 and any S3-compatible store (MinIO, Cloudflare R2, GCS via its S3
 * API, DigitalOcean Spaces) — point [create] at a custom `endpoint` with path-style access.
 *
 * **Idempotency.** The retention loop re-feeds the *same* terminal rows on the next tick
 * whenever a previous `archive` threw (DELETE is skipped on failure — see
 * `RetentionCleanupBatchUseCase`). To make that retry a no-op instead of a duplicate, the
 * object key is fully content-derived:
 *  - records are sorted by `id` before serialising, so DB read-order can't change the bytes;
 *  - the day partition is the batch's latest `updatedAt` (not wall-clock now), so a retry
 *    lands on the same prefix even across a midnight boundary;
 *  - the file name is a SHA-256 prefix of the JSONL bytes.
 *
 * Same logical batch → identical bytes → identical key → the retry overwrites the object
 * rather than creating a second copy.
 *
 * **Durability.** S3 `PutObject` returns only after the object is durably stored, so a
 * normal return satisfies the [ArchivalSink] contract. Any failure propagates so the
 * retention loop keeps the rows for another attempt.
 *
 * The sync [S3Client] is used over [Dispatchers.IO]; [close] shuts it down.
 */
public class S3ArchivalSink(
    private val s3: S3Client,
    private val bucket: String,
    private val keyPrefix: String = "",
    private val json: Json = DEFAULT_JSON,
) : ArchivalSink, AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun archive(category: String, batch: List<ArchivedJobRecord>) {
        if (batch.isEmpty()) return
        val sorted = batch.sortedBy { it.id }
        val payload = buildString {
            for (record in sorted) {
                append(json.encodeToString(ArchivedJobRecord.serializer(), record))
                append('\n')
            }
        }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val key = keyFor(category, sorted, bytes)
        withContext(Dispatchers.IO) {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/x-ndjson")
                    .build(),
                RequestBody.fromBytes(bytes),
            )
        }
        log.info("Archived {} {} row(s) to s3://{}/{}", sorted.size, category, bucket, key)
    }

    private fun keyFor(category: String, sorted: List<ArchivedJobRecord>, bytes: ByteArray): String {
        val day = sorted.maxOf { it.updatedAt }
            .toJavaInstant().atZone(ZoneOffset.UTC).toLocalDate().toString()
        val hash = sha256Hex(bytes).take(HASH_KEY_LEN)
        return listOf(keyPrefix.trim('/'), category, day, "$hash.jsonl")
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun close(): Unit = s3.close()

    public companion object {
        /** Hex chars of the SHA-256 kept in the object name — 16 ≈ 64 bits, ample per category/day. */
        public const val HASH_KEY_LEN: Int = 16

        // One JSON object per line; encodeDefaults so older readers see full shapes;
        // ignoreUnknownKeys so newer-written records still parse with older code.
        public val DEFAULT_JSON: Json = Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * Build a sink with its own [S3Client]. Bind it over the default `Noop` in a Koin
         * module declared *after* `schedulerInfraModule` (last-wins):
         *
         * ```
         * single<ArchivalSink> {
         *     S3ArchivalSink.create(bucket = "job-archive", endpoint = "https://r2.example.com", region = "auto")
         * }
         * ```
         *
         * @param region AWS region (e.g. `"eu-central-1"`); `"auto"` for Cloudflare R2.
         * @param endpoint custom S3 endpoint for non-AWS stores; `null` = real AWS.
         * @param accessKeyId / secretAccessKey static creds; both `null` → the AWS default
         *        credential chain (env vars, profile, instance/role).
         * @param pathStyleAccess defaults to `true` when a custom [endpoint] is set (MinIO
         *        and most S3-compatible stores require it); AWS uses virtual-hosted style.
         */
        public fun create(
            bucket: String,
            region: String = "us-east-1",
            endpoint: String? = null,
            accessKeyId: String? = null,
            secretAccessKey: String? = null,
            pathStyleAccess: Boolean = endpoint != null,
            keyPrefix: String = "",
            json: Json = DEFAULT_JSON,
        ): S3ArchivalSink {
            val builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build(),
                )
            if (endpoint != null) builder.endpointOverride(URI.create(endpoint))
            if (accessKeyId != null && secretAccessKey != null) {
                builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)),
                )
            }
            return S3ArchivalSink(builder.build(), bucket, keyPrefix, json)
        }
    }
}

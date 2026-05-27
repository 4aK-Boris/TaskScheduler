package cs.trade.scheduler.core.backend.archival

import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.archival.ArchivedJobRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Filesystem-only integration tests for [FileArchivalSink] — no Postgres / Docker needed.
 *
 * We exercise:
 * 1. **Happy path** — a single batch produces one daily JSONL file with one line per record,
 *    each parseable back to the original [ArchivedJobRecord].
 * 2. **Append semantics across calls** — two `archive()` invocations on the same UTC day land
 *    in the SAME file (no overwrite, no duplicate header), and the lines preserve order.
 * 3. **Category partitioning** — different categories write to different sub-directories so a
 *    single sink instance can serve `job.succeeded` / `job.failed` / future buckets without
 *    co-mingling. Also verifies the empty-batch fast path is a no-op (no zero-byte file).
 *
 * No fsync semantics asserted — see [FileArchivalSink] KDoc: it's `Files.writeString` only,
 * adequate for dev/single-node, not for durability-critical compliance use.
 */
@OptIn(ExperimentalUuidApi::class)
class FileArchivalSinkTest {

    private lateinit var tmpRoot: Path
    private lateinit var sink: FileArchivalSink

    @BeforeEach
    fun setUp() {
        // One fresh tmpDir per test — avoids the day-rollover gotcha (tests running near
        // midnight UTC would otherwise see leftover files from "yesterday" of a previous
        // run). Files.createTempDirectory cleans up via the deleteRecursively in @AfterEach.
        tmpRoot = Files.createTempDirectory("file-archival-sink-test-")
        sink = FileArchivalSink(tmpRoot)
    }

    @AfterEach
    fun tearDown() {
        // Best-effort recursive cleanup. We don't fail the test on cleanup errors — some
        // Windows AV scanners briefly hold file handles right after a write and a missed
        // cleanup just leaves a few KB of JSONL under %TEMP%.
        runCatching {
            Files.walk(tmpRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `archive writes one JSONL line per record under category-day file`() = runBlocking {
        val records = listOf(
            sampleRecord(state = JobState.SUCCEEDED),
            sampleRecord(state = JobState.SUCCEEDED),
            sampleRecord(state = JobState.SUCCEEDED),
        )

        sink.archive("job.succeeded", records)

        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val expectedFile = tmpRoot.resolve("job.succeeded").resolve("$day.jsonl")
        assertTrue(expectedFile.exists(), "expected $expectedFile to exist after archive()")

        val lines = expectedFile.readLines()
        assertEquals(records.size, lines.size, "one line per record")

        // Round-trip every line through the same Json encoder the sink uses to prove the
        // file is parseable JSONL and that the order is preserved. We compare ids since
        // the rest is identical by construction.
        val decoded = lines.map { FileArchivalSink.DEFAULT_JSON.decodeFromString(ArchivedJobRecord.serializer(), it) }
        assertEquals(records.map { it.id }, decoded.map { it.id })
    }

    @Test
    fun `successive archive calls append to same daily file in order`() = runBlocking {
        val first = sampleRecord(payloadType = "first-batch")
        val second = sampleRecord(payloadType = "second-batch")
        val third = sampleRecord(payloadType = "third-batch")

        sink.archive("job.failed", listOf(first))
        sink.archive("job.failed", listOf(second, third))

        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val file = tmpRoot.resolve("job.failed").resolve("$day.jsonl")
        val lines = file.readLines()
        assertEquals(3, lines.size, "two calls (1+2 records) should produce 3 lines, not 2 (overwrite) or 0")

        val decoded = lines.map { FileArchivalSink.DEFAULT_JSON.decodeFromString(ArchivedJobRecord.serializer(), it) }
        // Order matters: the retention loop processes oldest-first, and downstream readers
        // (`tail -f` cold-storage analysers) assume append-order ≈ archival-order.
        assertEquals(
            listOf("first-batch", "second-batch", "third-batch"),
            decoded.map { it.payloadType },
        )
    }

    @Test
    fun `category partitioning separates buckets and empty batch is a no-op`() = runBlocking {
        sink.archive("job.succeeded", listOf(sampleRecord(state = JobState.SUCCEEDED)))
        sink.archive("job.failed", listOf(sampleRecord(state = JobState.FAILED)))
        // Empty batch must NOT touch the filesystem — otherwise an empty retention pass
        // would litter `baseDir/job.cancelled/*.jsonl` with zero-byte files every tick.
        sink.archive("job.cancelled", emptyList())

        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val succeededDir = tmpRoot.resolve("job.succeeded")
        val failedDir = tmpRoot.resolve("job.failed")
        val cancelledDir = tmpRoot.resolve("job.cancelled")

        assertNotNull(succeededDir.resolve("$day.jsonl").takeIf { it.exists() }, "succeeded dir present")
        assertNotNull(failedDir.resolve("$day.jsonl").takeIf { it.exists() }, "failed dir present")
        assertFalse(cancelledDir.exists(), "empty batch must not create a category directory")

        // Cross-contamination guard: a `tail -f job.succeeded/...` reader must never see
        // a FAILED record. Trivial here since we partition by directory, but worth
        // asserting so a future refactor that merges files trips this test.
        val sucLines = succeededDir.resolve("$day.jsonl").readLines()
        val failLines = failedDir.resolve("$day.jsonl").readLines()
        val sucRecords = sucLines.map { FileArchivalSink.DEFAULT_JSON.decodeFromString(ArchivedJobRecord.serializer(), it) }
        val failRecords = failLines.map { FileArchivalSink.DEFAULT_JSON.decodeFromString(ArchivedJobRecord.serializer(), it) }
        assertTrue(sucRecords.all { it.state == JobState.SUCCEEDED })
        assertTrue(failRecords.all { it.state == JobState.FAILED })
    }

    @Test
    fun `custom Json instance is honoured for encoding`() = runBlocking {
        // The constructor accepts a `Json` override — exercise it so future refactors that
        // hardwire the encoder break a test instead of a downstream consumer.
        //
        // We CAN'T use `prettyPrint = true` here: JSONL semantics require a single record
        // per line and prettyPrint inserts newlines inside the object. Instead flip
        // `explicitNulls = false` — this DOES change wire output (nullable fields with a
        // `null` value get omitted entirely, vs DEFAULT_JSON which emits `"foo":null`).
        // Comparing line LENGTH is the most robust assertion that doesn't pin a specific
        // byte layout while still proving the custom Json instance reached the encoder.
        val customJson = Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
        val customSink = FileArchivalSink(tmpRoot, customJson)

        val record = sampleRecord()  // ~15 nullable fields are null → ~15 `:null` to omit
        customSink.archive("job.cancelled", listOf(record))
        sink.archive("job.cancelled-default", listOf(record))

        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val customLine = tmpRoot.resolve("job.cancelled").resolve("$day.jsonl").readLines().single()
        val defaultLine = tmpRoot.resolve("job.cancelled-default").resolve("$day.jsonl").readLines().single()

        assertTrue(
            customLine.length < defaultLine.length,
            "customJson (explicitNulls=false) should be shorter than DEFAULT_JSON " +
                "(custom=${customLine.length}, default=${defaultLine.length})",
        )
        assertFalse(
            customLine.contains("\"lockedBy\":null"),
            "explicitNulls=false should drop null nullable fields, found: $customLine",
        )
        // Round-trip sanity: the trimmed line still parses to the original record.
        val decoded = customJson.decodeFromString(ArchivedJobRecord.serializer(), customLine)
        assertEquals(record.id, decoded.id)
    }

    private fun sampleRecord(
        state: JobState = JobState.SUCCEEDED,
        payloadType: String = "test.sample",
    ): ArchivedJobRecord {
        val now = Clock.System.now()
        return ArchivedJobRecord(
            id = Uuid.random().toString(),
            state = state,
            queue = "default",
            priority = 0,
            payloadType = payloadType,
            payloadJson = """{"foo":"bar"}""",
            scheduledAt = null,
            attempts = 1,
            maxAttempts = 3,
            timeoutSeconds = null,
            lockedBy = null,
            lockedUntil = null,
            pendingDeps = 0,
            initialPendingDeps = 0,
            version = 1,
            idempotencyKey = null,
            targetNode = null,
            targetTag = null,
            progress = null,
            progressMsg = null,
            progressUpdatedAt = null,
            startedAt = now,
            durationMs = 12L,
            cancelRequestedAt = null,
            cancelRequestedBy = null,
            contextJson = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}

package cs.trade.scheduler.core.backend.archival

import cs.trade.scheduler.shared.archival.ArchivedJobRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Reference [ArchivalSink] writing JSON-lines under `baseDir/<category>/<YYYY-MM-DD>.jsonl`.
 * Daily files keep each shard cheap to read back. Append-mode + atomic write ordering
 * mean a crash mid-batch leaves partially-written records that the JSON-lines reader
 * still parses (broken last line is the only loss).
 *
 * **Not durable past a single process** — `Files.write` flushes to the OS but doesn't
 * fsync. For tighter durability, wrap with an explicit `Files.newByteChannel(...).force()`
 * call or replace this with an S3/GCS sink. Adequate for dev/single-node setups.
 */
public class FileArchivalSink(
    private val baseDir: Path,
    private val json: Json = DEFAULT_JSON,
) : ArchivalSink {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun archive(category: String, batch: List<ArchivedJobRecord>) {
        if (batch.isEmpty()) return
        withContext(Dispatchers.IO) {
            val dir = baseDir.resolve(category)
            Files.createDirectories(dir)
            val day = LocalDate.now(ZoneOffset.UTC).toString()
            val file = dir.resolve("$day.jsonl")
            val payload = buildString {
                for (record in batch) {
                    append(json.encodeToString(ArchivedJobRecord.serializer(), record))
                    append('\n')
                }
            }
            Files.writeString(
                file,
                payload,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            log.info(
                "Archived {} {} row(s) to {}",
                batch.size, category, file,
            )
        }
    }

    public companion object {
        // No pretty-printing (one JSON object per line is the whole point of JSONL),
        // encodeDefaults so older readers see complete shapes when newer writers omit
        // newly-added default fields.
        public val DEFAULT_JSON: Json = Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

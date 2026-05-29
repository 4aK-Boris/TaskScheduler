package cs.trade.scheduler.engine.worker.infrastructure

import cs.trade.scheduler.core.backend.schema.SchemaHasher
import cs.trade.scheduler.storage.postgres.domain.repositories.PayloadSchemaRepository
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import kotlin.reflect.full.starProjectedType

/**
 * One-shot schema-drift check, run at worker startup (DESIGN.md 22.9).
 *
 * For every payload type this worker handles ([HandlerRegistry.knownPayloadTypes]) it computes
 * the current serialization-schema hash ([SchemaHasher] over the type's `SerialDescriptor`) and
 * compares it with the last value recorded in `payload_schema`. A change since the previous
 * deploy means in-flight jobs of that type — serialized with the OLD schema — may no longer
 * deserialize on this worker, so we WARN and invoke [onDrift].
 *
 * **Best-effort.** A payload class that can't be loaded, or a DB that hasn't applied the V5
 * migration yet, is logged and skipped — the check never blocks worker startup. (Real
 * incompatibilities still surface as terminal FAILED at decode time, the §22.9 baseline; this
 * is just the proactive heads-up.) Drift dedups naturally across a fleet: the first worker to
 * record the new hash sees the change, the rest see it unchanged.
 */
public class SchemaDriftCheck(
    private val handlers: HandlerRegistry,
    private val payloadSchemas: PayloadSchemaRepository,
    private val onDrift: ((payloadType: String, previousHash: String, currentHash: String) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(SchemaDriftCheck::class.java)

    public suspend fun run() {
        for (payloadType in handlers.knownPayloadTypes) {
            val currentHash = runCatching {
                val kClass = Class.forName(payloadType).kotlin
                SchemaHasher.hash(serializer(kClass.starProjectedType).descriptor)
            }.getOrElse {
                log.debug("Schema-drift: cannot derive a serializer for {} — skipped ({})", payloadType, it.message)
                continue
            }

            val result = runCatching { payloadSchemas.recordAndDetect(payloadType, currentHash) }.getOrElse {
                // Most likely the payload_schema table isn't there yet (worker booted before
                // infra applied V5). Non-fatal — drift tracking just sits out this boot.
                log.warn("Schema-drift: could not record {} (is the DB migrated to V5?): {}", payloadType, it.message)
                continue
            }

            if (result.changed) {
                val previous = result.previousHash ?: ""
                log.warn(
                    "Payload schema for {} changed since the last deploy ({} -> {}). In-flight jobs " +
                        "of this type serialized with the old schema may fail to deserialize.",
                    payloadType, previous, currentHash,
                )
                onDrift?.invoke(payloadType, previous, currentHash)
            }
        }
    }
}

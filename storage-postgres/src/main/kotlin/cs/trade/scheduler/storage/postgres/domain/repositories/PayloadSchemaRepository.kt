package cs.trade.scheduler.storage.postgres.domain.repositories

/**
 * Tracks the last-seen serialization-schema hash per payload type, for schema-drift
 * detection (DESIGN.md 22.9). Backed by the `payload_schema` table.
 */
public interface PayloadSchemaRepository {

    /**
     * Record [schemaHash] as the current schema of [payloadType] and report whether it
     * drifted from what was stored:
     *  - no prior row → INSERT it; [SchemaCheck.changed] = false (first sighting isn't a drift)
     *  - identical hash → no write; changed = false
     *  - different hash → UPDATE to the new hash; changed = true, [SchemaCheck.previousHash] set
     *
     * Atomic per call. The INSERT path uses ON CONFLICT DO NOTHING so simultaneous fleet
     * startup (several workers of the same app recording the same type at once) can't throw
     * on a primary-key clash — the loser simply observes the row as already present.
     */
    public suspend fun recordAndDetect(payloadType: String, schemaHash: String): SchemaCheck

    public data class SchemaCheck(val changed: Boolean, val previousHash: String?)
}

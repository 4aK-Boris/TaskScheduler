package cs.trade.scheduler.storage.postgres.infrastructure

/**
 * Classpath root for the scheduler's Flyway migrations.
 *
 * Deliberately NOT under Flyway's default `db/migration`: a consumer app embeds this library
 * on its classpath, and Flyway scans `classpath:db/migration` **recursively**. Sharing that
 * root would mix the scheduler's versioned scripts with the consumer's own migrations —
 * version collisions, or the consumer's Flyway "applying" our `job`/`outbox` schema and vice
 * versa. Keeping our scripts under `classpath:scheduler/migration` and pinning every Flyway
 * config in the library (and its tests) to [SCHEDULER_MIGRATION_LOCATION] guarantees the two
 * streams never see each other.
 *
 * NOTE: this isolates migration **resolution**. If the scheduler runs its migrations against
 * the SAME database as a consumer's own Flyway, the shared default `flyway_schema_history`
 * table is a separate concern — run the scheduler against its own database/schema, or pin a
 * distinct history table.
 */
public const val SCHEDULER_MIGRATION_LOCATION: String = "classpath:scheduler/migration"

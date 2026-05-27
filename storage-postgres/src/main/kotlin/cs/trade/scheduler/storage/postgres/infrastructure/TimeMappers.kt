package cs.trade.scheduler.storage.postgres.infrastructure

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * Bridges between the public `kotlin.time.Instant` (domain) and `java.time.OffsetDateTime`
 * (Exposed timestampWithTimeZone column type). Always normalises to UTC offset on the
 * Java side — Postgres TIMESTAMPTZ stores absolute moments, the offset is just a hint.
 */
internal fun Instant.toOffsetDateTimeUtc(): OffsetDateTime =
    this.toJavaInstant().atOffset(ZoneOffset.UTC)

internal fun OffsetDateTime.toKotlinTime(): Instant =
    this.toInstant().toKotlinInstant()

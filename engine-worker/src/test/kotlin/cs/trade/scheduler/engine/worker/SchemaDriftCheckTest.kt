package cs.trade.scheduler.engine.worker

import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobContext
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.JobType
import cs.trade.scheduler.engine.worker.infrastructure.HandlerRegistry
import cs.trade.scheduler.engine.worker.infrastructure.SchemaDriftCheck
import cs.trade.scheduler.storage.postgres.domain.repositories.PayloadSchemaRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// File-level @Serializable payloads so `Class.forName(payloadType)` inside SchemaDriftCheck
// resolves them (a nested class's binary name uses '$', not the '.' qualifiedName the
// registry stores — same gotcha other worker tests avoid by lifting payloads to file level).
@Serializable
data class SchemaDriftTestPayloadA(val userId: Long, val template: String) : Job

@Serializable
data class SchemaDriftTestPayloadB(val n: Long) : Job

/**
 * [SchemaDriftCheck] behaviour with an in-memory [PayloadSchemaRepository] fake (DESIGN.md 22.9).
 * The real `serializer(...).descriptor` + [SchemaHasher] run for real; only the storage is faked.
 */
class SchemaDriftCheckTest {

    @JobType(SchemaDriftTestPayloadA::class)
    private class HandlerA : JobHandler<SchemaDriftTestPayloadA> {
        override suspend fun execute(ctx: JobContext, job: SchemaDriftTestPayloadA) {}
    }

    @JobType(SchemaDriftTestPayloadB::class)
    private class HandlerB : JobHandler<SchemaDriftTestPayloadB> {
        override suspend fun execute(ctx: JobContext, job: SchemaDriftTestPayloadB) {}
    }

    /** In-memory PayloadSchemaRepository with the same record/detect semantics as the PG impl. */
    private class FakeSchemaRepo(initial: Map<String, String> = emptyMap()) : PayloadSchemaRepository {
        val store = initial.toMutableMap()
        override suspend fun recordAndDetect(payloadType: String, schemaHash: String): PayloadSchemaRepository.SchemaCheck {
            val prev = store[payloadType]
            return when (prev) {
                null -> { store[payloadType] = schemaHash; PayloadSchemaRepository.SchemaCheck(false, null) }
                schemaHash -> PayloadSchemaRepository.SchemaCheck(false, prev)
                else -> { store[payloadType] = schemaHash; PayloadSchemaRepository.SchemaCheck(true, prev) }
            }
        }
    }

    private val typeA = SchemaDriftTestPayloadA::class.qualifiedName!!
    private val typeB = SchemaDriftTestPayloadB::class.qualifiedName!!

    @Test
    fun `alerts only for a payload type whose schema changed`() = runBlocking {
        // A was last seen with a different (stale) hash → drift; B is brand new → first-seen.
        val repo = FakeSchemaRepo(mapOf(typeA to "stale-hash-from-an-older-deploy"))
        val alerts = mutableListOf<Triple<String, String, String>>()
        val check = SchemaDriftCheck(HandlerRegistry(listOf(HandlerA(), HandlerB())), repo) { pt, prev, cur ->
            alerts += Triple(pt, prev, cur)
        }

        check.run()

        assertEquals(1, alerts.size, "only the drifted type should alert; got $alerts")
        assertEquals(typeA, alerts.single().first)
        assertEquals("stale-hash-from-an-older-deploy", alerts.single().second, "alert carries the previous hash")
        assertTrue(repo.store.containsKey(typeB), "the first-seen type must be recorded for next time")
        assertTrue(repo.store.getValue(typeA) != "stale-hash-from-an-older-deploy", "drifted type's hash is updated")
    }

    @Test
    fun `first-seen then unchanged stays quiet`() = runBlocking {
        val repo = FakeSchemaRepo()
        val alerts = mutableListOf<String>()
        val check = SchemaDriftCheck(HandlerRegistry(listOf(HandlerA())), repo) { pt, _, _ -> alerts += pt }

        check.run() // first sighting → record, no alert
        check.run() // identical hash → no alert

        assertTrue(alerts.isEmpty(), "first-seen then unchanged must never alert; got $alerts")
    }
}

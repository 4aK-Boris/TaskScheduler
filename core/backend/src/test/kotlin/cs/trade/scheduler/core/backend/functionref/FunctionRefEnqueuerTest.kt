package cs.trade.scheduler.core.backend.functionref

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [FunctionRefEnqueuer]'s extract-and-serialise step (DESIGN.md 21.2,
 * 21.6, 21.8). No Koin, no scheduler — just the helper that turns a `KFunction` + args
 * into a wire payload.
 *
 * The classes / handlers below live at file top-level so their `qualifiedName` resolves
 * to a flat FQN — same pattern as `WorkerTimeoutIntegrationTest.HangingJob` (KSP gotcha
 * with nested classes, see commit history).
 */
class FunctionRefEnqueuerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "_type" }

    @Test
    fun `KFunction reference produces targetType + signature with FQN parameters`() {
        val res = FunctionRefEnqueuer.build(
            method = SampleMailer::send,
            args = listOf(42L, "welcome"),
            targetQualifier = null,
            json = json,
        )
        assertEquals(SampleMailer::class, res.receiverClass)
        assertEquals(
            "cs.trade.scheduler.core.backend.functionref.SampleMailer",
            res.payload.targetType,
        )
        assertEquals("send(kotlin.Long,kotlin.String)", res.payload.methodSignature)
        assertEquals(2, res.payload.args.size)
        assertEquals(JsonPrimitive(42L), res.payload.args[0])
        assertEquals(JsonPrimitive("welcome"), res.payload.args[1])
    }

    @Test
    fun `qualifier is propagated to payload`() {
        val res = FunctionRefEnqueuer.build(
            method = SampleMailer::send,
            args = listOf(1L, "hi"),
            targetQualifier = "smtp",
            json = json,
        )
        assertEquals("smtp", res.payload.targetQualifier)
    }

    @Test
    fun `serializable data-class arg encodes through kotlinx-serialization`() {
        val res = FunctionRefEnqueuer.build(
            method = SampleReporter::send,
            args = listOf(ReportSpec(id = 7, label = "Q1")),
            targetQualifier = null,
            json = json,
        )
        // The single arg should be encoded as a JsonObject with the data class's fields.
        val arg = res.payload.args.single()
        val text = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), arg)
        assertTrue(text.contains("\"id\""), "encoded arg must carry data class fields: $text")
        assertTrue(text.contains("\"label\""))
    }

    @Test
    fun `null arg on a non-nullable parameter fails fast at enqueue`() {
        val ex = runCatching {
            // Cast forces a null through the typed overload's Long parameter at the
            // KFunction.build call site (kotlin compiler won't let us pass `null` to a
            // non-nullable `Long` directly).
            @Suppress("UNCHECKED_CAST")
            FunctionRefEnqueuer.build(
                method = SampleMailer::send,
                args = listOf<Any?>(null, "x"),
                targetQualifier = null,
                json = json,
            )
        }.exceptionOrNull()
        assertNotNull(ex, "passing null to a non-nullable parameter should throw")
        assertTrue(
            ex is IllegalArgumentException,
            "expected IllegalArgumentException, got ${ex?.let { it::class.qualifiedName }}: ${ex?.message}",
        )
        assertTrue(
            (ex?.message ?: "").contains("not nullable"),
            "error message must explain the nullability mismatch: ${ex?.message}",
        )
    }

    @Test
    fun `nullable parameter accepts a null arg, encoded as JsonNull`() {
        val res = FunctionRefEnqueuer.build(
            method = SampleNullable::maybe,
            args = listOf<Any?>(null),
            targetQualifier = null,
            json = json,
        )
        assertEquals(JsonNull, res.payload.args.single())
    }

    @Test
    fun `non-Serializable arg type fails fast at enqueue with IAE`() {
        val ex = runCatching {
            FunctionRefEnqueuer.build(
                method = SampleNonSerializable::take,
                args = listOf(NonSerializableThing(99)),
                targetQualifier = null,
                json = json,
            )
        }.exceptionOrNull()
        assertNotNull(ex, "non-@Serializable arg must throw at enqueue")
        assertTrue(ex is IllegalArgumentException, "expected IAE, got: $ex")
        val msg = ex?.message ?: ""
        assertTrue(
            msg.contains("@Serializable") || msg.contains("Serializer"),
            "message should mention serializability — got: $msg",
        )
    }

    @Test
    fun `wrong arg count throws — KFunction expects N, got M`() {
        val ex = runCatching {
            FunctionRefEnqueuer.build(
                method = SampleMailer::send,
                args = listOf(1L), // expected 2
                targetQualifier = null,
                json = json,
            )
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
        assertTrue(
            (ex?.message ?: "").contains("expects 2"),
            "message should call out the expected vs actual arg count: ${ex?.message}",
        )
    }

    @Test
    fun `method overload disambiguation — methodSignatureOf reflects parameter types`() {
        // Bare `SampleOverloaded::send` is ambiguous in Kotlin source; users
        // disambiguate via a typed binding at the call site (the call site's expected
        // KFunctionN type picks the matching overload). Here we test the signature
        // builder directly against the reflectively-found functions, which is enough
        // to prove signatures differ by parameter type — the disambig itself is a
        // Kotlin language concern, not ours.
        val funcs = SampleOverloaded::class.java.kotlin.members
            .filterIsInstance<kotlin.reflect.KFunction<*>>()
            .filter { it.name == "send" }
        val signatures = funcs.map { FunctionRefEnqueuer.methodSignatureOf(it) }.toSet()
        assertEquals(setOf("send(kotlin.Long)", "send(kotlin.Long,kotlin.String)"), signatures)
    }
}

// ---- Fixtures (top-level so qualifiedName is stable / no nested-class gotcha) -------

class SampleMailer {
    @Suppress("unused")
    suspend fun send(userId: Long, template: String): Unit = Unit
}

class SampleNullable {
    @Suppress("unused")
    suspend fun maybe(reason: String?): Unit = Unit
}

@Serializable
data class ReportSpec(val id: Long, val label: String)

class SampleReporter {
    @Suppress("unused")
    suspend fun send(spec: ReportSpec): Unit = Unit
}

class NonSerializableThing(val v: Int)

class SampleNonSerializable {
    @Suppress("unused")
    suspend fun take(thing: NonSerializableThing): Unit = Unit
}

class SampleOverloaded {
    @Suppress("unused")
    suspend fun send(userId: Long): Unit = Unit
    @Suppress("unused")
    suspend fun send(userId: Long, template: String): Unit = Unit
}

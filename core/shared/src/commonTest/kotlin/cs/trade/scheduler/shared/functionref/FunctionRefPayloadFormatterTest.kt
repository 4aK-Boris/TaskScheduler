package cs.trade.scheduler.shared.functionref

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for [FunctionRefPayloadFormatter]. Pin the wire-to-display contract so a
 * refactor doesn't silently regress the JobDetail rendering — dashboard UI uses this
 * formatter directly for the function-ref branch.
 */
class FunctionRefPayloadFormatterTest {

    @Test
    fun `format two-arg primitive — oneLine matches Mailer dot send pattern`() {
        val payload = FunctionRefPayload(
            targetType = "com.example.Mailer",
            targetQualifier = null,
            methodSignature = "send(kotlin.Long,kotlin.String)",
            args = listOf(JsonPrimitive(123L), JsonPrimitive("welcome")),
        )
        val out = FunctionRefPayloadFormatter.format(payload)
        assertEquals("Mailer.send(123, \"welcome\")", out.oneLine)
        assertEquals("Mailer", out.receiverSimpleName)
        assertEquals("send", out.methodName)
        assertEquals(listOf("Long", "String"), out.args.map { it.type })
        assertEquals(listOf("123", "\"welcome\""), out.args.map { it.valueRendered })
        assertNull(out.targetQualifier)
    }

    @Test
    fun `zero-arg method — empty args list, parens still rendered`() {
        val payload = FunctionRefPayload(
            targetType = "com.example.Healthcheck",
            methodSignature = "ping()",
            args = emptyList(),
        )
        val out = FunctionRefPayloadFormatter.format(payload)
        assertEquals("Healthcheck.ping()", out.oneLine)
        assertTrue(out.args.isEmpty())
    }

    @Test
    fun `null arg with nullable parameter renders as null literal`() {
        val payload = FunctionRefPayload(
            targetType = "com.example.X",
            methodSignature = "maybe(kotlin.String)",
            args = listOf(JsonNull),
        )
        val out = FunctionRefPayloadFormatter.format(payload)
        assertEquals("X.maybe(null)", out.oneLine)
    }

    @Test
    fun `composite arg (data class object) — rendered as compact JSON`() {
        // A @Serializable data class encoded to JsonElement becomes a JsonObject. The
        // formatter shouldn't try to pretty-print it — compact toString is fine and
        // reads about as well as raw JSON would.
        val spec = buildJsonObject {
            put("id", 7L)
            put("label", "Q1")
        }
        val payload = FunctionRefPayload(
            targetType = "com.example.Reporter",
            methodSignature = "send(com.example.ReportSpec)",
            args = listOf(spec),
        )
        val out = FunctionRefPayloadFormatter.format(payload)
        assertEquals("Reporter.send({\"id\":7,\"label\":\"Q1\"})", out.oneLine)
        assertEquals("ReportSpec", out.args.single().type)
    }

    @Test
    fun `qualifier is preserved in the Formatted result`() {
        val payload = FunctionRefPayload(
            targetType = "com.example.Mailer",
            targetQualifier = "smtp",
            methodSignature = "send(kotlin.Long)",
            args = listOf(JsonPrimitive(1L)),
        )
        val out = FunctionRefPayloadFormatter.format(payload)
        assertEquals("smtp", out.targetQualifier)
    }

    @Test
    fun `tryFormat returns null on malformed JSON, not a crash`() {
        // Defence in depth — operator might paste a borked DB blob into the UI dev mode,
        // and the JobDetail page must keep rendering even if the payload is gibberish.
        val out = FunctionRefPayloadFormatter.tryFormat("not-a-json")
        assertNull(out)
    }

    @Test
    fun `tryFormat returns null when JSON parses but doesn't match the FunctionRefPayload shape`() {
        // Random JSON object — missing required fields.
        val out = FunctionRefPayloadFormatter.tryFormat("""{"hello":"world"}""")
        assertNull(out)
    }

    @Test
    fun `boolean and number args render bare without quotes`() {
        val payload = FunctionRefPayload(
            targetType = "com.example.Math",
            methodSignature = "compute(kotlin.Boolean,kotlin.Double)",
            args = listOf(JsonPrimitive(true), JsonPrimitive(3.14)),
        )
        val out = FunctionRefPayloadFormatter.format(payload)
        assertEquals("Math.compute(true, 3.14)", out.oneLine)
    }

    @Test
    fun `receiverSimpleName falls back to the full FQN when there is no dot`() {
        val payload = FunctionRefPayload(
            targetType = "TopLevel",   // no package
            methodSignature = "x()",
            args = emptyList(),
        )
        val out = FunctionRefPayloadFormatter.format(payload)
        assertEquals("TopLevel.x()", out.oneLine)
    }
}

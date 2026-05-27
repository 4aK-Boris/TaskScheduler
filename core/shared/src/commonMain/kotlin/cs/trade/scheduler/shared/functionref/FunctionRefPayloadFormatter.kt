package cs.trade.scheduler.shared.functionref

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Human-readable rendering of a [FunctionRefPayload] for the dashboard JobDetail page
 * (DESIGN.md 21). Turns the wire-format JSON into something an operator can read at a
 * glance:
 *
 * ```
 * Mailer.send(123, "welcome")
 * ```
 *
 * Receiver shows as the simple class name; method name + args render in Kotlin-call
 * syntax. The full FQN + qualifier remain available for tooling via the raw payload.
 *
 * Stays in `:core:shared` so the wasm dashboard can render it AND so a server-side
 * log formatter can use the same routine — single source of truth for "how do we say
 * this in human terms".
 */
public object FunctionRefPayloadFormatter {

    /**
     * Parse [payloadJson] (the row's `payload_json` column) as a [FunctionRefPayload] and
     * format. Returns `null` if the JSON doesn't parse — caller falls back to raw JSON
     * display. Catching all exceptions defensively because malformed payload should NOT
     * crash a UI render path.
     */
    public fun tryFormat(payloadJson: String, json: Json = DEFAULT_JSON): Formatted? =
        runCatching { format(json.decodeFromString(FunctionRefPayload.serializer(), payloadJson)) }
            .getOrNull()

    /**
     * Compact one-liner — `Mailer.send(123, "welcome")`. Useful for log lines / list rows
     * where vertical space is precious.
     */
    public fun formatOneLine(payload: FunctionRefPayload): String {
        val receiver = receiverSimpleName(payload.targetType)
        val method = methodNameOf(payload.methodSignature)
        val args = payload.args.joinToString(", ") { renderArg(it) }
        return "$receiver.$method($args)"
    }

    /**
     * Multi-block render with field labels — what JobDetail shows. The data class lets
     * the UI pick which pieces to highlight (e.g. monospace the method line, label the
     * qualifier as "Koin binding").
     */
    public fun format(payload: FunctionRefPayload): Formatted {
        val receiver = receiverSimpleName(payload.targetType)
        val method = methodNameOf(payload.methodSignature)
        val paramTypes = paramTypesOf(payload.methodSignature)
        val labeledArgs = paramTypes.zip(payload.args).map { (type, value) ->
            ArgLine(type = type.simpleName(), valueRendered = renderArg(value))
        }
        return Formatted(
            oneLine = formatOneLine(payload),
            receiverFqn = payload.targetType,
            receiverSimpleName = receiver,
            methodName = method,
            args = labeledArgs,
            targetQualifier = payload.targetQualifier,
        )
    }

    public data class Formatted(
        /** Compact rendering: `Mailer.send(123, "welcome")`. */
        val oneLine: String,
        val receiverFqn: String,
        val receiverSimpleName: String,
        val methodName: String,
        /** One per non-receiver argument, with simple-name type label. */
        val args: List<ArgLine>,
        val targetQualifier: String?,
    )

    public data class ArgLine(
        /** Simple name of the parameter's declared type (`Long`, `String`, `ReportSpec`). */
        val type: String,
        /** Argument value as a Kotlin literal — `123`, `"welcome"`, `{"id":7,"label":"Q1"}`. */
        val valueRendered: String,
    )

    // ---- Internals --------------------------------------------------------

    /** Default Json used when caller doesn't supply one. Lenient with leftover fields. */
    private val DEFAULT_JSON: Json = Json {
        ignoreUnknownKeys = true
    }

    /** `com.example.Mailer` → `Mailer`. Anonymous (`<anonymous>`) and empty stay as-is. */
    private fun receiverSimpleName(fqn: String): String =
        fqn.substringAfterLast('.').ifBlank { fqn }

    /** `"send(kotlin.Long,kotlin.String)"` → `"send"`. */
    private fun methodNameOf(signature: String): String =
        signature.substringBefore('(').ifBlank { signature }

    /**
     * `"send(kotlin.Long,kotlin.String)"` → `["kotlin.Long", "kotlin.String"]`. Empty list
     * for zero-arg method. Trims to tolerate whitespace if a future formatter inserts any.
     */
    private fun paramTypesOf(signature: String): List<String> {
        val inside = signature.substringAfter('(', missingDelimiterValue = "")
            .substringBeforeLast(')', missingDelimiterValue = "")
        if (inside.isBlank()) return emptyList()
        return inside.split(',').map { it.trim() }
    }

    /** `kotlin.Long` → `Long`. Generic parameter types pass through (no `<>` parsing). */
    private fun String.simpleName(): String = substringAfterLast('.').ifBlank { this }

    /**
     * Render a single arg as a Kotlin literal:
     *  - String → quoted with double-quotes (no escaping of inner quotes for now — UI
     *    tolerates the visual artifact and a perfectly correct quote-escaper would just
     *    be re-implementing `JsonPrimitive.toString()` on the literal).
     *  - Boolean / Number / Null → bare.
     *  - Composite (object / array) → compact JSON via `toString()` — Kotlin literal
     *    rendering would need a full pretty-printer.
     */
    private fun renderArg(el: JsonElement): String = when (el) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (el.isString) "\"${el.content}\"" else el.content
        else -> el.toString()
    }
}

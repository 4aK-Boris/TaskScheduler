package cs.trade.scheduler.shared.functionref

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Job payload for the function-reference API (DESIGN.md 21).
 *
 * Stored in the `job` row's `payload_json` column when the user enqueues via the
 * `Scheduler.enqueue(SomeClass::method, args…)` overloads (or any future
 * KSP/compiler-plugin sugar that lowers a lambda to a function-ref call). The
 * row's `payload_type` is the sentinel [FUNCTION_REF_PAYLOAD_TYPE] — the worker
 * branches on that and dispatches via the function-ref runner instead of looking up
 * a `JobHandler` by FQN.
 *
 * Lives in `:core:shared` (KMP `commonMain`) so the wasm dashboard can parse the
 * payload to render a human-readable `Mailer.send(123, "welcome")` string in JobDetail
 * instead of dumping raw JSON. The JVM-only enqueue/execute machinery
 * (`FunctionRefEnqueuer`, `FunctionRefRunner`) reads/writes this same wire shape.
 *
 * Stable wire format. All fields are primitive enough to read with any JSON
 * tool — there is no class-discriminator polymorphism here.
 *
 * @param targetType FQN of the receiver class. Resolved at execute time via
 *   `Class.forName(...).kotlin` then looked up in Koin (`koin.get(KClass, qualifier)`).
 * @param targetQualifier Optional Koin named qualifier — required when the user has
 *   multiple bindings of [targetType]. `null` means "single binding", and the enqueue
 *   path fails fast if that turns out to be ambiguous.
 * @param methodSignature Full disambiguating signature in the form
 *   `"send(kotlin.Long,kotlin.String)"` — name + comma-separated FQN parameter types in
 *   declaration order. Built from a `KFunction` reference at enqueue time; matched at
 *   execute time against the receiver class's `functions`. Survives method overloading;
 *   does NOT survive parameter renames/reorders (documented refactor trade-off in
 *   DESIGN.md 21.7).
 * @param args JSON-encoded argument list, one element per non-receiver parameter, in
 *   declaration order. Each element was produced by `Json.encodeToJsonElement(serializer)`
 *   at enqueue time using the parameter's declared `KType`. On execute we apply the same
 *   serializer in reverse — so the per-arg payload survives `Any?`/erasure boundaries
 *   that a flat `List<Any>` couldn't cross.
 */
@Serializable
public data class FunctionRefPayload(
    val targetType: String,
    val targetQualifier: String? = null,
    val methodSignature: String,
    val args: List<JsonElement>,
) {
    public companion object {
        /**
         * Sentinel for `job.payload_type` that tells the worker "this is a function-ref
         * job, not a sealed-class one — decode payload_json as [FunctionRefPayload] and
         * dispatch through the runner, not a `JobHandler`".
         *
         * Chosen to be impossible to collide with a real Kotlin class FQN: no real class
         * is named simply `function_ref` (no dot, lowercase, snake_case).
         */
        public const val FUNCTION_REF_PAYLOAD_TYPE: String = "function_ref"
    }
}

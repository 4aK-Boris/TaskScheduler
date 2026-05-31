package cs.trade.scheduler.core.backend.functionref

import cs.trade.scheduler.shared.functionref.FunctionRefPayload
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.jvmErasure

/**
 * Build a [FunctionRefPayload] from a `KFunction` reference + its declared args.
 *
 * Receiver class derivation:
 *  - The first [KParameter] of any non-extension instance `KFunction` is the dispatch
 *    receiver. Its `type` carries the class we'll Koin-resolve at execute time. Extension
 *    receivers / unbound references currently aren't supported — DESIGN.md 21.2 only
 *    promises the unbound-method-reference shape (`Mailer::send`).
 *
 * Argument serialization:
 *  - For each non-receiver parameter, encode `arg` via `Json.encodeToJsonElement(serializer(kType), arg)`.
 *    The declared `KType` (not `arg::class`) drives the serializer choice — this lets us
 *    encode `null`, generic containers, and primitive wrappers correctly without having
 *    to introspect arg runtime types.
 *  - `null` args bypass serialization (`JsonNull`). The parameter's `KType.isMarkedNullable`
 *    is checked so we fail fast if the user passes `null` to a non-nullable param.
 *
 * Fail-fast policy (DESIGN.md 21.6):
 *  - A non-`@Serializable` arg type raises [IllegalArgumentException] at the enqueue
 *    site, not at execute time. The exception names the parameter index and the type so
 *    the dev can read which arg is the problem from the stack trace.
 *  - We do NOT wrap the underlying [SerializationException] — re-throw it as the cause
 *    of an IAE so callers can `catch (e: IllegalArgumentException)` consistently.
 */
public object FunctionRefEnqueuer {

    /**
     * Inspect [method] + its [args], produce a wire payload + return the receiver
     * [KClass] separately so the caller can do its own Koin multi-binding check.
     */
    public fun build(
        method: KFunction<*>,
        args: List<Any?>,
        targetQualifier: String?,
        json: Json,
    ): Result {
        val params = method.parameters
        require(params.isNotEmpty() && params[0].kind == KParameter.Kind.INSTANCE) {
            "Function-ref API only supports unbound member references (Receiver::method). " +
                "Got method=$method whose first parameter kind is " +
                (params.firstOrNull()?.kind ?: "none") +
                ". For top-level / extension functions use a sealed-class Job instead."
        }
        val receiverType = params[0].type
        val receiverClass: KClass<*> = receiverType.jvmErasure
        val valueParams = params.drop(1)
        require(valueParams.size == args.size) {
            "Function-ref API: ${method.name} expects ${valueParams.size} arg(s) but " +
                "${args.size} were passed. Args: $args"
        }

        val encoded = ArrayList<JsonElement>(args.size)
        for ((idx, param) in valueParams.withIndex()) {
            val arg = args[idx]
            encoded += encodeArg(method, idx, param, arg, json)
        }
        return Result(
            receiverClass = receiverClass,
            payload = FunctionRefPayload(
                targetType = receiverClass.qualifiedName
                    ?: error("Anonymous / local receiver class can't be function-ref'd: $receiverClass"),
                targetQualifier = targetQualifier,
                methodSignature = methodSignatureOf(method),
                args = encoded,
            ),
        )
    }

    /**
     * Variant for the compiler-plugin lowering of `enqueueLambda { … }` (DESIGN.md 21.9).
     * The plugin can't hand us a `KFunction` in IR, so it gives us the receiver's declared
     * type FQN + the method's disambiguating [methodSignatureOf] string instead. We reflect
     * the `KFunction` back out — the SAME way the worker's `FunctionRefRunner` resolves a
     * method on the receiver class — and delegate to [build]. So the wire payload, the args
     * serialisation (declared-`KType`-driven), and the fail-fast policy are all identical to
     * the explicit `enqueue(Recv::method, …)` path.
     *
     * Class-loading / signature-matching failures surface as [IllegalArgumentException] at
     * the enqueue site, matching [build]'s fail-fast contract.
     */
    public fun buildFromTarget(
        targetType: String,
        methodSignature: String,
        args: List<Any?>,
        targetQualifier: String?,
        json: Json,
    ): Result {
        val receiverClass = runCatching { Class.forName(targetType).kotlin }.getOrElse { cause ->
            throw IllegalArgumentException(
                "Function-ref API (lambda capture): receiver class $targetType is not on the " +
                    "classpath at enqueue time. The compiler plugin emitted this from a " +
                    "`enqueueLambda { … }` call — did the receiver type get renamed/removed?",
                cause,
            )
        }
        val method = resolveBySignature(receiverClass, methodSignature)
        return build(method, args, targetQualifier, json)
    }

    /**
     * Find the member function on [receiverClass] whose [methodSignatureOf] equals
     * [signature]. Mirrors `FunctionRefRunner.resolveMethod` on the worker so both sides
     * agree on which overload a signature names.
     */
    private fun resolveBySignature(receiverClass: KClass<*>, signature: String): KFunction<*> {
        val candidates = receiverClass.functions.filter { methodSignatureOf(it) == signature }
        return when {
            candidates.size == 1 -> candidates.single()
            candidates.isEmpty() -> {
                val available = receiverClass.functions.joinToString("; ") { methodSignatureOf(it) }
                throw IllegalArgumentException(
                    "Function-ref API (lambda capture): no method on ${receiverClass.qualifiedName} " +
                        "matches signature $signature. Available: [$available].",
                )
            }
            // JVM base/override clash — same tie-break as the worker: take the first.
            else -> candidates.first()
        }
    }

    public data class Result(
        val receiverClass: KClass<*>,
        val payload: FunctionRefPayload,
    )

    /**
     * Build the disambiguating signature stored in [FunctionRefPayload.methodSignature].
     * Format: `name(param0Type,param1Type,...)` with FQN parameter types. Used by the
     * worker to find the right overload on the receiver class.
     *
     * Exposed as a top-level for symmetry with the runner's signature-matching code —
     * both call the same helper so we can't have one side say `(kotlin.Long)` and the
     * other `(long)`.
     */
    public fun methodSignatureOf(method: KFunction<*>): String {
        val valueTypes = method.parameters.drop(1).joinToString(",") { typeSignatureOf(it.type) }
        return "${method.name}($valueTypes)"
    }

    /**
     * Stable string representation of a [KType]: `kotlin.Long`, `kotlin.collections.List`,
     * `com.example.Foo`. Generics' arguments are NOT included — Kotlin reflection erases
     * them at the boundary anyway and any `List<Long>` matches any other `List<*>` from
     * the JVM's perspective. The actual element type comes from the serializer, which the
     * worker re-derives from the receiver method's KType (not from the signature string).
     */
    private fun typeSignatureOf(type: KType): String =
        type.jvmErasure.qualifiedName ?: type.jvmErasure.java.name

    private fun encodeArg(
        method: KFunction<*>,
        argIndex: Int,
        param: KParameter,
        arg: Any?,
        json: Json,
    ): JsonElement {
        if (arg == null) {
            require(param.type.isMarkedNullable) {
                "Function-ref API: ${method.name} arg[$argIndex] (${param.name}: ${param.type}) is not " +
                    "nullable, but `null` was passed."
            }
            return JsonNull
        }
        return runCatching {
            // serializer(KType) honours nullability + generics from the declared KType (and is
            // already typed KSerializer<Any?>). We bake the declared type into the wire format
            // implicitly; the worker re-derives the same KType from the resolved param to decode.
            json.encodeToJsonElement(serializer(param.type), arg)
        }.getOrElse { cause ->
            val hint = when (cause) {
                is SerializationException -> "@Serializable is missing or the type isn't supported by kotlinx-serialization"
                else -> "kotlinx-serialization failed"
            }
            throw IllegalArgumentException(
                "Function-ref API: ${method.name} arg[$argIndex] (${param.name}: ${param.type}, " +
                    "value=$arg) — $hint. Either annotate the arg type with @Serializable or use a " +
                    "sealed-class Job for this enqueue.",
                cause,
            )
        }
    }

    /**
     * Sometimes we need to reconstruct a `KType` for a class we only have at runtime
     * (e.g. inside the Koin multi-binding check). Exposed as a tiny helper so callers
     * don't have to remember to use `starProjectedType` — the function-ref runtime
     * always wants the raw, unparameterised type.
     */
    public fun rawType(clazz: KClass<*>): KType = clazz.createType()
}

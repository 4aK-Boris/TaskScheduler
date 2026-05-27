@file:OptIn(org.koin.core.annotation.KoinInternalApi::class)

package cs.trade.scheduler.engine.worker.infrastructure

import cs.trade.scheduler.core.backend.functionref.FunctionRefEnqueuer
import cs.trade.scheduler.core.backend.functionref.FunctionRefPayload
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.functions

/**
 * Worker-side executor for function-ref jobs (DESIGN.md 21.4). Called from
 * [WorkerPool.processLockedInner] when the row's `payload_type` equals
 * [FunctionRefPayload.FUNCTION_REF_PAYLOAD_TYPE].
 *
 * Lifecycle of one execution:
 *  1. Decode the [FunctionRefPayload] from `payload_json`.
 *  2. Resolve the target class via `Class.forName(...).kotlin`.
 *  3. Resolve the bean from Koin — either unqualified (single binding) or by named
 *     qualifier when one was set at enqueue time.
 *  4. Find the method on the receiver class whose signature matches
 *     [FunctionRefEnqueuer.methodSignatureOf]. Overloads disambiguate by parameter types.
 *  5. Deserialise each argument from its [JsonElement] using the parameter's `KType`
 *     (same serializer rules as the enqueue path — symmetric encode/decode).
 *  6. Invoke via [KFunction.callSuspend].
 *
 * Errors at any step are wrapped in a clear exception so the dashboard / job_event row
 * carries enough breadcrumbs to diagnose:
 *  - Unknown class → "target class not on the classpath"
 *  - Missing Koin binding → "no Koin binding for X" (qualifier hint when applicable)
 *  - Method signature mismatch → "no method matches signature S; available: …"
 *  - Arg deserialisation failure → "arg[N] decode failed (declared type=…)"
 *
 * All exceptions thrown from here propagate to `WorkerPool.handleFailure` and follow the
 * normal retry / FAILED machinery. There's no special "function-ref handler not found"
 * state — the row goes to FAILED with a clear message and the operator's recourse is the
 * dashboard MANUAL_RETRY action (which won't help here if the underlying code is gone,
 * but at least the row isn't bouncing in Rabbit).
 */
public class FunctionRefRunner(
    private val koin: Koin,
    private val json: Json,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun run(payloadJson: String) {
        val payload = decodePayload(payloadJson)
        val targetClass = resolveTargetClass(payload.targetType)
        val target = resolveBean(targetClass, payload.targetQualifier)
        val method = resolveMethod(targetClass, payload.methodSignature)
        val args = decodeArgs(method, payload.args)
        log.debug(
            "Function-ref invoke — target={}, method={}, qualifier={}, args.size={}",
            payload.targetType, payload.methodSignature, payload.targetQualifier, args.size,
        )
        method.callSuspend(target, *args.toTypedArray())
    }

    private fun decodePayload(payloadJson: String): FunctionRefPayload =
        runCatching { json.decodeFromString(FunctionRefPayload.serializer(), payloadJson) }
            .getOrElse {
                throw IllegalStateException(
                    "Function-ref payload could not be parsed as FunctionRefPayload. " +
                        "This usually means payload_type=function_ref was set with a payload_json " +
                        "from a different shape (bug or manual DB edit).",
                    it,
                )
            }

    private fun resolveTargetClass(fqn: String): KClass<*> =
        runCatching { Class.forName(fqn).kotlin }
            .getOrElse {
                throw IllegalStateException(
                    "Function-ref API: target class $fqn is not on the worker's classpath. " +
                        "Was the class renamed or removed since enqueue? See DESIGN.md 21.7 — " +
                        "function-ref jobs are documented to be fragile across refactors.",
                    it,
                )
            }

    private fun resolveBean(targetClass: KClass<*>, qualifier: String?): Any {
        val q = qualifier?.let(::named)
        return runCatching {
            koin.scopeRegistry.rootScope.get<Any>(clazz = targetClass, qualifier = q, parameters = null)
        }.getOrElse {
            throw IllegalStateException(
                "Function-ref API: cannot resolve ${targetClass.qualifiedName} " +
                    (if (qualifier != null) "qualified by \"$qualifier\"" else "(unqualified)") +
                    " from Koin. The enqueue-side check missed it (DI graph change between " +
                    "nodes?), or the user-app forgot to register the bean on this worker.",
                it,
            )
        }
    }

    private fun resolveMethod(targetClass: KClass<*>, signature: String): KFunction<*> {
        // Match by the same disambiguating signature the enqueue side built. Overloads
        // line up by parameter types, not by parameter names — so a parameter rename is
        // refactor-safe; a parameter REORDER is not (documented in DESIGN.md 21.7).
        val candidates = targetClass.functions.filter { FunctionRefEnqueuer.methodSignatureOf(it) == signature }
        if (candidates.size == 1) return candidates.single()
        if (candidates.isEmpty()) {
            val available = targetClass.functions.joinToString("; ") { FunctionRefEnqueuer.methodSignatureOf(it) }
            throw IllegalStateException(
                "Function-ref API: no method on ${targetClass.qualifiedName} matches signature " +
                    "$signature. Available: [$available]. Either the method was renamed/removed, " +
                    "or its parameter list changed.",
            )
        }
        // Tie — two methods with identical signatures? Shouldn't happen on the JVM unless
        // we have a base class + override clash. Pick the first declared and warn.
        log.warn(
            "Function-ref API: {} methods on {} match signature {} — picking the first. " +
                "Consider an explicit qualifier or a concrete subclass reference.",
            candidates.size, targetClass.qualifiedName, signature,
        )
        return candidates.first()
    }

    private fun decodeArgs(method: KFunction<*>, args: List<JsonElement>): List<Any?> {
        val valueParams = method.parameters.drop(1)
        require(valueParams.size == args.size) {
            "Function-ref API: ${method.name} expects ${valueParams.size} arg(s) but the payload " +
                "carries ${args.size}. Re-enqueue after the signature change."
        }
        return valueParams.mapIndexed { idx, param ->
            val el = args[idx]
            if (el is JsonNull) {
                require(param.type.isMarkedNullable) {
                    "Function-ref API: ${method.name} arg[$idx] (${param.name}: ${param.type}) is not " +
                        "nullable, but payload carries JsonNull. This row was produced by an older " +
                        "version of the same method? Re-enqueue."
                }
                null
            } else {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    json.decodeFromJsonElement(serializer(param.type) as KSerializer<Any?>, el)
                }.getOrElse { cause ->
                    throw IllegalStateException(
                        "Function-ref API: ${method.name} arg[$idx] (${param.name}: ${param.type}) " +
                            "could not be decoded from payload element $el. Either the parameter " +
                            "type changed since enqueue or the @Serializable schema isn't compatible " +
                            "(see DESIGN.md 22.9 schema-evolution rules).",
                        cause,
                    )
                }
            }
        }
    }
}

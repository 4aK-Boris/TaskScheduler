package cs.trade.scheduler.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementContainer
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR transformer for `Scheduler.enqueueLambda { … }` lambda capture (DESIGN.md 21.9).
 *
 * **What it rewrites.** A call shaped like
 * ```
 * scheduler.enqueueLambda(opts) { mailer.send(123L, "welcome") }
 * ```
 * is lowered to
 * ```
 * scheduler.enqueueFunctionRefRaw(
 *     "com.example.Mailer",          // receiver's declared type FQN
 *     "send(kotlin.Long,kotlin.String)",  // FunctionRefEnqueuer.methodSignatureOf format
 *     listOf(123L, "welcome"),
 *     opts,                          // forwarded; omitted → defaults at the call site
 * )
 * ```
 *
 * **Why strings + listOf instead of a `KFunction` reference?** Synthesising a
 * reflection-capable `IrFunctionReference` in IR is both verbose and fragile across compiler
 * releases (the rich-vs-plain reference split landed in 2.2). Two `String` constants plus a
 * `listOf(...)` are trivial and stable to emit, and `Scheduler.enqueueFunctionRefRaw` reflects
 * the `KFunction` back out at enqueue time — producing the exact same `FunctionRefPayload` as
 * the explicit `enqueue(Recv::method, …)` API. The user-visible result is identical.
 *
 * **Supported lambda shape (enforced; anything else is a compile ERROR):**
 *  - Single expression body that is one call: `{ receiver.method(args...) }`.
 *  - `receiver` is an instance (member) call — no top-level / extension / context-receiver
 *    functions (their reflective parameter layout doesn't match the worker's resolver).
 *  - The method is non-generic and its value-parameter types are concrete (no type variables).
 *  - At most 5 arguments (the function-ref ceiling; beyond that, use a sealed-class `Job`).
 */
public class SchedulerIrGenerationExtension(
    private val configuration: CompilerConfiguration? = null,
) : IrGenerationExtension {

    private fun messageCollector(): MessageCollector? =
        configuration?.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val collector = messageCollector()
        val transformer = EnqueueLambdaTransformer(pluginContext, collector)
        moduleFragment.transform(transformer, null)
        if (transformer.rewritten > 0) {
            collector?.report(
                CompilerMessageSeverity.LOGGING,
                "scheduler-compiler-plugin: rewrote ${transformer.rewritten} enqueueLambda call-site(s) " +
                    "into enqueueFunctionRefRaw in ${moduleFragment.name}",
            )
        }
    }

    // referenceFunctions(CallableId) is soft-deprecated in 2.3 in favour of the finder API,
    // but it's the portable lookup that still works for both builtins and source symbols.
    // UnsafeDuringIrConstructionAPI guards .owner access — safe here because we only read
    // symbols of already-bound declarations (the Scheduler interface, kotlin.collections.listOf).
    @Suppress("DEPRECATION")
    @OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
    private class EnqueueLambdaTransformer(
        private val ctx: IrPluginContext,
        private val collector: MessageCollector?,
    ) : IrElementTransformerVoidWithContext() {

        var rewritten: Int = 0

        // --- Lazily-resolved symbols / types from the runtime ---------------------------

        private val schedulerClassId = ClassId(SCHEDULER_PACKAGE, Name.identifier("Scheduler"))

        private val enqueueFunctionRefRawSymbol: IrSimpleFunctionSymbol? by lazy {
            ctx.referenceFunctions(CallableId(schedulerClassId, Name.identifier("enqueueFunctionRefRaw")))
                .singleOrNull()
        }

        private val listOfSymbol: IrSimpleFunctionSymbol? by lazy {
            ctx.referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf")))
                .singleOrNull { sym ->
                    val params = sym.owner.parameters
                    params.size == 1 && params[0].varargElementType != null
                }
        }

        private val anyNType: IrType get() = ctx.irBuiltIns.anyNType
        private val stringType: IrType get() = ctx.irBuiltIns.stringType
        private val listOfAnyNType: IrType get() = ctx.irBuiltIns.listClass.typeWith(anyNType)
        private val arrayOfAnyNType: IrType get() = ctx.irBuiltIns.arrayClass.typeWith(anyNType)

        override fun visitCall(expression: IrCall): IrExpression {
            val owner = expression.symbol.owner
            // A concrete implementor (RecordingScheduler, DefaultScheduler …) carries a fake
            // override of the interface's default `enqueueLambda`; the call's symbol points at
            // THAT, whose fqn is `<Impl>.enqueueLambda`. Resolve through fake overrides so we
            // match the call regardless of the static receiver type.
            if (owner.name.asString() != "enqueueLambda" || owner.realFqn() != ENQUEUE_LAMBDA_FQN) {
                return super.visitCall(expression)
            }
            val replacement = rewrite(expression)
            return replacement ?: super.visitCall(expression)
        }

        private fun IrSimpleFunction.realFqn(): String =
            (if (isFakeOverride) (resolveFakeOverride() ?: this) else this).kotlinFqName.asString()

        /** Returns the replacement call, or null (leaving the original) after reporting an error. */
        private fun rewrite(call: IrCall): IrExpression? {
            val efrr = enqueueFunctionRefRawSymbol ?: run {
                error(call, "internal: Scheduler.enqueueFunctionRefRaw not found on the classpath")
                return null
            }
            val listOf = listOfSymbol ?: run {
                error(call, "internal: kotlin.collections.listOf(vararg) not found")
                return null
            }

            val callee = call.symbol.owner
            val blockParam = callee.parameters.firstOrNull { it.name.asString() == "block" }
                ?: run { error(call, "internal: enqueueLambda has no 'block' parameter"); return null }
            val optionsParam = callee.parameters.firstOrNull { it.name.asString() == "options" }
            val calleeDispatch = callee.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver }
                ?: run { error(call, "internal: enqueueLambda has no dispatch receiver"); return null }

            val lambda = call.arguments[blockParam] as? IrFunctionExpression ?: run {
                error(call, "enqueueLambda's argument must be a lambda literal `{ receiver.method(args) }`")
                return null
            }

            val userCall = singleCallIn(lambda) ?: run {
                error(
                    call,
                    "enqueueLambda body must be a single method call on an injected receiver, e.g. " +
                        "`{ mailer.send(id) }`. Conditionals, locals or multiple statements aren't supported — " +
                        "use a sealed-class Job or the explicit enqueue(Recv::method, …) form.",
                )
                return null
            }

            val target = userCall.symbol.owner
            if (target.typeParameters.isNotEmpty()) {
                error(call, "enqueueLambda can't capture a generic method (${target.name}); use a sealed-class Job.")
                return null
            }

            val nonRegular = target.parameters.filter { it.kind != IrParameterKind.Regular }
            val dispatchParam = nonRegular.singleOrNull()?.takeIf { it.kind == IrParameterKind.DispatchReceiver }
            if (dispatchParam == null) {
                error(
                    call,
                    "enqueueLambda only supports a plain member call (receiver.method(...)). Top-level, " +
                        "extension and context-receiver functions aren't supported — use enqueue(Recv::method, …).",
                )
                return null
            }

            val regulars = target.parameters.filter { it.kind == IrParameterKind.Regular }
            if (regulars.size > MAX_ARGS) {
                error(call, "enqueueLambda supports at most $MAX_ARGS arguments; ${target.name} has ${regulars.size}. Use a sealed-class Job.")
                return null
            }

            val receiverExpr = userCall.arguments[dispatchParam] ?: run {
                error(call, "enqueueLambda: could not read the call receiver"); return null
            }
            val receiverFqn = receiverExpr.type.classFqName?.asString() ?: run {
                error(call, "enqueueLambda: receiver type is anonymous/local and can't be resolved at runtime.")
                return null
            }

            val paramFqns = regulars.map { p ->
                p.type.classFqName?.asString() ?: run {
                    error(call, "enqueueLambda: parameter '${p.name}' has a non-concrete type and can't be captured.")
                    return null
                }
            }
            val signature = "${target.name.asString()}(${paramFqns.joinToString(",")})"

            // Args, transformed first so a nested enqueueLambda (rare) is handled too.
            val argExprs = regulars.map { p ->
                val arg = userCall.arguments[p] ?: run {
                    error(call, "enqueueLambda: missing argument for '${p.name}'"); return null
                }
                arg.transform(this, null)
            }

            val so = call.startOffset
            val eo = call.endOffset

            // listOf<Any?>(args...)
            val listCall = IrCallImpl(
                so, eo, listOfAnyNType, listOf,
                typeArgumentsCount = 1, origin = null, superQualifierSymbol = null,
            )
            listCall.typeArguments[0] = anyNType
            val varargParam = listOf.owner.parameters.first { it.varargElementType != null }
            listCall.arguments[varargParam] = IrVarargImpl(so, eo, arrayOfAnyNType, anyNType, argExprs)

            // scheduler.enqueueFunctionRefRaw(targetType, methodSignature, args, options?)
            val newCall = IrCallImpl(
                call.startOffset, call.endOffset, call.type, efrr,
                typeArgumentsCount = 0, origin = null, superQualifierSymbol = null,
            )
            val rawParams = efrr.owner.parameters
            newCall.arguments[rawParams.first { it.kind == IrParameterKind.DispatchReceiver }] =
                requireNotNull(call.arguments[calleeDispatch]) { "enqueueLambda call has no receiver" }
                    .transform(this, null)
            newCall.arguments[rawParam(rawParams, "targetType")] = IrConstImpl.string(so, eo, stringType, receiverFqn)
            newCall.arguments[rawParam(rawParams, "methodSignature")] = IrConstImpl.string(so, eo, stringType, signature)
            newCall.arguments[rawParam(rawParams, "args")] = listCall
            if (optionsParam != null) {
                val optionsArg = call.arguments[optionsParam]
                if (optionsArg != null) {
                    newCall.arguments[rawParam(rawParams, "options")] = optionsArg.transform(this, null)
                }
                // optionsArg == null → user omitted it; leave the slot null so the
                // enqueueFunctionRefRaw default (EnqueueOptions()) applies after lowering.
            }

            rewritten++
            return newCall
        }

        private fun rawParam(params: List<IrValueParameter>, name: String): IrValueParameter =
            params.first { it.name.asString() == name }

        /** Unwrap a single-statement lambda body down to its lone [IrCall], or null. */
        private fun singleCallIn(lambda: IrFunctionExpression): IrCall? {
            val statements = (lambda.function.body as? IrStatementContainer)?.statements ?: return null
            if (statements.size != 1) return null
            var cur: Any? = statements[0]
            while (true) {
                cur = when (cur) {
                    is IrCall -> return cur
                    is IrReturn -> cur.value
                    is IrTypeOperatorCall -> cur.argument
                    else -> return null
                }
            }
        }

        private fun error(at: IrCall, message: String) {
            collector?.report(
                CompilerMessageSeverity.ERROR,
                "scheduler-compiler-plugin: $message",
                location(at.startOffset),
            )
        }

        private fun location(offset: Int): CompilerMessageLocation? {
            val file = currentFile
            val entry = file.fileEntry
            return CompilerMessageLocation.create(
                entry.name,
                entry.getLineNumber(offset) + 1,
                entry.getColumnNumber(offset) + 1,
                null,
            )
        }
    }

    private companion object {
        const val ENQUEUE_LAMBDA_FQN: String = "cs.trade.scheduler.core.backend.Scheduler.enqueueLambda"
        val SCHEDULER_PACKAGE: FqName = FqName("cs.trade.scheduler.core.backend")
        const val MAX_ARGS: Int = 5
    }
}

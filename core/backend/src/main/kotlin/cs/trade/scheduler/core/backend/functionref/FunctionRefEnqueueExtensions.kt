@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.core.backend.functionref

import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.Scheduler
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4
import kotlin.reflect.KFunction5
import kotlin.reflect.KFunction6
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2
import kotlin.reflect.KSuspendFunction3
import kotlin.reflect.KSuspendFunction4
import kotlin.reflect.KSuspendFunction5
import kotlin.reflect.KSuspendFunction6
import kotlin.uuid.Uuid

/**
 * Typed overloads for the function-ref API (DESIGN.md 21.2). Each shape funnels into the
 * single [Scheduler.enqueueFunctionRef] primitive after type-checking the args. The
 * primitive owns serialisation (so the configured `SchedulerCoreConfig.json` stays a
 * private detail of the impl module, and Koin's compile-time validator doesn't see a
 * `koin.get<SchedulerCoreConfig>()` call propagated up through extension defaults).
 *
 * 0..5-arg ceiling matches the design — for >5 args, fall back to a sealed-class `Job`
 * (which scales linearly with field count and stays type-safe).
 *
 * **Suspend variants** exist as separate overloads because `KSuspendFunctionN` is a
 * distinct sealed family from `KFunctionN` (not a subtype). JVM-level `@JvmName` keeps
 * them from clashing in bytecode (generic erasure collapses both to `KFunction`).
 */

public suspend fun <T : Any> Scheduler.enqueue(
    method: KFunction1<T, *>,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = emptyList(), options)

public suspend fun <T : Any, A1> Scheduler.enqueue(
    method: KFunction2<T, A1, *>,
    a1: A1,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1), options)

public suspend fun <T : Any, A1, A2> Scheduler.enqueue(
    method: KFunction3<T, A1, A2, *>,
    a1: A1,
    a2: A2,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2), options)

public suspend fun <T : Any, A1, A2, A3> Scheduler.enqueue(
    method: KFunction4<T, A1, A2, A3, *>,
    a1: A1,
    a2: A2,
    a3: A3,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2, a3), options)

public suspend fun <T : Any, A1, A2, A3, A4> Scheduler.enqueue(
    method: KFunction5<T, A1, A2, A3, A4, *>,
    a1: A1,
    a2: A2,
    a3: A3,
    a4: A4,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2, a3, a4), options)

public suspend fun <T : Any, A1, A2, A3, A4, A5> Scheduler.enqueue(
    method: KFunction6<T, A1, A2, A3, A4, A5, *>,
    a1: A1,
    a2: A2,
    a3: A3,
    a4: A4,
    a5: A5,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2, a3, a4, a5), options)

// ---- Suspend variants ---------------------------------------------------------------

@JvmName("enqueueSuspend1")
public suspend fun <T : Any> Scheduler.enqueue(
    method: KSuspendFunction1<T, *>,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = emptyList(), options)

@JvmName("enqueueSuspend2")
public suspend fun <T : Any, A1> Scheduler.enqueue(
    method: KSuspendFunction2<T, A1, *>,
    a1: A1,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1), options)

@JvmName("enqueueSuspend3")
public suspend fun <T : Any, A1, A2> Scheduler.enqueue(
    method: KSuspendFunction3<T, A1, A2, *>,
    a1: A1,
    a2: A2,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2), options)

@JvmName("enqueueSuspend4")
public suspend fun <T : Any, A1, A2, A3> Scheduler.enqueue(
    method: KSuspendFunction4<T, A1, A2, A3, *>,
    a1: A1,
    a2: A2,
    a3: A3,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2, a3), options)

@JvmName("enqueueSuspend5")
public suspend fun <T : Any, A1, A2, A3, A4> Scheduler.enqueue(
    method: KSuspendFunction5<T, A1, A2, A3, A4, *>,
    a1: A1,
    a2: A2,
    a3: A3,
    a4: A4,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2, a3, a4), options)

@JvmName("enqueueSuspend6")
public suspend fun <T : Any, A1, A2, A3, A4, A5> Scheduler.enqueue(
    method: KSuspendFunction6<T, A1, A2, A3, A4, A5, *>,
    a1: A1,
    a2: A2,
    a3: A3,
    a4: A4,
    a5: A5,
    options: EnqueueOptions = EnqueueOptions(),
): Uuid = enqueueFunctionRef(method, args = listOf(a1, a2, a3, a4, a5), options)

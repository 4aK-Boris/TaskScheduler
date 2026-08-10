@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cs.trade.scheduler.demo

import cs.trade.scheduler.core.backend.EnqueueOptions
import cs.trade.scheduler.core.backend.RecurringDefinition
import cs.trade.scheduler.core.backend.Scheduler
import cs.trade.scheduler.core.backend.functionref.FunctionRefEnqueuer
import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.shared.CancelResult
import cs.trade.scheduler.shared.ConcurrencyPolicy
import cs.trade.scheduler.shared.DeleteResult
import cs.trade.scheduler.shared.RerouteResult
import cs.trade.scheduler.shared.RetryMode
import cs.trade.scheduler.shared.RetryResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The receiver the captured lambda calls. Top-level so its `qualifiedName`
 * (`cs.trade.scheduler.demo.LambdaCaptureMailer`) is `Class.forName`-loadable — the same
 * constraint the explicit function-ref API documents (see `FunctionRefMailer`).
 */
class LambdaCaptureMailer {
    @Suppress("unused")
    fun send(userId: Long, template: String) {
        // Never invoked here — RecordingScheduler only records the lowered call's arguments.
    }
}

/**
 * Stage-2 of the function-ref lambda-capture compiler plugin (DESIGN.md 21.9).
 *
 * This test compilation has `:scheduler-compiler-plugin` on its kotlinc plugin classpath
 * (see app/build.gradle.kts), so every `scheduler.enqueueLambda { recv.method(args) }`
 * below is rewritten at compile time into `scheduler.enqueueFunctionRefRaw(type, sig, args, opts)`.
 * We capture what the rewritten call passes and assert the plugin produced the right
 * receiver FQN, method signature and argument list — then prove that feeding those exact
 * strings to the runtime yields the SAME payload as the hand-written `enqueue(Recv::method, …)`.
 *
 * If the plugin were NOT applied, `enqueueLambda`'s default body throws `IllegalStateException`
 * — so a green test is itself proof the rewrite happened.
 */
class SchedulerLambdaCaptureTest {

    @Test
    fun `enqueueLambda is rewritten to enqueueFunctionRefRaw with the right type, signature and args`() = runBlocking {
        val scheduler = RecordingScheduler()
        val mailer = LambdaCaptureMailer()

        scheduler.enqueueLambda { mailer.send(7L, "welcome") }

        assertEquals("cs.trade.scheduler.demo.LambdaCaptureMailer", scheduler.lastTargetType)
        assertEquals("send(kotlin.Long,kotlin.String)", scheduler.lastMethodSignature)
        assertEquals(listOf(7L, "welcome"), scheduler.lastArgs)

        // The decisive cross-check: the strings the plugin emitted, run through the real
        // runtime resolver, must reconstruct the identical wire payload as the explicit
        // reference form. This ties the compile-time rewrite to the function-ref pipeline.
        val viaRaw = FunctionRefEnqueuer.buildFromTarget(
            scheduler.lastTargetType!!, scheduler.lastMethodSignature!!, scheduler.lastArgs!!, null, Json,
        )
        val viaRef = FunctionRefEnqueuer.build(
            LambdaCaptureMailer::send, listOf(7L, "welcome"), null, Json,
        )
        assertEquals(viaRef.payload, viaRaw.payload)
    }

    @Test
    fun `enqueueLambda forwards explicit EnqueueOptions through the rewrite`() = runBlocking {
        val scheduler = RecordingScheduler()
        val mailer = LambdaCaptureMailer()

        scheduler.enqueueLambda(EnqueueOptions(queue = "emails")) { mailer.send(9L, "digest") }

        assertEquals("emails", scheduler.lastOptions?.queue, "options must be forwarded to enqueueFunctionRefRaw")
        assertEquals(listOf(9L, "digest"), scheduler.lastArgs)
    }
}

/**
 * Minimal [Scheduler] that records what the lowered `enqueueFunctionRefRaw` call receives.
 * Every other member is unused by these tests.
 */
private class RecordingScheduler : Scheduler {
    var lastTargetType: String? = null
    var lastMethodSignature: String? = null
    var lastArgs: List<Any?>? = null
    var lastOptions: EnqueueOptions? = null

    override suspend fun enqueueFunctionRefRaw(
        targetType: String,
        methodSignature: String,
        args: List<Any?>,
        options: EnqueueOptions,
    ): Uuid {
        lastTargetType = targetType
        lastMethodSignature = methodSignature
        lastArgs = args
        lastOptions = options
        return Uuid.random()
    }

    override suspend fun start() = Unit
    override suspend fun stop(timeout: Duration) = Unit
    override suspend fun enqueue(job: Job, options: EnqueueOptions): Uuid = unused()
    override suspend fun scheduleAt(job: Job, at: Instant, options: EnqueueOptions): Uuid = unused()
    override suspend fun enqueueOnce(
        key: String,
        job: Job,
        options: EnqueueOptions,
        policy: ConcurrencyPolicy,
    ): Uuid = unused()
    override suspend fun chain(vararg jobs: Job, priority: Int?): List<Uuid> = unused()
    override suspend fun enqueueAfter(job: Job, waitFor: List<Uuid>, options: EnqueueOptions): Uuid = unused()
    override suspend fun recurring(definition: RecurringDefinition) = unused()
    override suspend fun cancel(jobId: Uuid, by: String?): CancelResult = unused()
    override suspend fun retry(jobId: Uuid, by: String?, mode: RetryMode): RetryResult = unused()
    override suspend fun delete(jobId: Uuid, by: String?): DeleteResult = unused()
    override suspend fun reroute(jobId: Uuid, targetNode: String?, targetTag: String?, by: String?): RerouteResult = unused()
    override suspend fun triggerRecurringNow(id: String): Uuid? = unused()
    override suspend fun rerun(sourceJobId: Uuid): Uuid? = unused()
    override suspend fun enqueueFunctionRef(method: KFunction<*>, args: List<Any?>, options: EnqueueOptions): Uuid = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by SchedulerLambdaCaptureTest")
}

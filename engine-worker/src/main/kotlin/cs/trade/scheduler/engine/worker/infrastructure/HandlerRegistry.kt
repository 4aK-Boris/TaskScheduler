package cs.trade.scheduler.engine.worker.infrastructure

import cs.trade.scheduler.core.backend.handler.Job
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.JobType

/**
 * `payload_type` (fully-qualified Job class name as written by [DefaultScheduler.enqueue]) →
 * the [JobHandler] that knows how to execute it. Built once at construction from all
 * `JobHandler<*>` beans Koin discovered (via `getAll<JobHandler<*>>()`).
 *
 * Resolution rule: each handler **must** carry a `@JobType(SomeJob::class)` annotation —
 * `JobHandler<T>`'s generic param is erased at runtime, so the annotation is the only way
 * to link bytes-on-the-wire to the right handler. See DESIGN.md section 12.3.
 *
 * Misses are returned as `null` so the worker can route the message to the DLQ rather
 * than crashing the consumer (DESIGN.md 11.4).
 */
public class HandlerRegistry(handlers: List<JobHandler<*>>) {

    private val byPayloadType: Map<String, JobHandler<*>>

    init {
        val map = mutableMapOf<String, JobHandler<*>>()
        for (handler in handlers) {
            val jobType = handler::class.java.getAnnotation(JobType::class.java)
                ?: error(
                    "JobHandler ${handler::class.qualifiedName} is missing @JobType — " +
                        "annotate it with @JobType(SomeJob::class) so the worker can dispatch to it."
                )
            val payloadType = jobType.value.qualifiedName
                ?: error("@JobType value on ${handler::class.qualifiedName} has no qualifiedName")
            val previous = map.put(payloadType, handler)
            require(previous == null) {
                "Two handlers registered for payload type $payloadType: " +
                    "${previous!!::class.qualifiedName} and ${handler::class.qualifiedName}"
            }
        }
        byPayloadType = map.toMap()
    }

    public fun find(payloadType: String): JobHandler<*>? = byPayloadType[payloadType]

    /** Job classes this registry can dispatch — used for startup sanity logging. */
    public val knownPayloadTypes: Set<String> get() = byPayloadType.keys

    /** Shortcut for tests / pause checks: does ANY handler claim this payload type? */
    public operator fun contains(payloadType: String): Boolean = byPayloadType.containsKey(payloadType)

    @Suppress("UNCHECKED_CAST")
    internal fun findCasted(payloadType: String): JobHandler<Job>? =
        find(payloadType) as JobHandler<Job>?
}

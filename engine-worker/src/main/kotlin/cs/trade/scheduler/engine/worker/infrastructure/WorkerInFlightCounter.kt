package cs.trade.scheduler.engine.worker.infrastructure

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Tracks "between pickup and finalize" per queue. WorkerPool calls increment(queue)
// right after a successful pickup and decrement(queue) in a finally block — so the
// per-queue count is correct regardless of handler outcome (success / cancel / retry
// / fail).
//
// Lives outside WorkerPool so WorkerRegistryLoop can read it without taking a direct
// dependency on WorkerPool (which would create a circular Koin graph).
//
// `total()` keeps the legacy single-number consumer happy (worker.in_flight_count column,
// dashboard's "Show 3" line). `byQueue()` is the new map used for the per-queue gauges
// and the breakdown chips on the Workers screen.
public class WorkerInFlightCounter {
    // ConcurrentHashMap because consumers run on Rabbit's internal threads; AtomicInteger
    // inside because we increment/decrement without holding a map-level lock. Map entries
    // never get removed — queue count returns to zero but the key stays so `byQueue()` is
    // stable order across heartbeats.
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    public fun increment(queue: String): Int = counter(queue).incrementAndGet()

    public fun decrement(queue: String): Int = counter(queue).decrementAndGet()

    public fun current(queue: String): Int = counts[queue]?.get() ?: 0

    /** Sum across queues — backwards-compatible with `worker.in_flight_count` legacy column. */
    public fun total(): Int = counts.values.sumOf { it.get() }

    /** Snapshot copy — safe to hand to a heartbeat write or a metric scrape. */
    public fun byQueue(): Map<String, Int> = counts.mapValues { it.value.get() }

    private fun counter(queue: String): AtomicInteger =
        counts.computeIfAbsent(queue) { AtomicInteger(0) }
}

package cs.trade.scheduler.runner

import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.OutboxRepository
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Custom Prometheus gauges driven by a background poller. We don't compute them at
// scrape time — Prometheus may scrape multiple replicas concurrently, and we don't want
// each scrape to round-trip to Postgres. Instead the poller updates AtomicLongs that
// gauges read directly (cheap pointer read on scrape).
//
// Exposed series:
//   scheduler_jobs_by_state{state="ENQUEUED|PROCESSING|...|CANCELLED"}  (gauge)
//   scheduler_outbox_unpublished                                         (gauge)
//   scheduler_outbox_lag_seconds                                         (gauge)
//   scheduler_workers_total                                              (gauge)
//   scheduler_workers_alive                                              (gauge)
//
// Polling interval defaults to 15s — frequent enough for ops dashboards, infrequent
// enough that the 10 SELECTs we issue per tick (8 state counts + 2 outbox queries +
// workers findAll) don't load the database. Tune via [intervalSeconds].
public class SchedulerMetricsBinder(
    registry: MeterRegistry,
    private val jobs: JobRepository,
    private val outbox: OutboxRepository,
    private val workers: WorkerRepository,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val byState: Map<JobState, AtomicLong> = JobState.entries.associateWith { state ->
        val ref = AtomicLong(0)
        Gauge.builder("scheduler.jobs.by_state") { ref.get().toDouble() }
            .description("Job count per lifecycle state")
            .tag("state", state.name)
            .register(registry)
        ref
    }
    private val outboxUnpublished = AtomicLong(0).also { ref ->
        Gauge.builder("scheduler.outbox.unpublished") { ref.get().toDouble() }
            .description("Outbox rows pending Rabbit publish")
            .register(registry)
    }
    private val outboxLagSeconds = AtomicLong(0).also { ref ->
        Gauge.builder("scheduler.outbox.lag_seconds") { ref.get().toDouble() }
            .description("Age (seconds) of the oldest unpublished outbox row; 0 if none")
            .register(registry)
    }
    private val workersTotal = AtomicLong(0).also { ref ->
        Gauge.builder("scheduler.workers.total") { ref.get().toDouble() }
            .description("Worker rows in registry (alive + dead-but-not-yet-collected)")
            .register(registry)
    }
    private val workersAlive = AtomicLong(0).also { ref ->
        Gauge.builder("scheduler.workers.alive") { ref.get().toDouble() }
            .description("Workers whose last heartbeat is within ${ALIVE_THRESHOLD_MINUTES}min")
            .register(registry)
    }

    public fun start(
        scope: CoroutineScope,
        isLeader: () -> Boolean = { true },
    ): Job = scope.launch {
        log.info("SchedulerMetricsBinder started — poll interval={}s", intervalSeconds)
        while (isActive) {
            if (isLeader()) {
                runCatching { pollOnce() }
                    .onFailure { log.warn("Metrics poll failed — keeping previous values until next tick", it) }
            }
            delay(intervalSeconds.seconds.inWholeMilliseconds)
        }
    }

    private suspend fun pollOnce() {
        val states = jobs.countByState()
        byState.forEach { (state, ref) ->
            ref.set(states.getOrDefault(state, 0L))
        }

        val unpublished = outbox.countUnpublished()
        outboxUnpublished.set(unpublished)

        val lag = outbox.findOldestUnpublishedCreatedAt()?.let { oldest ->
            (Clock.System.now() - oldest).inWholeSeconds.coerceAtLeast(0)
        } ?: 0L
        outboxLagSeconds.set(lag)

        val workerRows = workers.findAll(limit = WORKERS_POLL_CAP)
        val now = Clock.System.now()
        val aliveCount = workerRows.count { (now - it.lastHeartbeat) < ALIVE_THRESHOLD }
        workersTotal.set(workerRows.size.toLong())
        workersAlive.set(aliveCount.toLong())
    }

    private companion object {
        const val DEFAULT_INTERVAL_SECONDS: Long = 15L
        const val ALIVE_THRESHOLD_MINUTES: Long = 1L
        const val WORKERS_POLL_CAP: Int = 500
        val ALIVE_THRESHOLD: kotlin.time.Duration = ALIVE_THRESHOLD_MINUTES.minutes
    }
}

package cs.trade.scheduler.engine.worker.infrastructure.loops

import cs.trade.scheduler.engine.worker.infrastructure.SchedulerWorkerConfig
import cs.trade.scheduler.engine.worker.infrastructure.WorkerInFlightCounter
import cs.trade.scheduler.storage.postgres.domain.models.WorkerRow
import cs.trade.scheduler.storage.postgres.domain.repositories.WorkerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Instant

// Periodic upsert into the `worker` table — gives the dashboard's Workers screen a
// live registry of running nodes. Tick interval = SchedulerWorkerConfig.heartbeatInterval
// (same cadence as the job lock heartbeat — both are about telling the cluster "I'm
// alive", just for different state).
//
// startedAt is captured once at loop start; the upsert preserves it on conflict so
// the dashboard can show node uptime correctly.
//
// in_flight_count + in_flight_by_queue come from [WorkerInFlightCounter], a per-queue
// map of AtomicIntegers that WorkerPool increments on successful pickup and decrements
// in a finally block. The row reflects the snapshot at the moment of upsert — slightly
// stale between ticks, but fine for an ops dashboard.
public class WorkerRegistryLoop(
    private val workers: WorkerRepository,
    private val inFlight: WorkerInFlightCounter,
    private val config: SchedulerWorkerConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(scope: CoroutineScope): Job = scope.launch {
        val startedAt: Instant = Clock.System.now()
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
        val intervalMs = config.heartbeatInterval.inWholeMilliseconds
        log.info(
            "WorkerRegistryLoop started — node={}, host={}, tags={}, interval={}ms",
            config.nodeId, host, config.nodeTags, intervalMs,
        )

        while (isActive) {
            try {
                val byQueue = inFlight.byQueue()
                workers.upsertHeartbeat(
                    WorkerRow(
                        nodeId = config.nodeId,
                        host = host,
                        tags = config.nodeTags,
                        startedAt = startedAt,
                        lastHeartbeat = Clock.System.now(),
                        inFlightCount = byQueue.values.sum(),
                        inFlightByQueue = byQueue,
                    ),
                )
            } catch (t: Throwable) {
                log.warn("WorkerRegistry heartbeat failed — will retry next interval", t)
            }
            delay(intervalMs)
        }
    }
}

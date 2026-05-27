package cs.trade.scheduler.engine.worker.infrastructure.loops

import cs.trade.scheduler.engine.worker.infrastructure.SchedulerWorkerConfig
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Periodic lock-extension for jobs this worker currently holds. Every
 * [SchedulerWorkerConfig.heartbeatInterval] the loop does one bulk UPDATE that bumps
 * `locked_until = now + lockDuration` for every PROCESSING row whose `locked_by` matches
 * this node.
 *
 * Without this, the SafetyNetPoller (DESIGN.md 13.5) would eventually re-publish jobs
 * whose execution outlasts a single `lockDuration` — leading to double-execution.
 *
 * Sizing rule (DESIGN.md 13.4): `heartbeatInterval ≤ lockDuration / 3` so two missed
 * heartbeats (GC pause, scheduler starvation) still leave the lock valid. Defaults are
 * 30s and 90s respectively.
 *
 * Errors in `extendLocks` are logged and swallowed — a one-off DB blip shouldn't kill
 * the heartbeat loop, the next tick recovers.
 */
public class HeartbeatLoop(
    private val jobs: JobRepository,
    private val config: SchedulerWorkerConfig,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(scope: CoroutineScope): Job = scope.launch {
        val intervalMs = config.heartbeatInterval.inWholeMilliseconds
        val lockMs = config.lockDuration.inWholeMilliseconds
        log.info(
            "HeartbeatLoop started — node={}, interval={}ms, lockDuration={}ms",
            config.nodeId, intervalMs, lockMs,
        )
        while (isActive) {
            try {
                val newUntilMs = Clock.System.now().toEpochMilliseconds() + lockMs
                val updated = jobs.extendLocks(config.nodeId, newUntilMs)
                if (updated > 0) {
                    log.debug("Heartbeat extended {} lock(s)", updated)
                }
            } catch (t: Throwable) {
                log.warn("Heartbeat tick failed — will retry next interval", t)
            }
            delay(intervalMs)
        }
    }
}

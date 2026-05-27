package cs.trade.scheduler.engine.worker.domain.usecases

import cs.trade.scheduler.core.backend.usecases.BaseUseCase
import cs.trade.scheduler.core.backend.usecases.runCatchingWithLogging
import cs.trade.scheduler.storage.postgres.domain.models.Job
import cs.trade.scheduler.storage.postgres.domain.repositories.JobRepository
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Atomic transition of a job from ENQUEUED → PROCESSING with a fresh lock.
 * Returns the locked Job or null if another worker grabbed it first (race).
 *
 * Skeleton — actual SQL goes in the JobRepository impl. See DESIGN.md section 7.2.
 */
@OptIn(ExperimentalUuidApi::class)
@Single
public class PickupJobUseCase(
    private val jobs: JobRepository,
) : BaseUseCase() {

    public suspend operator fun invoke(jobId: Uuid, nodeId: String, lockDurationMillis: Long): Result<Job?> =
        runCatchingWithLogging {
            // TODO: replace with jobs.pickup(jobId, nodeId, lockDurationMillis)
            jobs.findById(jobId)
        }
}

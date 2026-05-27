package cs.trade.scheduler.demo

import cs.trade.scheduler.core.backend.handler.JobContext
import cs.trade.scheduler.core.backend.handler.JobHandler
import cs.trade.scheduler.core.backend.handler.JobType
import org.koin.core.annotation.Single
import org.slf4j.LoggerFactory

/**
 * Sample handler — demo user code. Two annotations:
 *  - `@Single(binds = [JobHandler::class])` makes Koin register us behind the `JobHandler` super.
 *  - `@JobType(SendEmail::class)` tells the scheduler which payload class we handle.
 *
 * See DESIGN.md section 12.3.
 */
@Single(binds = [JobHandler::class])
@JobType(SendEmail::class)
public class SendEmailHandler : JobHandler<SendEmail> {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun execute(ctx: JobContext, job: SendEmail) {
        log.info(
            "[demo] sending email userId={} template={} attempt={}/{}",
            job.userId, job.template, ctx.attempt, ctx.maxAttempts,
        )
        // Real code would call a mailer here.
    }
}

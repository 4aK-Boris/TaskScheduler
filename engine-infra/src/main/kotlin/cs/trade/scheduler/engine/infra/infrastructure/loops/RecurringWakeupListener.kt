package cs.trade.scheduler.engine.infra.infrastructure.loops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.DriverManager

/**
 * Commit-time wakeup for continuous recurring definitions, independent of worker language.
 * A dedicated connection avoids occupying a pooled transaction slot. Missed notifications
 * are harmless: [RecurringScheduler] also polls and always checks durable database state.
 */
public class RecurringWakeupListener(
    private val jdbcUrl: String,
    private val jdbcUser: String,
    private val jdbcPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun start(scope: CoroutineScope, onSignal: () -> Unit): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { it.execute("LISTEN recurring_ready") }
                    val pg = connection.unwrap(PGConnection::class.java)
                    // Also scan after reconnect: a completion may have happened while disconnected.
                    onSignal()
                    while (currentCoroutineContext().isActive) {
                        if (!pg.getNotifications(1_000).isNullOrEmpty()) onSignal()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("Recurring wakeup listener disconnected; polling remains active", failure)
                delay(5_000)
            }
        }
    }
}

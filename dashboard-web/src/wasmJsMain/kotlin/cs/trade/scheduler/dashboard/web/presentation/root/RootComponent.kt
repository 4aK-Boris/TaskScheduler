package cs.trade.scheduler.dashboard.web.presentation.root

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import cs.trade.scheduler.dashboard.web.data.connection.ConnectionStatus
import cs.trade.scheduler.dashboard.web.presentation.screens.jobdetail.JobDetailComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.joblist.JobListComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.recurring.RecurringListComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.stats.StatsComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.types.TypesComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.typesstats.TypeStatsComponent
import cs.trade.scheduler.dashboard.web.presentation.screens.workers.WorkersComponent
import kotlinx.serialization.Serializable

// Top-level navigation host. Holds the ChildStack of screen components and the
// back-stack API. See DESIGN.md 3.4 for the Decompose convention.
public interface RootComponent {

    public val stack: Value<ChildStack<Config, Child>>
    public val connection: Value<ConnectionStatus>

    /** Persisted to localStorage (`dashboard.dark`); default = OS preference at first load. */
    public val isDarkTheme: Value<Boolean>

    public fun onBackClicked()
    public fun onNavigateToJobs()
    public fun onNavigateToRecurring()
    public fun onNavigateToStats()
    public fun onNavigateToWorkers()
    public fun onNavigateToTypes()
    public fun onNavigateToTypeStats()
    public fun onToggleTheme()

    /** Push the job-detail screen for the given id. Called from nav search box. */
    public fun onJumpToJob(jobId: String)

    public sealed interface Child {
        public class JobList(public val component: JobListComponent) : Child
        public class JobDetail(public val component: JobDetailComponent) : Child
        public class RecurringList(public val component: RecurringListComponent) : Child
        public class Stats(public val component: StatsComponent) : Child
        public class Workers(public val component: WorkersComponent) : Child
        public class Types(public val component: TypesComponent) : Child
        public class TypeStats(public val component: TypeStatsComponent) : Child
    }

    @Serializable
    public sealed interface Config {
        @Serializable
        public data object JobList : Config

        @Serializable
        public data class JobDetail(public val jobId: String) : Config

        @Serializable
        public data object RecurringList : Config

        @Serializable
        public data object Stats : Config

        @Serializable
        public data object Workers : Config

        @Serializable
        public data object Types : Config

        @Serializable
        public data object TypeStats : Config
    }
}

package cs.trade.scheduler.dashboard.web.presentation.components

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.core.frontend.ui.Chip
import cs.trade.scheduler.dashboard.web.presentation.theme.JobStateColors
import cs.trade.scheduler.shared.JobState
import cs.trade.scheduler.shared.dto.QueueHealthDto
import cs.trade.scheduler.shared.dto.QueueHealthStatus
import react.FC
import react.Props

/** Colour-coded job-state badge, used in list rows and the detail header. */
external interface StateChipProps : Props {
    var state: JobState
}

public val StateChip: FC<StateChipProps> = FC { props ->
    Chip {
        container = JobStateColors.bg(props.state)
        content = JobStateColors.fg(props.state)
        +props.state.name
    }
}

/**
 * "PAUSED" warning pill — shown next to a payload type when the type sits in `job_type_pause`.
 * Uses the semantic warning palette so it reads as a warning in both light and dark.
 */
public val PausedBadge: FC<Props> = FC {
    Chip {
        container = SchedulerColors.warningContainer
        content = SchedulerColors.onWarningContainer
        +"PAUSED"
    }
}

/**
 * Per-queue backpressure pill rendered on the JobList header (DESIGN.md 20.10). An escalating
 * operational signal: amber = elevated, red = overloaded. NORMAL queues render nothing — no
 * visual noise when everything is fine.
 */
external interface QueueHealthBadgeProps : Props {
    var item: QueueHealthDto
}

public val QueueHealthBadge: FC<QueueHealthBadgeProps> = FC { props ->
    val item = props.item
    if (item.status != QueueHealthStatus.NORMAL) {
        Chip {
            when (item.status) {
                QueueHealthStatus.ELEVATED -> {
                    container = SchedulerColors.warningContainer
                    content = SchedulerColors.onWarningContainer
                }

                QueueHealthStatus.OVERLOADED -> {
                    container = SchedulerColors.errorContainer
                    content = SchedulerColors.onErrorContainer
                }

                QueueHealthStatus.NORMAL -> error("unreachable: NORMAL short-circuited above")
            }
            title = "Queue ${item.queue} is ${item.status.name.lowercase()} — ${item.depth} messages waiting"
            +"${item.queue} (${item.depth})"
        }
    }
}

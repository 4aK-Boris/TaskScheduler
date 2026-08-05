package cs.trade.scheduler.dashboard.web.presentation.theme

import cs.trade.scheduler.core.frontend.theme.SchedulerColors
import cs.trade.scheduler.shared.JobState
import web.cssom.Color

/**
 * Single source of truth for state → colour mapping in the dashboard. Chips, list rows and the
 * detail header all read from here so the visual language stays consistent.
 *
 * Every value is a theme token (`var(--sch-*)`), so dark mode swaps cleanly with nothing
 * hardcoded — including SUCCEEDED, which uses the semantic success palette Material has no role
 * for.
 */
public object JobStateColors {

    /** Background colour for a state chip / row accent. */
    public fun bg(state: JobState): Color = when (state) {
        JobState.AWAITING_DEPS -> SchedulerColors.surfaceVariant
        JobState.SCHEDULED -> SchedulerColors.tertiaryContainer
        JobState.ENQUEUED -> SchedulerColors.primaryContainer
        JobState.PROCESSING -> SchedulerColors.secondaryContainer
        JobState.AWAITING_RETRY -> SchedulerColors.errorContainer
        JobState.SUCCEEDED -> SchedulerColors.successContainer
        JobState.FAILED -> SchedulerColors.errorContainer
        JobState.CANCELLED -> SchedulerColors.surfaceVariant
    }

    /** Text colour readable against [bg]. */
    public fun fg(state: JobState): Color = when (state) {
        JobState.AWAITING_DEPS -> SchedulerColors.onSurfaceVariant
        JobState.SCHEDULED -> SchedulerColors.onTertiaryContainer
        JobState.ENQUEUED -> SchedulerColors.onPrimaryContainer
        JobState.PROCESSING -> SchedulerColors.onSecondaryContainer
        JobState.AWAITING_RETRY -> SchedulerColors.onErrorContainer
        JobState.SUCCEEDED -> SchedulerColors.onSuccessContainer
        JobState.FAILED -> SchedulerColors.onErrorContainer
        JobState.CANCELLED -> SchedulerColors.onSurfaceVariant
    }
}

package cs.trade.scheduler.shared

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Job priority in range 0..10 (RabbitMQ x-max-priority limit, see DESIGN.md section 19).
 */
@Serializable
@JvmInline
public value class JobPriority(public val value: Int) {
    init {
        // Literals, NOT MIN.value/MAX.value. The companion's MAX is itself a JobPriority,
        // and its `<clinit>` calls this very `init` block while MAX is still null —
        // the value class unboxes to a default Int of 0, the range collapses to 0..0,
        // and MAX = JobPriority(10) fails its own require. Hard-code the bounds.
        require(value in 0..10) {
            "JobPriority must be in 0..10, got $value"
        }
    }

    public companion object {
        public val MIN: JobPriority = JobPriority(0)
        public val MAX: JobPriority = JobPriority(10)
        public val DEFAULT: JobPriority = MIN
    }
}

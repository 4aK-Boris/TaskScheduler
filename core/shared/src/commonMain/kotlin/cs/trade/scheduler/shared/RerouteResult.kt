package cs.trade.scheduler.shared

import kotlinx.serialization.Serializable

/**
 * Outcome of [cs.trade.scheduler.core.backend.Scheduler.reroute] — operator-initiated
 * RE-ROUTE action (DESIGN.md 22.2 edge case: "5 jobs waiting for offline node X").
 *
 *  - [REROUTED] — row was non-terminal; target_node/target_tag updated and a fresh
 *    outbox row inserted with the new routing key.
 *  - [ALREADY_TERMINAL] — row reached SUCCEEDED/FAILED/CANCELLED; nothing to redirect.
 *  - [NOT_FOUND] — no row with that id.
 *  - [CONFLICT] — version CAS lost (concurrent mutation); caller can retry.
 *
 * **Note on duplicate delivery:** if the originally-targeted node comes back online, it
 * MAY pick up the stale Rabbit message that was sitting on its queue when reroute fired.
 * `JobRepository.pickup`'s CAS guarantees only one worker wins the row — the other
 * `pickup() == null` and ack's the message harmlessly. Operators can rely on
 * at-most-once execution, never see double work.
 */
@Serializable
public enum class RerouteResult {
    REROUTED,
    ALREADY_TERMINAL,
    NOT_FOUND,
    CONFLICT,
}

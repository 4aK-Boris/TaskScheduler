package cs.trade.scheduler.core.frontend.react

import react.useEffectOnce
import react.useState

/**
 * `false` during the first render after (re)mount, `true` from the next one on.
 *
 * This is how a live list tells "a new row just arrived" apart from "the whole list was replaced".
 * React already mounts a DOM node only for a genuinely new key, so a CSS enter-animation fires
 * exactly on new rows for free — the only thing that needs suppressing is the initial paint, where
 * *every* row is new and animating them all would flash the whole table.
 *
 * Pair it with a `key` on the list container that encodes the query (page, filters, sort). Changing
 * the query remounts the container, this resets to `false`, and that first paint stays still; rows
 * that appear afterwards animate.
 */
public fun useSettled(): Boolean {
    var settled by useState(false)
    useEffectOnce { settled = true }
    return settled
}

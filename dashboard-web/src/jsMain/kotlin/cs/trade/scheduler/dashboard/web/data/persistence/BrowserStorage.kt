package cs.trade.scheduler.dashboard.web.data.persistence

import kotlinx.browser.window

/**
 * Thin wrapper around `window.localStorage` for the dashboard's UI preferences. Used for
 * dark-mode toggle, JobList filter state, etc.
 *
 * **Why a wrapper:** localStorage throws `SecurityError` in private-mode browsers and
 * `QuotaExceededError` when full. We wrap every read/write in `runCatching` so a
 * disabled / full storage degrades to "settings won't persist" instead of crashing the
 * dashboard. UI behaviour is unchanged in-session — only the survive-reload promise is
 * broken.
 *
 * Keys are flat strings; namespaces by prefix (`dashboard.dark`, `dashboard.jobs.filter`).
 */
public object BrowserStorage {

    public fun load(key: String): String? = runCatching {
        window.localStorage.getItem(key)
    }.getOrNull()

    public fun save(key: String, value: String?) {
        runCatching {
            if (value == null) window.localStorage.removeItem(key)
            else window.localStorage.setItem(key, value)
        }
    }

    /** Read a Boolean flag with a fallback when storage is unavailable / value malformed. */
    public fun loadBool(key: String, default: Boolean): Boolean =
        load(key)?.toBooleanStrictOrNull() ?: default

    public fun saveBool(key: String, value: Boolean) {
        save(key, value.toString())
    }
}

package org.lvl3.hibi.model

/** Serial ownership on the UI thread: finish the outgoing snapshot before activating its successor. */
class RendererHandoff<T : Any>(private val retire: (T, () -> Unit) -> Unit) {
    var current: T? = null
        private set
    var waiting: Boolean = false
        private set
    private var desired: Pair<T, () -> Unit>? = null

    fun attach(owner: T, activate: () -> Unit) {
        desired = owner to activate
        if (current == null && !waiting) activateDesired() else retireCurrent()
    }

    fun release(owner: T) {
        if (desired?.first === owner) desired = null
        if (current === owner) retireCurrent()
    }

    fun isCurrent(owner: T) = current === owner

    /** A terminated renderer cannot be queried or kept as a recovery source. */
    fun forget(owner: T) {
        if (desired?.first === owner) desired = null
        if (current === owner) { current = null; waiting = false; desired = null }
    }

    private fun retireCurrent() {
        if (waiting) return
        val outgoing = current ?: return
        waiting = true
        var completed = false
        retire(outgoing) {
            if (!completed && current === outgoing) {
                completed = true
                current = null
                waiting = false
                activateDesired()
            }
        }
    }

    private fun activateDesired() {
        val next = desired ?: return
        desired = null
        current = next.first
        next.second()
    }
}

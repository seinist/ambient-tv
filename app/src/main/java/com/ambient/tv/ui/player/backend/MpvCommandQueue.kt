package com.ambient.tv.ui.player.backend

/** Bounded mailbox. No caller waits for native work, including close(). */
internal class MpvCommandQueue(
    private val schedule: (() -> Unit) -> Unit,
    private val failed: (Throwable) -> Unit,
    private val capacity: Int = 64,
    /** Called on the owner thread when an operation starts (its key) and ends (null). */
    private val running: (String?) -> Unit = {},
) {
    private class Work(val action: () -> Unit, val discard: () -> Unit, val keepOnClose: Boolean = false)
    private val lock = Any()
    private val pending = linkedMapOf<String, Work>()
    private var scheduled = false
    private var closed = false

    /**
     * [keepOnClose]: still runs, before the cleanup, when [close] arrives while it is pending
     * (a surface detach must take the VO off a window that is about to disappear).
     */
    fun submit(key: String, discard: () -> Unit = {}, keepOnClose: Boolean = false, action: () -> Unit): Boolean {
        var obsolete: Work? = null
        var start = false
        val accepted = synchronized(lock) {
            if (closed || (key !in pending && pending.size >= capacity)) false
            else {
                obsolete = pending.remove(key)
                pending[key] = Work(action, discard, keepOnClose)
                if (!scheduled) { scheduled = true; start = true }
                true
            }
        }
        obsolete?.discard?.invoke()
        if (!accepted) discard()
        if (start) schedule(::runNext)
        return accepted
    }

    /**
     * Discard obsolete work and run cleanup after the in-flight operation, exactly once.
     * Pending work submitted with keepOnClose runs first, in submission order.
     */
    fun close(cleanup: () -> Unit) {
        var start = false
        val discarded = synchronized(lock) {
            if (closed) return
            closed = true
            val kept = pending.entries.filter { it.value.keepOnClose }.map { it.key to it.value }
            val old = pending.values.filterNot { it.keepOnClose }
            pending.clear()
            kept.forEach { (key, work) -> pending[key] = work }
            pending["close"] = Work(cleanup, {})
            if (!scheduled) { scheduled = true; start = true }
            old
        }
        discarded.forEach { it.discard() }
        if (start) schedule(::runNext)
    }

    private fun runNext() {
        var key: String? = null
        val work = synchronized(lock) {
            key = pending.keys.firstOrNull()
            key?.let { pending.remove(it) } ?: run { scheduled = false; null }
        } ?: return
        running(key)
        try { work.action() } catch (error: Exception) { failed(error) } finally { running(null) }
        val again = synchronized(lock) {
            if (pending.isEmpty()) { scheduled = false; false } else true
        }
        // Yield to the owner's event loop. Cleanup may have terminated that loop, so never
        // post an empty drain after the terminal operation.
        if (again) schedule(::runNext)
    }
}

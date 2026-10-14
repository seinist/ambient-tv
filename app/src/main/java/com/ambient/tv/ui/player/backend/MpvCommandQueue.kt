package com.ambient.tv.ui.player.backend

/** Bounded mailbox. No caller waits for native work, including close(). */
internal class MpvCommandQueue(
    private val schedule: (() -> Unit) -> Unit,
    private val failed: (Throwable) -> Unit,
    private val capacity: Int = 64,
) {
    private class Work(val action: () -> Unit, val discard: () -> Unit)
    private val lock = Any()
    private val pending = linkedMapOf<String, Work>()
    private var scheduled = false
    private var closed = false

    fun submit(key: String, discard: () -> Unit = {}, action: () -> Unit): Boolean {
        var obsolete: Work? = null
        var start = false
        val accepted = synchronized(lock) {
            if (closed || (key !in pending && pending.size >= capacity)) false
            else {
                obsolete = pending.remove(key)
                pending[key] = Work(action, discard)
                if (!scheduled) { scheduled = true; start = true }
                true
            }
        }
        obsolete?.discard?.invoke()
        if (!accepted) discard()
        if (start) schedule(::runNext)
        return accepted
    }

    /** Discard obsolete work and run cleanup after the in-flight operation, exactly once. */
    fun close(cleanup: () -> Unit) {
        var start = false
        val discarded = synchronized(lock) {
            if (closed) return
            closed = true
            val old = pending.values.toList()
            pending.clear()
            pending["close"] = Work(cleanup, {})
            if (!scheduled) { scheduled = true; start = true }
            old
        }
        discarded.forEach { it.discard() }
        if (start) schedule(::runNext)
    }

    private fun runNext() {
        val work = synchronized(lock) {
            val key = pending.keys.firstOrNull()
            if (key == null) { scheduled = false; null } else pending.remove(key)
        } ?: return
        try { work.action() } catch (error: Exception) { failed(error) }
        val again = synchronized(lock) {
            if (pending.isEmpty()) { scheduled = false; false } else true
        }
        // Yield to the owner's event loop. Cleanup may have terminated that loop, so never
        // post an empty drain after the terminal operation.
        if (again) schedule(::runNext)
    }
}

package com.ambient.tv.ui.games

import androidx.compose.runtime.RememberObserver

/**
 * Both Compose disposal orders are valid: the Android View can detach first, or the remembered
 * owner can be forgotten first. They share one cleanup, invoked before the GL thread exits.
 * Detach is not necessarily disposal: the TV resolution host can retain and reattach the same
 * composition. That case requests a new session after attach, using its saved checkpoint.
 * An abandoned composition also releases its reservation, including before a View was created.
 */
internal class GameNativeSessionOwner(gate: GameNativeSessionGate = gameNativeSessions) : RememberObserver {
    val closed = gate.tryOpen()
    var cleanup: (() -> Unit)? = null
    var onRetainedAttach: (() -> Unit)? = null
    private var released = false
    private var remembered = false
    private var recoveryRequested = false

    fun release() {
        if (released) return
        released = true
        val action = cleanup
        cleanup = null
        if (action == null) closed?.complete(Unit) else action()
    }

    /** Posted after View attach, so any disposal in the same Compose apply pass has finished. */
    fun viewAttached() {
        if (!remembered || !released || recoveryRequested) return
        val recover = onRetainedAttach ?: return
        recoveryRequested = true
        recover()
    }

    override fun onRemembered() { remembered = true }
    override fun onForgotten() = dispose()
    override fun onAbandoned() = dispose()

    private fun dispose() {
        remembered = false
        onRetainedAttach = null
        release()
    }
}

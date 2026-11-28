package com.ambient.tv.ui.player.backend

import java.util.IdentityHashMap

/**
 * Defers releasing a LibVLC instance while MediaPlayers created from it are still retiring on a
 * background stop/release thread (Dolby Vision surface swap).
 */
internal class VlcLibraryReleaseGate<T : Any>(private val release: (T) -> Unit) {
    private val retiring = IdentityHashMap<T, Int>()
    private val awaiting = IdentityHashMap<T, Unit>()

    /** A player of [library] starts its asynchronous stop; pair with [retired]. */
    @Synchronized fun retiring(library: T) {
        retiring[library] = (retiring[library] ?: 0) + 1
    }

    /** The retiring player of [library] has been released; a pending library release runs now. */
    @Synchronized fun retired(library: T) {
        val left = (retiring[library] ?: return) - 1
        if (left > 0) { retiring[library] = left; return }
        retiring.remove(library)
        if (awaiting.remove(library) != null) runCatching { release(library) }
    }

    /** Releases [library] now, or once its last retiring player is gone. */
    @Synchronized fun releaseWhenIdle(library: T) {
        if ((retiring[library] ?: 0) > 0) awaiting[library] = Unit
        else runCatching { release(library) }
    }

    @Synchronized internal fun pending(library: T): Boolean = awaiting.containsKey(library)
}

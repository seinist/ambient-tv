package com.ambient.tv.ui.player.backend

import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer

/** A process-lifetime quarantine must keep the JNI/Java owners strongly reachable.
 * A later player must never inherit an output whose native retirement is unknown. */
internal object VlcNativeRetirement {
    private val retained = mutableListOf<Pair<MediaPlayer, LibVLC>>()
    private val query by lazy {
        runCatching { MediaPlayer::class.java.getMethod("ambientOutputQuarantined") }.getOrNull()
    }

    @Synchronized private fun quarantine(player: MediaPlayer, library: LibVLC, cause: Throwable): Nothing {
        if (retained.none { it.first === player }) {
            retained += player to library
            nativeAudioReleases.beginRelease().completeExceptionally(NativeOutputReleaseUncertain(cause))
        }
        throw NativeOutputReleaseUncertain(cause)
    }

    fun verify(player: MediaPlayer, library: LibVLC) {
        val poisoned = runCatching { query?.invoke(null) == true }
            .getOrElse { quarantine(player, library, it) }
        if (poisoned) quarantine(player, library, IllegalStateException("VLC GPU output quarantined"))
    }

    fun stop(player: MediaPlayer, library: LibVLC) {
        verify(player, library)
        try { player.stop() } catch (e: Throwable) { quarantine(player, library, e) }
        verify(player, library)
    }

    // Surface/UI detachment is done on the main thread by the caller before
    // this worker runs. AWindow.detachViews is then an idempotent no-op.
    fun release(player: MediaPlayer, library: LibVLC) {
        verify(player, library)
        try { player.release() } catch (e: Throwable) { quarantine(player, library, e) }
        verify(player, library)
    }
}

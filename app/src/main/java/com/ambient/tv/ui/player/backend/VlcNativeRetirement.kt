package com.ambient.tv.ui.player.backend

import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

/** Deadline without interrupting or cancelling ownership of a native call. */
internal fun <T> boundedNativeOperation(timeoutMs: Long, operation: () -> T): T {
    val task = FutureTask<T> { operation() }
    Thread(task, "vlc-native-retirement-call").apply { isDaemon = true }.start()
    try {
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    }
    // A TimeoutException deliberately leaves the task running. The caller
    // must keep its native owners alive and never invoke them again.
}

/** A process-lifetime quarantine must keep the JNI/Java owners strongly reachable.
 * A later player must never inherit an output whose native retirement is unknown. */
internal object VlcNativeRetirement {
    private val retained = mutableListOf<Pair<MediaPlayer, LibVLC>>()
    private const val NATIVE_LIMIT_MS = 15_000L
    @Volatile private var poisoned = false
    val isQuarantined: Boolean get() = poisoned
    @Synchronized private fun poison(cause: Throwable) {
        if (!poisoned) {
            poisoned = true
            nativeAudioReleases.beginRelease().completeExceptionally(NativeOutputReleaseUncertain(cause))
        }
    }
    fun ensureAvailable() {
        if (poisoned) throw IllegalStateException("VLC native output quarantined")
        if (runCatching { query?.invoke(null) == true }.getOrElse {
                poison(it); throw NativeOutputReleaseUncertain(it)
            }) {
            val cause = IllegalStateException("VLC GPU output quarantined")
            poison(cause)
            throw NativeOutputReleaseUncertain(cause)
        }
    }
    private val query by lazy {
        runCatching { MediaPlayer::class.java.getMethod("ambientOutputQuarantined") }.getOrNull()
    }

    val supported: Boolean get() = query != null

    @Synchronized private fun quarantine(player: MediaPlayer, library: LibVLC, cause: Throwable): Nothing {
        if (retained.none { it.first === player }) {
            retained += player to library
        }
        poison(cause)
        throw NativeOutputReleaseUncertain(cause)
    }

    fun verify(player: MediaPlayer, library: LibVLC) {
        try { ensureAvailable() } catch (e: Throwable) { quarantine(player, library, e) }
    }

    fun stop(player: MediaPlayer, library: LibVLC) {
        verify(player, library)
        try { boundedNativeOperation(NATIVE_LIMIT_MS) { player.stop() } } catch (e: Throwable) { quarantine(player, library, e) }
        verify(player, library)
    }

    fun releaseLibrary(player: MediaPlayer, library: LibVLC, release: () -> Unit) {
        verify(player, library)
        try { boundedNativeOperation(NATIVE_LIMIT_MS, release) } catch (e: Throwable) { quarantine(player, library, e) }
        verify(player, library)
    }

    // Surface/UI detachment is done on the main thread by the caller before
    // this worker runs. AWindow.detachViews is then an idempotent no-op.
    fun release(player: MediaPlayer, library: LibVLC) {
        verify(player, library)
        try { boundedNativeOperation(NATIVE_LIMIT_MS) { player.release() } } catch (e: Throwable) { quarantine(player, library, e) }
        verify(player, library)
    }
}

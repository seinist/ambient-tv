package com.ambient.tv.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Largest width of the held frame; it is dimmed under a spinner, so 720p is plenty. */
private const val HOLD_MAX_WIDTH = 1280f
/** The copy runs before the outgoing renderer is released; never stall the hand-off longer. */
private const val HOLD_CAPTURE_TIMEOUT_MS = 150L
private const val HOLD_POLL_MS = 100L
/** Position progress that proves the new renderer is drawing frames again. */
private const val HOLD_PROGRESS_MS = 300L
/** A paused hand-off shows its new still frame after the restore seek. */
private const val HOLD_PAUSED_MS = 1_500L
/** Safety net: never cover the video longer than this. */
private const val HOLD_MAX_MS = 15_000L
private const val HOLD_DIM = .35f

internal object VideoFrameHoldPolicy {
    /** True once the incoming renderer visibly took over (or the hold must give up). */
    fun released(
        elapsedMs: Long,
        status: PlaybackStatus,
        startPositionMs: Long,
        wantsToPlay: Boolean,
    ): Boolean = when {
        status.error != null || status.ended -> true
        status.playing && status.positionMs - startPositionMs >= HOLD_PROGRESS_MS -> true
        !wantsToPlay && elapsedMs >= HOLD_PAUSED_MS -> true
        else -> elapsedMs >= HOLD_MAX_MS
    }
}

/**
 * Keeps the last picture on screen while the same title's renderer is rebuilt (stream reload,
 * renderer switch, output change): the outgoing video plane is copied just before it is
 * released and shown dimmed, under the player's spinner, until the new renderer draws again.
 * Without a copy (protected/secure surfaces, no video plane, timeout) the plane stays black as
 * before. Main thread only.
 */
internal object VideoFrameHold {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The renderer that must take over before the hold disappears; null while it is created. */
    var awaiting by mutableStateOf<PlaybackHandle?>(null)
        private set
    private val callbackThread by lazy { HandlerThread("video-frame-hold").apply { start() } }

    /** Copy the visible video plane before the outgoing renderer of the same title is released. */
    fun captureForHandOff() {
        frame = capture(AmbientVideoBackdrop.surface)
        awaiting = null
    }

    /** The incoming renderer is in place; the hold lasts until it draws. */
    fun handOffTo(handle: PlaybackHandle) {
        if (frame != null) awaiting = handle
    }

    fun release(handle: PlaybackHandle) {
        if (awaiting === handle) clear()
    }

    fun clear() {
        frame = null
        awaiting = null
    }

    private fun capture(view: android.view.SurfaceView?): ImageBitmap? {
        view ?: return null
        if (!view.isShown || view.width <= 0 || view.height <= 0 || view.holder.surface?.isValid != true) return null
        val scale = minOf(1f, HOLD_MAX_WIDTH / view.width)
        val width = (view.width * scale).roundToInt().coerceAtLeast(1)
        val height = (view.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val requested = runCatching {
            // The listener runs on a private thread, so waiting here cannot block its delivery.
            PixelCopy.request(view, bitmap, { code -> result.set(code); done.countDown() }, Handler(callbackThread.looper))
        }.isSuccess
        if (!requested) { bitmap.recycle(); return null }
        if (!done.await(HOLD_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null // late copy: let GC take it
        if (result.get() != PixelCopy.SUCCESS) { bitmap.recycle(); return null }
        return bitmap.asImageBitmap()
    }
}

/** The held frame above the video plane and below the player chrome (spinner, OSD). */
@Composable
internal fun VideoFrameHoldLayer(handle: PlaybackHandle?, modifier: Modifier = Modifier) {
    LaunchedEffect(handle) { if (handle == null) VideoFrameHold.clear() }
    val frame = VideoFrameHold.frame ?: return
    val awaiting = VideoFrameHold.awaiting
    LaunchedEffect(frame, awaiting) {
        val incoming = awaiting ?: run {
            // Creating the incoming renderer failed or never finished: do not keep the picture.
            delay(HOLD_MAX_MS)
            VideoFrameHold.clear()
            return@LaunchedEffect
        }
        val start = SystemClock.elapsedRealtime()
        val startPosition = runCatching { incoming.status().positionMs }.getOrDefault(0L)
        while (true) {
            delay(HOLD_POLL_MS)
            val status = runCatching { incoming.status() }.getOrNull() ?: break
            val wantsToPlay = runCatching { incoming.wantsToPlay() }.getOrDefault(false)
            if (VideoFrameHoldPolicy.released(SystemClock.elapsedRealtime() - start, status, startPosition, wantsToPlay)) break
        }
        VideoFrameHold.release(incoming)
    }
    Box(modifier.background(Color.Black)) {
        Image(frame, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = HOLD_DIM)))
    }
}

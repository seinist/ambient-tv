package com.ambient.tv.ui.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Engines that step exactly one frame themselves (MPV frame-step / frame-back-step). */
interface FrameStepTarget {
    /** Pauses and shows the next (or previous) frame. */
    fun stepFrame(forward: Boolean)
}

/** Fallback frame time when the engine reports no frame rate. */
internal const val DEFAULT_FRAME_MS = 40L

/** One frame: the engine's own step where it has one, else pause and an exact seek by a frame. */
internal fun stepFrame(handle: PlaybackHandle, forward: Boolean, frameMs: Long = DEFAULT_FRAME_MS) {
    AbRepeat.clearIfOther(handle)
    if (handle is FrameStepTarget) return handle.stepFrame(forward)
    handle.pause()
    val status = handle.status()
    handle.seekTo(frameStepTarget(status.positionMs, status.durationMs, forward, frameMs))
}

internal fun frameStepTarget(positionMs: Long, durationMs: Long, forward: Boolean, frameMs: Long): Long {
    val target = positionMs + if (forward) frameMs else -frameMs
    return if (durationMs > 0) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
}

/**
 * A-B repeat for every engine: once both points are set, playback that reaches B jumps back to
 * A. It belongs to one playback; another handle (next title, renderer switch) clears it.
 */
internal object AbRepeat {
    var pointA by mutableStateOf<Long?>(null)
        private set
    var pointB by mutableStateOf<Long?>(null)
        private set
    private var owner: PlaybackHandle? = null
    private var loop: Job? = null
    private val scope = MainScope()

    val active: Boolean get() = pointA != null && pointB != null

    fun setA(handle: PlaybackHandle) {
        clearIfOther(handle)
        owner = handle
        pointA = handle.status().positionMs
        // A new A after B starts the range over.
        if (pointB?.let { it <= pointA!! } == true) pointB = null
        restart()
    }

    fun setB(handle: PlaybackHandle) {
        clearIfOther(handle)
        val position = handle.status().positionMs
        val a = pointA ?: return setA(handle)
        if (position <= a + MIN_RANGE_MS) return
        owner = handle
        pointB = position
        restart()
        handle.seekTo(a)
    }

    fun clear() {
        loop?.cancel(); loop = null
        pointA = null; pointB = null; owner = null
    }

    fun clearIfOther(handle: PlaybackHandle) { if (owner != null && owner !== handle) clear() }

    private fun restart() {
        loop?.cancel()
        val handle = owner ?: return
        if (!active) return
        loop = scope.launch {
            while (true) {
                delay(POLL_MS)
                if (BackgroundPlaybackSession.activeHandle() !== handle) { clear(); return@launch }
                val a = pointA ?: return@launch
                val b = pointB ?: return@launch
                if (abRepeatWraps(handle.status().positionMs, a, b)) handle.seekTo(a)
            }
        }
    }

    private const val POLL_MS = 100L
    internal const val MIN_RANGE_MS = 500L
}

/** True once playback reached or passed B (a seek past B wraps too). */
internal fun abRepeatWraps(positionMs: Long, aMs: Long, bMs: Long): Boolean = bMs > aMs && positionMs >= bMs

/**
 * Saves the frame on screen as a PNG in Pictures/Ambient TV. Copies the active video surface,
 * so it works for every engine; protected (DRM) and some HDR surfaces cannot be copied.
 */
internal suspend fun saveVideoScreenshot(context: Context, title: String?): Boolean {
    val surface = AmbientVideoBackdrop.surface
    val bitmap = surface?.let { captureFullFrame(it) }
    val saved = bitmap?.let { frame ->
        withContext(Dispatchers.IO) {
            runCatching { writeScreenshot(context, frame, title) }.getOrDefault(false).also { frame.recycle() }
        }
    } ?: false
    AppNotifications.post(AppNotice(
        "screenshot", if (saved) R.string.player_tools_screenshot_saved else R.string.player_tools_screenshot_failed,
        subject = title, phase = if (saved) NoticePhase.SUCCESS else NoticePhase.ERROR,
        kind = NoticeKind.PLAYBACK, interruptPlayback = false,
    ))
    return saved
}

private suspend fun captureFullFrame(surface: SurfaceView): Bitmap? {
    if (surface.width <= 0 || surface.height <= 0 || surface.holder.surface?.isValid != true) return null
    // The surface buffer may be larger than the view (render at panel resolution); the view size is what is shown.
    val scale = minOf(1f, MAX_SCREENSHOT_EDGE / maxOf(surface.width, surface.height).toFloat())
    val bitmap = Bitmap.createBitmap((surface.width * scale).toInt().coerceAtLeast(1), (surface.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val ok = runCatching {
        suspendCancellableCoroutine { cont ->
            PixelCopy.request(surface, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
    }.getOrDefault(false)
    return if (ok) bitmap else { bitmap.recycle(); null }
}

private fun writeScreenshot(context: Context, bitmap: Bitmap, title: String?): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, screenshotFileName(title, System.currentTimeMillis()))
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Ambient TV")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val written = runCatching {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
    }.getOrDefault(false)
    if (!written) { resolver.delete(uri, null, null); return false }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    return true
}

/** `Title_2026-10-27_21-05-33.png`; characters file systems reject become underscores. */
internal fun screenshotFileName(title: String?, epochMs: Long): String {
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date(epochMs))
    val name = title?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")?.trim()?.take(80)?.ifBlank { null } ?: "Ambient TV"
    return "${name}_$stamp.png"
}

private const val MAX_SCREENSHOT_EDGE = 3840f

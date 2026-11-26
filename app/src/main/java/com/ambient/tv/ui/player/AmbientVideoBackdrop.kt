package com.ambient.tv.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Ambient mode for portrait phones, like YouTube: the letterbox around the video takes the
 * blurred colours of the current picture instead of black. The SurfaceView cannot be filtered
 * live, so a tiny frame is copied every [CAPTURE_INTERVAL_MS]; the shown colours glide towards
 * it continuously ([AmbientGlide]) instead of stepping from snapshot to snapshot.
 */
internal object AmbientVideoBackdrop {
    /** Set by the persistent video host: a phone shows a real picture (any orientation). */
    var enabled by mutableStateOf(false)
    /** Phone in landscape: only side bars (portrait/square video) get ambient, never top/bottom bars. */
    var landscape by mutableStateOf(false)
    /** The picture is narrower than the player, i.e. it has bars left and right. */
    var pillarbox by mutableStateOf(false)
    /** The video views drop their black fill while true. */
    val active: Boolean get() = enabled && (!landscape || pillarbox)
    /** The SurfaceView of the attached video output, if any. */
    var surface by mutableStateOf<SurfaceView?>(null)

    fun attach(view: SurfaceView) { surface = view }
    fun detach(view: SurfaceView?) { if (surface === view) surface = null }
}

private const val CAPTURE_INTERVAL_MS = 500L
/** Below 97 % of the player width the side bars are wide enough to be worth filling. */
private const val PILLARBOX_THRESHOLD = 0.97f
private const val CAPTURE_WIDTH = 32
/** About 30 colour updates per second are plenty for a 32 px, heavily blurred picture. */
private const val GLIDE_FRAME_MS = 33L

@Composable
internal fun AmbientVideoBackdropLayer(modifier: Modifier = Modifier) {
    AmbientSurfaceBackdropLayer(
        surface = AmbientVideoBackdrop.surface,
        enabled = AmbientVideoBackdrop.enabled,
        landscape = AmbientVideoBackdrop.landscape,
        modifier = modifier,
        followVideoPillarbox = true,
    )
}

/**
 * Shared video/game ambient appearance. An explicit surface keeps a game independent of the
 * persistent video's global attachment. PixelCopy samples the presented image, never the core's
 * synchronous raw-frame API. Reduced effects keep the colour fill with sparse, unblurred updates.
 */
@Composable
internal fun AmbientSurfaceBackdropLayer(
    surface: SurfaceView?,
    enabled: Boolean,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    sampling: Boolean = true,
    reducedEffects: Boolean = false,
    followVideoPillarbox: Boolean = false,
) {
    val glide = remember(surface) { AmbientGlide() }
    var shown by remember(surface) { mutableStateOf<Bitmap?>(null) }
    var version by remember(surface) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var layerWidth by remember { mutableIntStateOf(0) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSampling by rememberUpdatedState(sampling)
    val active = enabled && (!followVideoPillarbox || !landscape || AmbientVideoBackdrop.pillarbox)
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(surface, lifecycle, reducedEffects, followVideoPillarbox) {
        surface ?: return@LaunchedEffect
        val handler = Handler(Looper.getMainLooper())
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Conflation is only a wake-up, not a frame queue: capture and draw
            // share the UI dispatcher and the glide always reads the latest target.
            val changed = Channel<Unit>(Channel.CONFLATED)
            changed.trySend(Unit) // Resume a partially settled glide after lifecycle restart.
            launch {
                var capturePixels = IntArray(0)
                while (true) {
                    // Widescreen in landscape keeps its black top/bottom bars.
                    if (followVideoPillarbox) AmbientVideoBackdrop.pillarbox =
                        layerWidth > 0 && surface.width > 0 &&
                            surface.width * surface.scaleX < layerWidth * PILLARBOX_THRESHOLD
                    // Nothing to fill (widescreen in landscape): no frame copies.
                    val captureActive = if (followVideoPillarbox) AmbientVideoBackdrop.active else currentActive
                    if (currentEnabled && captureActive && currentSampling) captureFrame(surface, handler)?.let { captured ->
                        try {
                            val count = captured.width * captured.height
                            if (capturePixels.size != count) capturePixels = IntArray(count)
                            captured.getPixels(capturePixels, 0, captured.width, 0, 0, captured.width, captured.height)
                            if (glide.setTarget(capturePixels, captured.width, captured.height)) changed.trySend(Unit)
                        } finally {
                            captured.recycle()
                        }
                    }
                    delay(if (reducedEffects) CAPTURE_INTERVAL_MS * 3 else CAPTURE_INTERVAL_MS)
                }
            }
            while (true) {
                // No 30 Hz wake-ups when the colour target is settled (including paused games).
                changed.receive()
                var last = android.os.SystemClock.uptimeMillis()
                var moving: Boolean
                do {
                    delay(if (reducedEffects) CAPTURE_INTERVAL_MS * 3 else GLIDE_FRAME_MS)
                    val now = android.os.SystemClock.uptimeMillis()
                    moving = glide.step(if (reducedEffects) Float.MAX_VALUE else (now - last).toFloat())
                    val width = glide.width
                    val height = glide.height
                    if (width > 0 && height > 0 &&
                        (moving || shown?.width != width || shown?.height != height)) {
                        val bitmap = shown?.takeIf { it.width == width && it.height == height }
                            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { shown = it }
                        // setPixels consumes the scratch synchronously; PixelCopy never owns it.
                        bitmap.setPixels(glide.pixels(), 0, width, 0, 0, width, height)
                        version++
                    }
                    last = now
                } while (moving)
            }
        }
    }
    Box(modifier.background(Color.Black).onSizeChanged { layerWidth = it.width }) {
        val bitmap = shown
        if (bitmap != null && active) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            // A 32 px frame upscaled bilinearly is already soft; blur (API 31+) smooths the rest.
            Canvas(Modifier.fillMaxSize()
                .then(if (reducedEffects) Modifier else Modifier.blur(48.dp))
                .graphicsLayer { alpha = 0.6f }) {
                // Read in the draw phase: each glide step redraws without recomposing.
                version
                val scale = maxOf(size.width / image.width, size.height / image.height)
                val width = image.width * scale
                val height = image.height * scale
                drawImage(
                    image = image,
                    dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
                    dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        // Fade into black towards the controls at the bottom, like YouTube; in landscape
        // towards the screen edges left and right.
        Box(Modifier.fillMaxSize().background(if (landscape) Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.6f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f),
        ) else Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.35f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f),
        )))
    }
}

/** Null when the surface has no frame yet or refuses the copy (protected content). */
private suspend fun captureFrame(surface: SurfaceView, handler: Handler): Bitmap? {
    if (surface.width <= 0 || surface.height <= 0 || surface.holder.surface?.isValid != true) return null
    val height = (CAPTURE_WIDTH * surface.height / surface.width.toFloat()).roundToInt().coerceIn(1, CAPTURE_WIDTH * 4)
    val bitmap = Bitmap.createBitmap(CAPTURE_WIDTH, height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(surface, bitmap, { result ->
                if (result != PixelCopy.SUCCESS || !cont.isActive) {
                    // A cancelled copy still owns its destination until this callback runs.
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(null)
                } else {
                    cont.resume(bitmap, onCancellation = { _, abandoned, _ -> abandoned.recycle() })
                }
            }, handler)
        } catch (_: RuntimeException) {
            bitmap.recycle()
            cont.resume(null)
        }
    }
}

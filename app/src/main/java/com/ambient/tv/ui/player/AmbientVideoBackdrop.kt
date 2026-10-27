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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    /** Set by the persistent video host; the video views drop their black fill while true. */
    var enabled by mutableStateOf(false)
    /** The SurfaceView of the attached video output, if any. */
    var surface by mutableStateOf<SurfaceView?>(null)

    fun attach(view: SurfaceView) { surface = view }
    fun detach(view: SurfaceView?) { if (surface === view) surface = null }
}

private const val CAPTURE_INTERVAL_MS = 500L
private const val CAPTURE_WIDTH = 32
/** Time constant of the glide: a scene cut has settled after about three of these. */
internal const val AMBIENT_GLIDE_TAU_MS = 700f
/** About 30 colour updates per second are plenty for a 32 px, heavily blurred picture. */
private const val GLIDE_FRAME_MS = 33L

/**
 * The shown ambient colours, per pixel and channel, easing exponentially towards the latest
 * capture. Unlike a cross-fade there is no pause between snapshots and no mid-fade dip where two
 * half-transparent layers let the black show through; a cut glides over in about two seconds.
 */
internal class AmbientGlide {
    var width = 0
        private set
    var height = 0
        private set
    private var current = FloatArray(0)
    private var target = FloatArray(0)

    /** New capture as ARGB pixels; a size change (rotation, new video) starts again from black. */
    fun setTarget(pixels: IntArray, width: Int, height: Int) {
        if (width != this.width || height != this.height || current.size != pixels.size * 3) {
            this.width = width
            this.height = height
            current = FloatArray(pixels.size * 3)
        }
        target = FloatArray(pixels.size * 3) { i ->
            val p = pixels[i / 3]
            when (i % 3) { 0 -> (p shr 16 and 0xFF).toFloat(); 1 -> (p shr 8 and 0xFF).toFloat(); else -> (p and 0xFF).toFloat() }
        }
    }

    /** Moves [elapsedMs] closer to the target; false once nothing visibly changes any more. */
    fun step(elapsedMs: Float): Boolean {
        if (target.size != current.size || current.isEmpty()) return false
        val k = 1f - kotlin.math.exp(-elapsedMs.coerceAtLeast(0f) / AMBIENT_GLIDE_TAU_MS)
        var moving = false
        for (i in current.indices) {
            val delta = target[i] - current[i]
            if (kotlin.math.abs(delta) > 0.5f) moving = true
            current[i] += delta * k
        }
        return moving
    }

    fun pixels(): IntArray = IntArray(current.size / 3) { i ->
        val r = current[i * 3].roundToInt().coerceIn(0, 255)
        val g = current[i * 3 + 1].roundToInt().coerceIn(0, 255)
        val b = current[i * 3 + 2].roundToInt().coerceIn(0, 255)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

@Composable
internal fun AmbientVideoBackdropLayer(modifier: Modifier = Modifier) {
    val surface = AmbientVideoBackdrop.surface
    val glide = remember(surface) { AmbientGlide() }
    var shown by remember(surface) { mutableStateOf<Bitmap?>(null) }
    var version by remember(surface) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(surface, lifecycle) {
        surface ?: return@LaunchedEffect
        val handler = Handler(Looper.getMainLooper())
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    captureFrame(surface, handler)?.let { captured ->
                        val pixels = IntArray(captured.width * captured.height)
                        captured.getPixels(pixels, 0, captured.width, 0, 0, captured.width, captured.height)
                        glide.setTarget(pixels, captured.width, captured.height)
                        captured.recycle()
                    }
                    delay(CAPTURE_INTERVAL_MS)
                }
            }
            var last = android.os.SystemClock.uptimeMillis()
            while (true) {
                delay(GLIDE_FRAME_MS)
                val now = android.os.SystemClock.uptimeMillis()
                if (glide.step((now - last).toFloat()) || shown == null) {
                    val width = glide.width
                    val height = glide.height
                    if (width > 0 && height > 0) {
                        val bitmap = shown?.takeIf { it.width == width && it.height == height }
                            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { shown = it }
                        bitmap.setPixels(glide.pixels(), 0, width, 0, 0, width, height)
                        version++
                    }
                }
                last = now
            }
        }
    }
    Box(modifier.background(Color.Black)) {
        val bitmap = shown
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            // A 32 px frame upscaled bilinearly is already soft; blur (API 31+) smooths the rest.
            Canvas(Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.6f }) {
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
        // Fade into black towards the controls at the bottom, like YouTube.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.35f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f),
        )))
    }
}

/** Null when the surface has no frame yet or refuses the copy (protected content). */
private suspend fun captureFrame(surface: SurfaceView, handler: Handler): Bitmap? {
    if (surface.width <= 0 || surface.height <= 0 || surface.holder.surface?.isValid != true) return null
    val height = (CAPTURE_WIDTH * surface.height / surface.width.toFloat()).roundToInt().coerceIn(1, CAPTURE_WIDTH * 4)
    val bitmap = Bitmap.createBitmap(CAPTURE_WIDTH, height, Bitmap.Config.ARGB_8888)
    val ok = runCatching {
        suspendCancellableCoroutine { cont ->
            PixelCopy.request(surface, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, handler)
        }
    }.getOrDefault(false)
    return if (ok) bitmap else { bitmap.recycle(); null }
}

package com.ambient.tv.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Ambient mode for portrait phones, like YouTube: the letterbox around the video takes the
 * blurred colours of the current picture instead of black. The SurfaceView cannot be filtered
 * live, so a tiny frame is copied every [CAPTURE_INTERVAL_MS] and cross-faded behind the video.
 */
internal object AmbientVideoBackdrop {
    /** Set by the persistent video host; the video views drop their black fill while true. */
    var enabled by mutableStateOf(false)
    /** The SurfaceView of the attached video output, if any. */
    var surface by mutableStateOf<SurfaceView?>(null)

    fun attach(view: SurfaceView) { surface = view }
    fun detach(view: SurfaceView?) { if (surface === view) surface = null }
}

private const val CAPTURE_INTERVAL_MS = 1_500L
private const val CAPTURE_WIDTH = 32

@Composable
internal fun AmbientVideoBackdropLayer(modifier: Modifier = Modifier) {
    val surface = AmbientVideoBackdrop.surface
    var frame by remember(surface) { mutableStateOf<ImageBitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(surface, lifecycle) {
        surface ?: return@LaunchedEffect
        val handler = Handler(Looper.getMainLooper())
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                captureFrame(surface, handler)?.let { frame = it.asImageBitmap() }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }
    Box(modifier.background(Color.Black)) {
        Crossfade(frame, animationSpec = tween(900), label = "ambient") { image ->
            if (image != null) Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // A 32 px frame upscaled bilinearly is already soft; blur (API 31+) smooths the rest.
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.6f },
            )
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

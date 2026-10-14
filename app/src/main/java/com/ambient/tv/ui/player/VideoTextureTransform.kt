@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.view.TextureView
import androidx.media3.ui.AspectRatioFrameLayout

/** Applies the same aspect, fit/crop/stretch and zoom semantics to every video backend. */
internal fun TextureView.applyVideoPresentation(
    presentation: VideoPresentation,
    sourceAspectRatio: Float?,
) {
    val viewWidth = width.toFloat()
    val viewHeight = height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return

    val viewRatio = viewWidth / viewHeight
    val targetRatio = presentation.aspectRatio
        ?: sourceAspectRatio?.takeIf { it.isFinite() && it > 0f }
        ?: viewRatio
    var scaleX = 1f
    var scaleY = 1f
    when (presentation.resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> if (targetRatio > viewRatio) {
            scaleY = viewRatio / targetRatio
        } else {
            scaleX = targetRatio / viewRatio
        }
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> if (targetRatio > viewRatio) {
            scaleX = targetRatio / viewRatio
        } else {
            scaleY = viewRatio / targetRatio
        }
        else -> Unit // RESIZE_MODE_FILL deliberately stretches to the complete viewport.
    }
    val zoom = presentation.zoom.coerceIn(0.5f, 2f)
    // TextureView.setTransform() changes the SurfaceTexture sampling matrix. Some Android-TV
    // codecs (notably NVIDIA's MediaCodec implementation) replace that matrix while rendering,
    // so a selected aspect ratio can appear in the UI without changing the picture. Scaling the
    // actual view quad is owned by the UI layer and therefore behaves identically for Media3,
    // libVLC and mpv surfaces.
    pivotX = viewWidth / 2f
    pivotY = viewHeight / 2f
    this.scaleX = scaleX * zoom
    this.scaleY = scaleY * zoom
}

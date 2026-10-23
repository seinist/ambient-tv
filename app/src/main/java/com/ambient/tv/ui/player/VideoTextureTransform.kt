@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.view.TextureView

/** Applies the same aspect, fit/crop/stretch and zoom semantics to every video backend. */
internal fun TextureView.applyVideoPresentation(
    presentation: VideoPresentation,
    sourceAspectRatio: Float?,
) {
    val viewWidth = width.toFloat()
    val viewHeight = height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return

    val (targetWidth, targetHeight) = videoPresentationSize(width, height, presentation, sourceAspectRatio)
    val scaleX = targetWidth / viewWidth
    val scaleY = targetHeight / viewHeight
    // TextureView.setTransform() changes the SurfaceTexture sampling matrix. Some Android-TV
    // codecs (notably NVIDIA's MediaCodec implementation) replace that matrix while rendering,
    // so a selected aspect ratio can appear in the UI without changing the picture. Scaling the
    // actual view quad is owned by the UI layer and therefore behaves identically for Media3,
    // libVLC and mpv surfaces.
    pivotX = viewWidth / 2f
    pivotY = viewHeight / 2f
    this.scaleX = scaleX
    this.scaleY = scaleY
}

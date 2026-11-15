package com.ambient.tv.ui.player

import android.graphics.Point
import android.view.Surface
import android.view.SurfaceView
import com.ambient.tv.ui.display.isUiRenderHosted
import com.ambient.tv.ui.display.uiRenderViewport

/** One coordinate conversion for MPV, VLC and Media3, including embedded UI render buffers. */
@Suppress("DEPRECATION")
internal fun SurfaceView.videoRenderTargetSize(target: VideoRenderTargetMode): Pair<Int, Int>? {
    val viewport = uiRenderViewport()
    // A new viewport is published before its layout. Do not briefly allocate an oversized
    // buffer by multiplying the previous view dimensions by the next window scale.
    if (viewport == null && isUiRenderHosted()) return null
    val scaleX = viewport?.let { it.windowWidth.toDouble() / it.renderWidth } ?: 1.0
    val scaleY = viewport?.let { it.windowHeight.toDouble() / it.renderHeight } ?: 1.0
    return when (target) {
        VideoRenderTargetMode.UI -> windowVideoRenderSize(width, height)
        VideoRenderTargetMode.WINDOW -> windowVideoRenderSize(width, height, scaleX, scaleY)
        VideoRenderTargetMode.DISPLAY -> {
            val output = display ?: return null
            val logical = Point().also(output::getRealSize)
            val mode = output.mode
            val rotated = output.rotation == Surface.ROTATION_90 || output.rotation == Surface.ROTATION_270
            physicalVideoRenderSize(width, height, logical.x, logical.y,
                if (rotated) mode.physicalHeight else mode.physicalWidth,
                if (rotated) mode.physicalWidth else mode.physicalHeight,
                scaleX, scaleY)
        }
    }
}

internal fun SurfaceView.hasScaledUiRenderViewport(): Boolean = uiRenderViewport()?.let {
    it.renderWidth != it.windowWidth || it.renderHeight != it.windowHeight
} ?: false

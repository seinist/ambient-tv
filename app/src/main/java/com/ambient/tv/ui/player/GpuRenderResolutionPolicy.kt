package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences

/**
 * Whether a GPU video output (mpv vo=gpu/gpu-next, VLC libplacebo/GL tone mapping) renders its
 * buffer at the physical HDMI mode (e.g. 4K behind a 1080p TV desktop) or at the logical window
 * size. A 4K buffer costs 4× the shader work, a 4K FP16/10-bit swapchain and usually CLIENT
 * (GPU) composition in SurfaceFlinger; those dropped frames on TVs even without filters.
 *
 * - [AppPreferences.GPU_RENDER_PANEL]: always physical.
 * - [AppPreferences.GPU_RENDER_UI]: follows the UI render buffer; the display/HWC scales.
 * - Auto: physical on capable devices with selected GPU image work; MPV also permits a
 *   deliberately selected upscaler to request physical output on low-end devices, because a
 *   1080p logical buffer would otherwise disable 1080p→4K GPU upscaling entirely. Untouched
 *   defaults and HDR→SDR tone mapping retain the original logical window resolution, even
 *   if the UI renders at a different size. Direct MediaCodec output keeps its native plane.
 *
 * [gpuOutput] false (direct MediaCodec/Android surface) never fixes the buffer size.
 */
internal fun gpuRenderAtPanelResolution(
    key: String,
    gpuOutput: Boolean,
    lowEndDevice: Boolean,
    userImageProcessing: Boolean,
): Boolean = gpuOutput && when (key) {
    AppPreferences.GPU_RENDER_PANEL -> true
    AppPreferences.GPU_RENDER_UI -> false
    else -> !lowEndDevice && userImageProcessing
}

/** A UI-only resolution preference must not silently lower Auto's original video target. */
internal fun gpuRenderTargetMode(
    resolutionKey: String,
    gpuOutput: Boolean,
    atDisplayResolution: Boolean,
): VideoRenderTargetMode = when {
    !gpuOutput || resolutionKey == AppPreferences.GPU_RENDER_UI -> VideoRenderTargetMode.UI
    atDisplayResolution -> VideoRenderTargetMode.DISPLAY
    else -> VideoRenderTargetMode.WINDOW
}

/**
 * A deliberately selected MPV scaler needs a real GPU output target. On low-end devices
 * Render Resolution=Auto previously always kept the 1080p UI buffer even if a user explicitly
 * selected NNEDI/RAVU/FSR or another upscaler to render 1080p sources at a 4K HDMI mode.
 * Respect an explicit UI target, but let an explicit upscale choice opt out of the automatic
 * low-end cap. The frame-drop watchdog still handles devices unable to sustain that work.
 */
internal fun mpvExplicitUpscalerRequestsPanel(options: com.ambient.tv.data.local.MpvImageOptions): Boolean {
    val scaler = options.normalized().scaler
    return scaler != com.ambient.tv.data.local.MpvImageOptions().scaler && scaler != "bilinear"
}

/** Stats describe the selected coordinate space and the confirmed native surface dimensions. */
internal fun gpuRenderResolutionLabel(
    gpuOutput: Boolean, panel: Boolean, width: Int?, height: Int?, windowResolution: Boolean = false,
): String {
    val size = if (width != null && height != null && width > 0 && height > 0) " ${width}×$height" else ""
    return when {
        !gpuOutput -> "Direct$size"
        panel -> "Panel$size"
        windowResolution -> "Window$size"
        else -> "UI$size"
    }
}

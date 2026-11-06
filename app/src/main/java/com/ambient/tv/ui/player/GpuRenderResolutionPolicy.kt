package com.ambient.tv.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.ambient.tv.data.local.AppPreferences

/**
 * Whether a GPU video output (mpv vo=gpu/gpu-next, VLC libplacebo/GL tone mapping) renders its
 * buffer at the physical HDMI mode (e.g. 4K behind a 1080p TV desktop) or at the logical window
 * size. A 4K buffer costs 4× the shader work, a 4K FP16/10-bit swapchain and usually CLIENT
 * (GPU) composition in SurfaceFlinger; those dropped frames on TVs even without filters.
 *
 * - [AppPreferences.GPU_RENDER_PANEL]: always physical.
 * - [AppPreferences.GPU_RENDER_UI]: always logical; the display/HWC scales.
 * - Auto: physical only on a device that is not low-end and only when the user chose GPU image
 *   work (non-default scaler, shaders, filters) that benefits from it. Untouched defaults,
 *   HDR→SDR tone mapping or a direct (MediaCodec) surface render at UI resolution.
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

/** Stats label: "Panel 3840×2160" / "UI 1920×1080" / "Direkt". */
internal fun gpuRenderResolutionLabel(gpuOutput: Boolean, panel: Boolean, width: Int?, height: Int?): String {
    val size = if (width != null && height != null && width > 0 && height > 0) " ${width}×$height" else ""
    return when {
        !gpuOutput -> "Direct$size"
        panel -> "Panel$size"
        else -> "UI$size"
    }
}

/** Physical HDMI mode for Panel, logical window metrics for UI (stats only). */
internal fun gpuRenderSize(context: Context, panel: Boolean): Pair<Int?, Int?> {
    if (panel) {
        val mode = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        }.getOrNull()
        if (mode != null) return maxOf(mode.physicalWidth, mode.physicalHeight) to minOf(mode.physicalWidth, mode.physicalHeight)
    }
    val metrics = context.resources.displayMetrics
    return maxOf(metrics.widthPixels, metrics.heightPixels) to minOf(metrics.widthPixels, metrics.heightPixels)
}

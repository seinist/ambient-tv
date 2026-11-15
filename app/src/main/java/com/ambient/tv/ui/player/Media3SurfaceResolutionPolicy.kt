package com.ambient.tv.ui.player

/** The intended GPU target can be known before it is safe to resize the video surface. */
internal data class Media3SurfaceResolutionPolicy(
    val targetAtDisplayResolution: Boolean,
    /** GPU processing is eligible; the controller also checks whether this viewport needs fixing. */
    val fixSurfaceSize: Boolean,
    val targetAtWindowResolution: Boolean = false,
) {
    val targetMode: VideoRenderTargetMode get() = when {
        targetAtDisplayResolution -> VideoRenderTargetMode.DISPLAY
        targetAtWindowResolution -> VideoRenderTargetMode.WINDOW
        else -> VideoRenderTargetMode.UI
    }

    fun fixesBuffer(uiScaled: Boolean): Boolean = fixSurfaceSize && targetMode.fixesBuffer(uiScaled)
}

/**
 * Resolve scaler availability independently from whether image processing is already enabled.
 * Otherwise a 1080p TV desktop can hide the scaler needed to reach its active 4K output.
 * Only an enabled, supported SDR effect path may fix the native surface's buffer size.
 */
internal fun media3SurfaceResolutionPolicy(
    resolutionKey: String,
    lowEndDevice: Boolean,
    gpuEffectsSupported: Boolean,
    sdrEffectsAllowed: Boolean,
    processingEnabled: Boolean,
    reducedEffects: Boolean,
    effectsFailed: Boolean,
): Media3SurfaceResolutionPolicy {
    val targetAtDisplayResolution = gpuRenderAtPanelResolution(
        key = resolutionKey,
        gpuOutput = true,
        lowEndDevice = lowEndDevice,
        userImageProcessing = true,
    )
    val targetAtWindowResolution = gpuRenderTargetMode(resolutionKey, gpuOutput = true,
        atDisplayResolution = targetAtDisplayResolution) == VideoRenderTargetMode.WINDOW
    return Media3SurfaceResolutionPolicy(
        targetAtDisplayResolution = targetAtDisplayResolution,
        fixSurfaceSize = (targetAtDisplayResolution || targetAtWindowResolution) && gpuEffectsSupported && sdrEffectsAllowed &&
            processingEnabled && !reducedEffects && !effectsFailed,
        targetAtWindowResolution = targetAtWindowResolution,
    )
}

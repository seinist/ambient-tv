package com.ambient.tv.ui.player

/** The intended GPU target can be known before it is safe to resize the video surface. */
internal data class Media3SurfaceResolutionPolicy(
    val targetAtDisplayResolution: Boolean,
    /** A requested or retained GPU output owns sizing; the controller also checks the viewport. */
    val fixSurfaceSize: Boolean,
    val targetAtWindowResolution: Boolean = false,
    /** The running output may keep WINDOW while the menu still advertises a possible DISPLAY scaler. */
    val bufferTargetMode: VideoRenderTargetMode = when {
        targetAtDisplayResolution -> VideoRenderTargetMode.DISPLAY
        targetAtWindowResolution -> VideoRenderTargetMode.WINDOW
        else -> VideoRenderTargetMode.UI
    },
) {
    /** Prospective target for scaler availability, including before the first GPU effect. */
    val targetMode: VideoRenderTargetMode get() = when {
        targetAtDisplayResolution -> VideoRenderTargetMode.DISPLAY
        targetAtWindowResolution -> VideoRenderTargetMode.WINDOW
        else -> VideoRenderTargetMode.UI
    }

    fun fixesBuffer(uiScaled: Boolean): Boolean = fixSurfaceSize && bufferTargetMode.fixesBuffer(uiScaled)
}

/**
 * Resolve scaler availability independently from whether image processing is already enabled.
 * Otherwise a 1080p TV desktop can hide the scaler needed to reach its active 4K output.
 * A direct decoder stays unconstrained until a supported SDR effect path is requested. Media3
 * retains an initialized GPU sink when effects are removed, reduced or fail; that output must
 * still keep its video resolution independent of the UI without re-enabling any effects.
 */
internal fun media3SurfaceResolutionPolicy(
    resolutionKey: String,
    lowEndDevice: Boolean,
    gpuEffectsSupported: Boolean,
    sdrEffectsAllowed: Boolean,
    processingEnabled: Boolean,
    reducedEffects: Boolean,
    effectsFailed: Boolean,
    gpuPipelineInitialized: Boolean = false,
): Media3SurfaceResolutionPolicy {
    val targetAtDisplayResolution = gpuRenderAtPanelResolution(
        key = resolutionKey,
        gpuOutput = true,
        lowEndDevice = lowEndDevice,
        userImageProcessing = true,
    )
    val targetAtWindowResolution = gpuRenderTargetMode(resolutionKey, gpuOutput = true,
        atDisplayResolution = targetAtDisplayResolution) == VideoRenderTargetMode.WINDOW
    val effectsEligible = gpuEffectsSupported && sdrEffectsAllowed && processingEnabled &&
        !reducedEffects && !effectsFailed
    val gpuOutput = gpuPipelineInitialized || effectsEligible
    val bufferTargetMode = gpuRenderTargetMode(
        resolutionKey = resolutionKey,
        gpuOutput = gpuOutput,
        atDisplayResolution = gpuRenderAtPanelResolution(
            key = resolutionKey,
            gpuOutput = gpuOutput,
            lowEndDevice = lowEndDevice,
            userImageProcessing = effectsEligible,
        ),
    )
    return Media3SurfaceResolutionPolicy(
        targetAtDisplayResolution = targetAtDisplayResolution,
        fixSurfaceSize = gpuOutput && bufferTargetMode != VideoRenderTargetMode.UI,
        targetAtWindowResolution = targetAtWindowResolution,
        bufferTargetMode = bufferTargetMode,
    )
}

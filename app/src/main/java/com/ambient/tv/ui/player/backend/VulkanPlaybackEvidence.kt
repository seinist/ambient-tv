package com.ambient.tv.ui.player.backend

enum class VulkanPlaybackStage { NOT_TESTED, SURFACE_READY, OUTPUT_INITIALIZED, FRAMES_REPORTED, FAILED }

/** Last Vulkan attempt in this app process; never a visual or audio certification. */
data class VulkanPlaybackEvidence(
    val stage: VulkanPlaybackStage = VulkanPlaybackStage.NOT_TESTED,
    val reason: String? = null,
) {
    fun outputObserved(frames: Boolean): VulkanPlaybackEvidence = when (stage) {
        VulkanPlaybackStage.SURFACE_READY, VulkanPlaybackStage.OUTPUT_INITIALIZED ->
            copy(stage = if (frames) VulkanPlaybackStage.FRAMES_REPORTED else VulkanPlaybackStage.OUTPUT_INITIALIZED)
        else -> this // A late callback cannot erase a failure, nor invent a Surface test.
    }
}

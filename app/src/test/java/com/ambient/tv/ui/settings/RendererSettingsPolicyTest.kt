package com.ambient.tv.ui.settings

import com.ambient.tv.R
import com.ambient.tv.ui.player.backend.*
import org.junit.Assert.*
import org.junit.Test

class RendererSettingsPolicyTest {
    private val supported = VideoAccelerationCapabilities(
        checked = true,
        device = VulkanDeviceCapabilities(render = true, version = (1L shl 22) or (3L shl 12), hostQueryReset = true),
        mpvOutputBuilt = true, mpvDecodeBuilt = true, vlcOutputBuilt = true,
    )

    @Test fun autoShowsTheSettingsOfTheRendererItPlaysWith() {
        for (key in listOf("mpv", "vlc", "media3")) assertEquals(key, selectedRendererSettingsKey(key))
        for (key in listOf("auto", "", "unknown")) assertEquals(PlaybackBackendResolver.resolve(key).key, selectedRendererSettingsKey(key))
    }

    @Test fun untouchedDeviceIsNotReportedAsUnsupportedOrSuccessful() {
        assertEquals(R.string.vulkan_checking, vulkanOutputProblem("mpv", VideoAccelerationCapabilities()))
    }

    @Test fun selectedRendererDoesNotInheritTheOtherRenderersModules() {
        val caps = supported.copy(mpvOutputBuilt = false)
        assertEquals(R.string.vulkan_build_unavailable, vulkanOutputProblem("mpv", caps))
        assertNull(vulkanOutputProblem("vlc", caps))
        assertEquals(R.string.vulkan_build_unavailable, vulkanOutputProblem("vlc", supported.copy(vlcOutputBuilt = false)))
    }

    @Test fun higherApiWithoutRequiredDriverFeaturesDoesNotPass() {
        val caps = supported.copy(device = supported.device.copy(render = false, reason = "timeline_semaphore"))
        assertEquals(R.string.vulkan_missing_timeline, vulkanOutputProblem("mpv", caps))
        assertEquals(R.string.vulkan_missing_timeline, vulkanOutputProblem("vlc", caps))
        assertEquals(R.string.vulkan_missing_host_query,
            vulkanOutputProblem("mpv", supported.copy(device = supported.device.copy(hostQueryReset = false))))
    }

    @Test fun mpvAndVlcHaveDifferentMinimumApiRequirements() {
        val caps = supported.copy(device = supported.device.copy(version = (1L shl 22) or (1L shl 12)))
        assertEquals(R.string.vulkan_mpv_api, vulkanOutputProblem("mpv", caps))
        assertNull(vulkanOutputProblem("vlc", caps))
    }

    @Test fun nativeProbeFailureIsUnknownNotASuccess() {
        assertEquals(R.string.vulkan_probe_failed, vulkanOutputProblem("mpv",
            supported.copy(device = VulkanDeviceCapabilities())))
    }

    @Test fun apiLabelIncludesPatchAndMasksTheVariantBits() {
        assertEquals("1.1.178", vulkanApiLabel((1L shl 22) or (1L shl 12) or 178))
        assertEquals("1.3.250", vulkanApiLabel((1L shl 29) or (1L shl 22) or (3L shl 12) or 250))
    }

    @Test fun outputEvidenceNeedsAnActualSurfaceAttempt() {
        assertEquals(VulkanPlaybackStage.NOT_TESTED, VulkanPlaybackEvidence().outputObserved(true).stage)
        val surface = VulkanPlaybackEvidence(VulkanPlaybackStage.SURFACE_READY)
        assertEquals(VulkanPlaybackStage.OUTPUT_INITIALIZED, surface.outputObserved(false).stage)
        assertEquals(VulkanPlaybackStage.FRAMES_REPORTED, surface.outputObserved(true).stage)
    }

    @Test fun lateOutputCallbackCannotEraseAFailedAttempt() {
        val failure = VulkanPlaybackEvidence(VulkanPlaybackStage.FAILED, "no_video_frames")
        assertEquals(failure, failure.outputObserved(true))
        val frames = VulkanPlaybackEvidence(VulkanPlaybackStage.FRAMES_REPORTED)
        assertEquals(frames, frames.outputObserved(false))
    }
}

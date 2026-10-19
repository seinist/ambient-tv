package com.ambient.tv.ui.player.backend

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.disabledPlayerVideoOutputs
import com.ambient.tv.ui.player.playerPanelVideoVisibility
import org.junit.Assert.*
import org.junit.Test

class MpvHdrGpuPolicyTest {
    private val vulkan12 = (1L shl 22) or (2L shl 12)
    private val caps = VideoAccelerationCapabilities(
        checked = true,
        device = VulkanDeviceCapabilities(render = true, device = "gpu", version = vulkan12, hostQueryReset = true,
            surfaceColorSpaces = listOf(0, 1000104008)),
        mpvOutputBuilt = true,
    )
    private val hdr10Display = setOf(HdrStandard.HDR10, HdrStandard.HLG)
    private fun decide(
        enabled: Boolean = true,
        output: String = "auto",
        source: HdrStandard? = HdrStandard.HDR10,
        display: Set<HdrStandard> = hdr10Display,
        capabilities: VideoAccelerationCapabilities = caps,
        reduced: Boolean = false,
        failure: MpvHdrGpuReason? = null,
    ) = mpvHdrGpuDecision(enabled, output, source, display, capabilities, reduced, failure)

    @Test fun hdr10OnAnHdr10DisplayPassesTheVulkanGate() {
        assertEquals(MpvHdrGpuDecision.GPU, decide())
        assertEquals(MpvHdrGpuDecision.GPU, decide(output = "vulkan"))
        assertEquals(MpvHdrGpuDecision.GPU, decide(output = "gpu"))
        // Older probes without colour spaces are not a negative result.
        assertEquals(MpvHdrGpuDecision.GPU, decide(capabilities = caps.copy(device = caps.device.copy(surfaceColorSpaces = null))))
    }

    @Test fun offOrSdrDoesNotConcernTheOption() {
        assertEquals(MpvHdrGpuDecision.NOT_APPLICABLE, decide(enabled = false))
        assertEquals(MpvHdrGpuDecision.NOT_APPLICABLE, decide(source = null))
    }

    @Test fun dolbyVisionHlgAndNativeHdr10PlusStayDirect() {
        assertEquals(MpvHdrGpuReason.DOLBY_VISION, decide(source = HdrStandard.DOLBY_VISION).reason)
        assertEquals(MpvHdrGpuReason.DOLBY_VISION, decide(source = HdrStandard.DOLBY_VISION, display = setOf(HdrStandard.HDR10, HdrStandard.DOLBY_VISION)).reason)
        assertEquals(MpvHdrGpuReason.NOT_HDR10, decide(source = HdrStandard.HLG).reason)
        assertEquals(MpvHdrGpuReason.NOT_HDR10, decide(source = HdrStandard.HDR10_PLUS, display = setOf(HdrStandard.HDR10, HdrStandard.HDR10_PLUS)).reason)
        // Without an HDR10+ display the panel only receives HDR10 anyway.
        assertTrue(decide(source = HdrStandard.HDR10_PLUS).gpu)
    }

    @Test fun deviceGateFailuresNameTheReason() {
        assertEquals(MpvHdrGpuReason.DISPLAY_NO_HDR10, decide(display = emptySet()).reason)
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(capabilities = caps.copy(checked = false)).reason)
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(capabilities = caps.copy(device = caps.device.copy(hostQueryReset = false))).reason)
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(capabilities = caps.copy(device = caps.device.copy(version = (1L shl 22) or (1L shl 12)))).reason)
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, decide(capabilities = caps.copy(mpvOutputBuilt = false)).reason)
        assertEquals(MpvHdrGpuReason.SWAPCHAIN_NO_HDR10, decide(capabilities = caps.copy(device = caps.device.copy(surfaceColorSpaces = listOf(0)))).reason)
    }

    @Test fun directOutputSparsamAndRuntimeFailuresFallBack() {
        assertEquals(MpvHdrGpuReason.OUTPUT_DIRECT, decide(output = "direct").reason)
        assertEquals(MpvHdrGpuReason.REDUCED_EFFECTS, decide(reduced = true).reason)
        assertEquals(MpvHdrGpuReason.OVERLOAD, decide(failure = MpvHdrGpuReason.OVERLOAD).reason)
        // The failure also switched the session to "Direkt"; the cause stays visible.
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, decide(output = "direct", failure = MpvHdrGpuReason.NOT_CONFIRMED).reason)
        assertFalse(decide(failure = MpvHdrGpuReason.NOT_CONFIRMED).gpu)
    }

    @Test fun settingsShowOnlyDeviceLevelReasons() {
        assertNull(mpvHdrGpuDeviceReason(caps, hdr10Display))
        assertNull(mpvHdrGpuDeviceReason(caps.copy(checked = false), hdr10Display))
        assertEquals(MpvHdrGpuReason.DISPLAY_NO_HDR10, mpvHdrGpuDeviceReason(caps, emptySet()))
        assertEquals(MpvHdrGpuReason.VULKAN_UNAVAILABLE, mpvHdrGpuDeviceReason(caps.copy(mpvOutputBuilt = false), hdr10Display))
    }

    @Test fun passthroughPlansGpuNextOnVulkan() {
        val plan = planMpvAcceleration("auto", "auto", caps, nativeHdrPreferred = false, hdrPassthrough = true)
        assertTrue(plan.vulkan)
        assertEquals("gpu-next", plan.vo)
        assertEquals("mediacodec", plan.decoder)
        assertNull(plan.fallback)
        val decode = caps.copy(mpvDecodeBuilt = true, device = caps.device.copy(profiles = listOf(VulkanDecodeProfile("hevc", 10, 3840, 2160))))
        assertEquals("vulkan", planMpvAcceleration("vulkan", "auto", decode, hdrPassthrough = true).decoder)
        assertEquals("mediacodec", planMpvAcceleration("vulkan", "mediacodec", decode, hdrPassthrough = true).decoder)
        // Without the gate the native HDR surface keeps priority.
        assertEquals("direct", planMpvAcceleration("vulkan", "auto", caps.copy(checked = false), nativeHdrPreferred = true, hdrPassthrough = true).output)
        assertEquals(mapOf("target-colorspace-hint" to "yes", "target-colorspace-hint-mode" to "source",
            "target-colorspace-hint-strict" to "yes"), MPV_HDR_PASSTHROUGH_OPTIONS.toMap())
    }

    @Test fun passthroughKeepsVulkanSelectableButNotOpenGl() {
        assertEquals(setOf("gpu"), disabledPlayerVideoOutputs("mpv", caps, nativeHdrPreferred = false, hdrGpu = true))
        assertEquals(setOf("gpu", "vulkan"), disabledPlayerVideoOutputs("mpv", caps, nativeHdrPreferred = true))
    }

    private val pqSurface = (6 shl 16) or (7 shl 22) or (1 shl 27)
    private fun facts(
        fileReady: Boolean = true,
        playing: Boolean = true,
        source: String? = "pq",
        gamma: String? = "pq",
        primaries: String? = "bt.2020",
        pixelFormat: String? = "rgb10a2",
        dataSpace: Int = pqSurface,
        swapchain: Boolean? = true,
    ) = MpvHdrPassthroughFacts(fileReady, playing, source, gamma, primaries, pixelFormat, dataSpace, swapchain)

    @Test fun monitorConfirmsPqTargetAndSurface() {
        val monitor = MpvHdrPassthroughMonitor(timeoutMs = 3_000L)
        assertNull(monitor.update(facts(gamma = "bt.1886"), 0L))
        assertNull(monitor.update(facts(), 1_000L))
        assertTrue(monitor.confirmed)
        // A short gap after the confirmation is tolerated, a sustained SDR target is not.
        assertNull(monitor.update(facts(gamma = null), 2_000L))
        assertNull(monitor.update(facts(), 2_500L))
        assertNull(monitor.update(facts(gamma = "bt.1886"), 3_000L))
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, monitor.update(facts(gamma = "bt.1886"), 6_000L))
    }

    @Test fun monitorFailsAfterTheTimeoutOfSteadyPlayback() {
        val monitor = MpvHdrPassthroughMonitor(timeoutMs = 3_000L)
        assertNull(monitor.update(facts(dataSpace = 0), 0L))
        // Pauses, seeks and startup do not count towards the deadline.
        assertNull(monitor.update(facts(dataSpace = 0, playing = false), 2_500L))
        assertNull(monitor.update(facts(dataSpace = 0), 3_000L))
        assertNull(monitor.update(facts(dataSpace = 0), 5_999L))
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, monitor.update(facts(dataSpace = 0), 6_000L))
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, monitor.update(facts(), 7_000L))
    }

    @Test fun monitorRejects8BitTargetsAndSdrSwapchains() {
        val eightBit = MpvHdrPassthroughMonitor(timeoutMs = 1_000L)
        eightBit.update(facts(pixelFormat = "rgba8"), 0L)
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, eightBit.update(facts(pixelFormat = "rgba8"), 1_000L))
        assertEquals(MpvHdrGpuReason.SWAPCHAIN_NO_HDR10, MpvHdrPassthroughMonitor().update(facts(swapchain = false), 0L))
        val sdr = MpvHdrPassthroughMonitor(timeoutMs = 1_000L)
        assertNull(sdr.update(facts(source = "bt.1886", gamma = "bt.1886"), 0L))
        assertNull(sdr.update(facts(source = "bt.1886", gamma = "bt.1886"), 10_000L))
        assertNull(sdr.update(facts(fileReady = false), 20_000L))
    }

    @Test fun pqOutputDropsGrainButKeepsHdrSafeShaders() {
        val options = MpvImageOptions(scaler = "ravu_lite_r3", sharpening = "cas", filmGrain = "light")
        val toneMapped = mpvImageShaderKeys(options, sdr = false, hdr = true, gpuNext = true)
        assertTrue("filmgrain_light_output" in toneMapped)
        val passthrough = mpvImageShaderKeys(options, sdr = false, hdr = true, gpuNext = true, pqOutput = true)
        assertEquals(toneMapped - "filmgrain_light_output", passthrough)
        assertTrue("ravu_lite_r3" in passthrough && "cas" in passthrough)
        // SDR → HDR conversion keeps its LUMA grain.
        assertTrue("filmgrain_light" in mpvImageShaderKeys(options, sdr = true, gpuNext = true, pqOutput = true))
        assertEquals(listOf("cas_medium"), mpvImageShaderKeys(MpvImageOptions(sharpening = "medium"), sdr = false, hdr = true,
            gpuNext = true, pqOutput = true))
    }

    @Test fun watchdogSamplesThePassthroughWithoutFilters() {
        assertNull(mpvRenderCheckSignature(emptyList(), emptyMap(), false, false, null, MpvImageOptions()))
        assertNotNull(mpvRenderCheckSignature(emptyList(), emptyMap(), false, false, null, MpvImageOptions(), hdrPassthrough = true))
        assertNotEquals(mpvRenderCheckSignature(listOf("cas"), emptyMap(), false, false, null, MpvImageOptions()),
            mpvRenderCheckSignature(listOf("cas"), emptyMap(), false, false, null, MpvImageOptions(), hdrPassthrough = true))
    }

    @Test fun panelHidesGrainOnlyForPqOutput() {
        val toneMapped = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = false, media3ScalerApplies = false, hdrInput = true)
        assertTrue(toneMapped.hdrShaders && !toneMapped.hdrPassthrough && toneMapped.toneMapping)
        val passthrough = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = false, media3ScalerApplies = false,
            hdrInput = true, hdrOutput = true)
        assertTrue(passthrough.hdrShaders && passthrough.hdrPassthrough && !passthrough.toneMapping)
        val sdrToHdr = playerPanelVideoVisibility("mpv", gpuFilters = true, shadersApply = true, media3ScalerApplies = false, hdrOutput = true)
        assertFalse(sdrToHdr.hdrPassthrough)
    }

    @Test fun backupCarriesTheOptionFromSchema64() {
        val source = AppPreferences(memoryPreferences())
        assertFalse(source.mpvHdrGpuEnabled)
        source.mpvHdrGpuEnabled = true
        val exported = SettingsBackupManager(source, TokenStore(memoryPreferences(), source)).exportJson()
        assertTrue(Regex("\"schemaVersion\"\\s*:\\s*64\\b").containsMatchIn(exported))
        val target = AppPreferences(memoryPreferences())
        val manager = SettingsBackupManager(target, TokenStore(memoryPreferences(), target))
        manager.importJson(exported)
        assertTrue(target.mpvHdrGpuEnabled)
        manager.importJson("""{"schemaVersion":63,"exportedAtEpochMs":0,"mpvHdrGpuEnabled":true}""")
        assertFalse(target.mpvHdrGpuEnabled)
    }

    @Test fun companionTreatsTheOptionAsADeviceSetting() {
        assertTrue("mpvHdrGpuEnabled" in CompanionBackupPolicy.classifiedFields)
        val json = CompanionBackupPolicy.json
        val target = SettingsBackup(exportedAtEpochMs = 1)
        val source = SettingsBackup(exportedAtEpochMs = 2, mpvHdrGpuEnabled = true)
        fun merged(source: SettingsBackup, device: Boolean) = json.decodeFromString<SettingsBackup>(
            CompanionBackupPolicy.merge(source, target, "preferences", overwrite = true, deviceSettings = device)).mpvHdrGpuEnabled
        assertTrue(merged(source, device = true))
        assertFalse(merged(source, device = false))
        assertFalse(merged(source.copy(schemaVersion = 63), device = true))
    }
}

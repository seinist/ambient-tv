package com.ambient.tv.ui.player

import com.ambient.tv.ui.player.backend.VideoAccelerationCapabilities
import com.ambient.tv.ui.player.backend.VulkanDeviceCapabilities
import com.ambient.tv.ui.player.backend.VulkanDecodeProfile
import org.junit.Assert.*
import org.junit.Test

class PlayerVideoSettingsTest {
    @Test fun saveOnlyUpdatesTheChosenBackendsDefaults() {
        val preferences = com.ambient.tv.data.local.AppPreferences(com.ambient.tv.data.local.memoryPreferences())
        preferences.mpvVideoOutputModeKey = "auto"
        preferences.mpvVideoDecoderKey = "auto"
        preferences.vlcVideoOutputModeKey = "auto"
        val selection = PlayerVideoSettings("gpu", "software", "android")
        selection.save(preferences, "mpv")
        assertEquals(PlayerVideoSettings("gpu", "software", "auto"), PlayerVideoSettings.from(preferences))
        selection.save(preferences, "vlc")
        assertEquals(selection, PlayerVideoSettings.from(preferences))
        PlayerVideoSettings().save(preferences, "media3")
        assertEquals(selection, PlayerVideoSettings.from(preferences))
    }

    private val capable = VideoAccelerationCapabilities(
        checked = true,
        device = VulkanDeviceCapabilities(render = true, hostQueryReset = true,
            version = (1L shl 22) or (2L shl 12), profiles = listOf(VulkanDecodeProfile("h264", 8, 1920, 1080))),
        mpvOutputBuilt = true, mpvDecodeBuilt = true, vlcOutputBuilt = true,
    )

    @Test fun unprobedOrUnsupportedVulkanCannotBeSelected() {
        for (backend in listOf("mpv", "vlc")) {
            assertEquals(setOf("vulkan"), disabledPlayerVideoOutputs(backend, VideoAccelerationCapabilities()))
            assertEquals(emptySet<String>(), disabledPlayerVideoOutputs(backend, capable))
        }
        assertEquals(setOf("vulkan"), disabledPlayerVideoOutputs("mpv", capable.copy(mpvOutputBuilt = false)))
        assertEquals(setOf("vulkan"), disabledPlayerVideoOutputs("vlc", capable.copy(vlcOutputBuilt = false)))
    }

    @Test fun decoderRequiresCompatibleOutputAndDevice() {
        for (output in listOf("direct", "gpu")) assertEquals(setOf("vulkan"), disabledPlayerVideoDecoders(output, capable))
        for (output in listOf("auto", "vulkan")) {
            assertTrue(disabledPlayerVideoDecoders(output, capable).isEmpty())
            assertEquals(setOf("vulkan"), disabledPlayerVideoDecoders(output, capable.copy(mpvDecodeBuilt = false)))
        }
    }

    @Test fun nativeHdrSurfaceDisablesGpuOutputsAndVulkanDecoder() {
        assertEquals(setOf("gpu", "vulkan"), disabledPlayerVideoOutputs("mpv", capable, nativeHdrPreferred = true))
        assertEquals(setOf("vulkan"), disabledPlayerVideoOutputs("vlc", capable, nativeHdrPreferred = true))
        assertEquals(setOf("vulkan"), disabledPlayerVideoDecoders("auto", capable, nativeHdrPreferred = true))
    }

    @Test fun outputChangeClearsOnlyAnIncompatibleDecoder() {
        val settings = PlayerVideoSettings("vulkan", "vulkan", "android")
        assertEquals("auto", settings.withMpvOutput("gpu").mpvDecoder)
        assertEquals("auto", settings.withMpvOutput("direct").mpvDecoder)
        assertEquals("vulkan", settings.withMpvOutput("auto").mpvDecoder)
        assertEquals("software", settings.copy(mpvDecoder = "software").withMpvOutput("gpu").mpvDecoder)
        assertEquals("android", settings.withMpvOutput("gpu").vlcOutput)
    }

    @Test fun overrideSurvivesMetadataRefreshButNotAnotherPlayback() {
        val first = PlaybackMetadata("player/one", "First", source = "server", mediaId = "one")
        assertTrue(sameVideoSettingsPlayback(first, first.copy(title = "New title", timelineSeekToken = 2)))
        assertFalse(sameVideoSettingsPlayback(first, first.copy(route = "player/two")))
        assertFalse(sameVideoSettingsPlayback(first, first.copy(source = "other")))
        assertFalse(sameVideoSettingsPlayback(first, first.copy(mediaId = "two")))
        assertFalse(sameVideoSettingsPlayback(null, first))
        assertFalse(sameVideoSettingsPlayback(first, null))
    }
}

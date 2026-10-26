package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSectionSummaryTest {
    @Test fun defaultsCountAsUnchanged() {
        listOf("filters", "motion", "color").forEach { key ->
            assertEquals(0, videoSectionChanges(key, MpvImageOptions(), null, null))
            assertEquals(0, videoSectionChanges(key, null, VlcImageOptions(), null))
            assertEquals(0, videoSectionChanges(key, null, null, Media3ImageOptions()))
        }
    }

    @Test fun changedSettingsAreCountedPerArea() {
        val mpv = MpvImageOptions(deband = "off", sharpening = "cas", brightness = 2, svpMode = "smooth")
        assertEquals(2, videoSectionChanges("filters", mpv, null, null))
        assertEquals(1, videoSectionChanges("color", mpv, null, null))
        assertEquals(1, videoSectionChanges("motion", mpv, null, null))
        assertEquals(1, videoSectionChanges("filters", null, VlcImageOptions(denoise = "light"), null))
        assertEquals(2, videoSectionChanges("color", null, null, Media3ImageOptions(contrast = 3, saturation = -1)))
        assertEquals(3, videoSectionChanges("color", null, null, Media3ImageOptions(contrast = 3, saturation = -1, gamma = 4)))
        assertEquals(2, videoSectionChanges("filters", null, null, Media3ImageOptions(deband = "light", sharpening = "cas")))
    }

    @Test fun everyAreaHasATitle() {
        listOf("renderer", "scaling", "filters", "motion", "color", "output", "unknown").forEach { videoSectionTitle(it) }
    }
}

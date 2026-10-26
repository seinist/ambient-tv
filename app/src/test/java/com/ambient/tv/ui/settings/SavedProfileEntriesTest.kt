package com.ambient.tv.ui.settings

import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedProfileEntriesTest {
    @Test
    fun `entries are grouped by category with tvheadend separate`() {
        val entries = savedProfileEntries(
            resolution = mapOf("width:1920" to ResolutionVideoProfile("mpv"), "live:width:720" to ResolutionVideoProfile("vlc")),
            titles = mapOf(
                "plex:5:abcde:123" to TitleVideoSettings(audioDelayMs = 120, savedAt = 2),
                "live-channel:3:tvh:42" to TitleVideoSettings(rendererKey = "vlc", savedAt = 1),
            ),
            scoped = mapOf("series:jellyfin:3:srv:xyz" to TitleVideoSettings(mpvDecoder = "hw", savedAt = 3)),
        )
        val byKey = entries.associateBy { it.key }
        assertEquals(SavedProfileCategory.RESOLUTION, byKey.getValue("width:1920").category)
        assertEquals(SavedProfileCategory.TVHEADEND, byKey.getValue("live:width:720").category)
        assertEquals(SavedProfileCategory.TVHEADEND, byKey.getValue("live-channel:3:tvh:42").category)
        assertEquals(SavedProfileCategory.TITLE, byKey.getValue("plex:5:abcde:123").category)
        assertEquals("123", byKey.getValue("plex:5:abcde:123").label)
        assertEquals(listOf(SavedProfilePart.AUDIO_DELAY), byKey.getValue("plex:5:abcde:123").parts)
        assertEquals(SavedProfileCategory.SERIES, byKey.getValue("series:jellyfin:3:srv:xyz").category)
        assertEquals("jellyfin", byKey.getValue("series:jellyfin:3:srv:xyz").source)
        assertEquals(listOf(SavedProfilePart.OUTPUT), byKey.getValue("series:jellyfin:3:srv:xyz").parts)
    }
}

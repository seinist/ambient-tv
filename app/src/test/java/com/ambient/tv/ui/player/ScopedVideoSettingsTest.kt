package com.ambient.tv.ui.player

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.domain.model.MediaTag
import org.junit.Assert.*
import org.junit.Test

class ScopedVideoSettingsTest {
    private val episode = PlaybackVideoScope(serverId = "srv", seasonId = "s1", seriesId = "show",
        collections = listOf(MediaTag("c1", "Old"), MediaTag("c2", "New"), MediaTag(null, "No id")), episode = true)

    @Test fun layersGoNarrowToBroadAndTheNewestCollectionWins() {
        val scoped = mapOf(scopedVideoKey("collection", "plex", "srv", "c1") to TitleVideoSettings(rendererKey = "vlc", savedAt = 1),
            scopedVideoKey("collection", "plex", "srv", "c2") to TitleVideoSettings(rendererKey = "mpv", savedAt = 5))
        val layers = videoScopeLayers("plex:1", "plex", episode, scoped)
        assertEquals(listOf(VideoSettingsScope.TITLE, VideoSettingsScope.SEASON, VideoSettingsScope.SERIES,
            VideoSettingsScope.COLLECTION, VideoSettingsScope.COLLECTION), layers.map { it.scope })
        assertEquals("New", layers[3].label)
        assertEquals("mpv", mergeVideoScopes(layers.map { scoped[it.key] })?.rendererKey)
    }

    @Test fun moviesHaveNoSeasonOrSeriesAndLiveOrUnknownTitlesOnlyTheTitle() {
        val movie = PlaybackVideoScope(collections = listOf(MediaTag("c", "Saga")))
        assertEquals(listOf(VideoSettingsScope.TITLE, VideoSettingsScope.COLLECTION),
            videoScopeLayers("jellyfin:j:1", "jellyfin", movie, emptyMap()).map { it.scope })
        assertEquals(listOf(VideoSettingsScope.TITLE), videoScopeLayers("x:1", "x", null, emptyMap()).map { it.scope })
        assertTrue(videoScopeLayers(null, "x", episode, emptyMap()).isEmpty())
    }

    @Test fun titleBeatsSeasonBeatsSeriesBeatsCollectionFieldByField() {
        val title = TitleVideoSettings(audioDelayMs = 40, mpvOverloadContinueSignature = "sig")
        val season = TitleVideoSettings(mpv = MpvImageOptions(sharpening = "cas"), audioDelayMs = 80)
        val series = TitleVideoSettings(rendererKey = "mpv", mpv = MpvImageOptions(), subtitleDelayMs = 100)
        val collection = TitleVideoSettings(rendererKey = "vlc", vlcOutput = "android")
        val merged = mergeVideoScopes(listOf(title, season, series, collection))!!
        assertEquals(40, merged.audioDelayMs)
        assertEquals("cas", merged.mpv?.sharpening)
        assertEquals("mpv", merged.rendererKey)
        assertEquals(100, merged.subtitleDelayMs)
        assertEquals("android", merged.vlcOutput)
        assertEquals("sig", merged.mpvOverloadContinueSignature)
        assertNull(mergeVideoScopes(listOf(null, null)))
        // Signatures never come from a broader scope.
        assertNull(mergeVideoScopes(listOf(null, TitleVideoSettings(rendererKey = "mpv")))!!.mpvOverloadContinueSignature)
    }

    @Test fun savingForABroaderScopeClearsOnlyTheMaskingFieldsOfNarrowerOnes() {
        val title = TitleVideoSettings(rendererKey = "vlc", audioDelayMs = 30, subtitleDelayMs = 10)
        val season = TitleVideoSettings(rendererKey = "mpv", audioDelayMs = 0)
        val cleared = title.withoutFieldsOf(season)
        assertNull(cleared.rendererKey)
        assertNull(cleared.audioDelayMs)
        assertEquals(10, cleared.subtitleDelayMs)
    }

    @Test fun originsNameTheScopeTheWidthProfileOrTheDefault() {
        val layers = videoScopeLayers("plex:1", "plex", episode, emptyMap())
        val values = listOf(layers[0] to TitleVideoSettings(audioDelayMs = 20), layers[1] to null,
            layers[2] to TitleVideoSettings(mpv = MpvImageOptions()), layers[3] to TitleVideoSettings(rendererKey = "mpv"))
        val origins = videoSettingOrigins(values, ResolutionVideoProfile("vlc", vlcOutput = "android"))
        assertEquals(VideoSettingOrigin.COLLECTION, origins.renderer)
        assertEquals(VideoSettingOrigin.SERIES, origins.picture)
        assertEquals(VideoSettingOrigin.TITLE, origins.delay)
        assertEquals(VideoSettingOrigin.RESOLUTION, origins.output)
        assertEquals(VideoSettingOrigin.DEFAULT, origins.audio)
        assertEquals(VideoSettingOrigin.DEFAULT, videoSettingOrigins(emptyList(), null).renderer)
    }

    @Test fun keysCarryTheServerAndOldPlexTitleKeysStayDistinct() {
        assertNotEquals(scopedVideoKey("season", "plex", "a", "1"), scopedVideoKey("season", "plex", "b", "1"))
        assertNotEquals(scopedVideoKey("season", "jellyfin", "a:b", "c"), scopedVideoKey("season", "jellyfin", "a", "b:c"))
        assertEquals("plex:3:srv:42", TitleVideoSettingsStore.titleKey("plex", "42", "srv"))
        assertEquals("plex:42", TitleVideoSettingsStore.titleKey("plex", "42", null))
        assertEquals("jellyfin:j:42", TitleVideoSettingsStore.titleKey("jellyfin", "j:42", "srv"))
        val plex = PlaybackMetadata("r", "", source = "plex", mediaId = "42", videoScope = PlaybackVideoScope(serverId = "srv"))
        assertEquals("plex:3:srv:42", TitleVideoSettingsStore.key(plex))
        assertTrue(TitleVideoSettings.isScopedKey(scopedVideoKey("series", "plex", "srv", "1")))
        assertFalse(TitleVideoSettings.isScopedKey("plex:42"))
    }
}

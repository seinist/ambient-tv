package com.ambient.tv.ui.player

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.TitleVideoSettings
import org.junit.Assert.*
import org.junit.Test

/** Audit 25.10.: capture, remember target and player choice across title/season/series scopes. */
class ScopedVideoCaptureTest {
    private val sharp = MpvImageOptions(sharpening = "medium")
    private val soft = MpvImageOptions(denoise = "high")
    private val default = MpvImageOptions()

    private fun mpv(value: MpvImageOptions?, default: MpvImageOptions = this.default) = ObservedVideoField(VideoScopeFields.MPV, value, default)
    private fun delay(value: Int?) = ObservedVideoField(VideoScopeFields.AUDIO_DELAY, value, null)
    private fun subDelay(value: Int?) = ObservedVideoField(VideoScopeFields.SUBTITLE_DELAY, value, null)

    @Test fun titleTargetStoresOnlyWhatDiffersFromTheInheritedValue() {
        val series = TitleVideoSettings(mpv = sharp, savedAt = 1)
        // Same as the series value: the title keeps nothing.
        assertEquals(listOf(null, null, series), captureIntoScopes(listOf(null, null, series), 0, listOf(mpv(sharp))))
        // Different: the title overrides.
        assertEquals(soft, captureIntoScopes(listOf(null, null, series), 0, listOf(mpv(soft)))[0]?.mpv)
        // Equal to the default without a scope: nothing.
        assertNull(captureIntoScopes(listOf(null), 0, listOf(mpv(default)))[0])
        // Legacy snapshot keeps every value.
        assertEquals(default, captureIntoScopes(listOf(null), 0, listOf(mpv(default)), fullSnapshot = true)[0]?.mpv)
    }

    @Test fun broaderTargetTakesOnlyFieldsChangedInThisSession() {
        val title = TitleVideoSettings(mpv = soft, mpvOutput = "gpu", savedAt = 1)
        val layers = listOf(title, null, null)
        // Nothing changed since playback started (baseline = layers): the untouched title override does not spread.
        // Changes of this session that the automatic capture already put on the title do spread
        // (sessionChangesAlreadySavedOnTheTitleMoveToTheSeries).
        val unchanged = captureIntoScopes(layers, 2, listOf(mpv(soft), ObservedVideoField(VideoScopeFields.MPV_OUTPUT, "gpu", "auto")))
        assertSame(title, unchanged[0])
        assertNull(unchanged[2])
        // Picture changed to sharp: series stores it, the title stops masking exactly that field.
        val changed = captureIntoScopes(layers, 2, listOf(mpv(sharp), ObservedVideoField(VideoScopeFields.MPV_OUTPUT, "gpu", "auto")))
        assertEquals(sharp, changed[2]?.mpv)
        assertNull(changed[2]?.mpvOutput)
        assertNull(changed[0]?.mpv)
        assertEquals("gpu", changed[0]?.mpvOutput)
        // Changed back to the default: the series drops it and the title no longer masks it.
        val reset = captureIntoScopes(listOf(title, null, TitleVideoSettings(mpv = sharp)), 2, listOf(mpv(default)))
        assertNull(reset[0]?.mpv)
        assertNull(reset[2])
    }

    @Test fun sessionChangesAlreadySavedOnTheTitleMoveToTheSeries() {
        // Typical flow: picture changed (the automatic capture stored it on the episode), then
        // "remember for" switched to the series. Baseline = layers when playback started.
        val start = listOf<TitleVideoSettings?>(null, null, null)
        val autoSaved = listOf<TitleVideoSettings?>(TitleVideoSettings(mpv = sharp, savedAt = 1), null, null)
        val moved = captureIntoScopes(autoSaved, 2, listOf(mpv(sharp)), baseline = start)
        assertEquals(sharp, moved[2]?.mpv)
        assertNull(moved[0])
        // Next tick: stable, nothing changes any more.
        assertEquals(moved, captureIntoScopes(moved, 2, listOf(mpv(sharp)), baseline = start))
        // The next episode gets the series value.
        assertEquals(sharp, mergeVideoScopes(listOf(null, null, moved[2]))?.mpv)
    }

    @Test fun changedBackToTheStartValueRestoresTheStartLayers() {
        val oldTitle = TitleVideoSettings(mpv = soft, savedAt = 1)
        val start = listOf<TitleVideoSettings?>(oldTitle, null, null)
        // Changed to sharp while "series" is chosen: the series holds it, the episode stops masking it.
        val changed = captureIntoScopes(start, 2, listOf(mpv(sharp)), baseline = start)
        assertEquals(sharp, changed[2]?.mpv)
        assertNull(changed[0])
        // Back to the start value: the series forgets it and the episode keeps its old value again.
        val back = captureIntoScopes(changed, 2, listOf(mpv(soft)), baseline = start)
        assertEquals(soft, back[0]?.mpv)
        assertNull(back[2])
    }

    @Test fun rendererChosenInThisSessionMovesWithTheScopeSwitch() {
        val start = listOf<TitleVideoSettings?>(null, null, null)
        val chosen = listOf<TitleVideoSettings?>(TitleVideoSettings(rendererKey = "vlc"), null, null)
        assertEquals(listOf(null, null, "vlc"), sessionRendererInScopes(chosen, start, 2).map { it?.rendererKey })
        // A player from an earlier session is no change of this session: it stays on the episode.
        assertSame(chosen, sessionRendererInScopes(chosen, chosen, 2))
        // Title target or a player already stored on the target: unchanged.
        assertSame(chosen, sessionRendererInScopes(chosen, start, 0))
        val onSeries = listOf<TitleVideoSettings?>(null, null, TitleVideoSettings(rendererKey = "mpv"))
        assertSame(onSeries, sessionRendererInScopes(onSeries, start, 2))
    }

    @Test fun delaysAlwaysStayOnTheTitle() {
        val result = captureIntoScopes(listOf(null, null, null), 2, listOf(delay(120), subDelay(-300), mpv(sharp)))
        assertEquals(120, result[0]?.audioDelayMs)
        assertEquals(-300, result[0]?.subtitleDelayMs)
        assertNull(result[2]?.audioDelayMs)
        assertNull(result[2]?.subtitleDelayMs)
        assertEquals(sharp, result[2]?.mpv)
    }

    @Test fun titleResetKeepsTheSeriesValues() {
        // H1: after "forget title" the panel loads the merged scopes, not the bare defaults.
        val series = TitleVideoSettings(mpv = sharp, audioDelayMs = null, savedAt = 1)
        val season = TitleVideoSettings(mpvOutput = "gpu-next", savedAt = 1)
        val merged = mergeVideoScopes(listOf(null, season, series))
        assertEquals(sharp, merged?.mpv)
        assertEquals("gpu-next", merged?.mpvOutput)
    }

    @Test fun playerChoiceClearsNarrowerPlayersEvenWhenReset() {
        val layers = listOf(TitleVideoSettings(rendererKey = "vlc"), TitleVideoSettings(rendererKey = "media3"), null)
        val chosen = rememberRendererInScopes(layers, 2, "mpv")
        assertEquals(listOf(null, null, "mpv"), chosen.map { it?.rendererKey })
        // M6: "default" (null) on the series also removes narrower players, so the reset takes effect.
        val reset = rememberRendererInScopes(listOf(TitleVideoSettings(rendererKey = "vlc"), null, TitleVideoSettings(rendererKey = "mpv")), 2, null)
        assertEquals(listOf(null, null, null), reset.map { it?.rendererKey })
        // Title target leaves broader scopes alone.
        assertEquals("mpv", rememberRendererInScopes(listOf(null, TitleVideoSettings(rendererKey = "mpv")), 0, "vlc")[1]?.rendererKey)
    }

    private fun episode(title: String, season: String) = listOf(
        VideoScopeLayer(VideoSettingsScope.TITLE, "plex:$title"),
        VideoScopeLayer(VideoSettingsScope.SEASON, scopedVideoKey("season", "plex", "srv", season)),
        VideoScopeLayer(VideoSettingsScope.SERIES, scopedVideoKey("series", "plex", "srv", "show")),
    )

    @Test fun seasonAndSeriesChoicesCarryOverToFurtherEpisodes() {
        val choices = VideoScopeChoices()
        val e1 = episode("1", "s1")
        assertEquals(0, choices.index(e1))
        choices.choose(e1, e1[1])
        // Next episode of the same season and the first of the next season: the season choice follows.
        assertEquals(1, choices.index(episode("2", "s1")))
        assertEquals(1, choices.index(episode("9", "s2")))
        choices.choose(e1, e1[2])
        assertEquals(2, choices.index(episode("3", "s1")))
        // Another title (a movie) starts with the title itself.
        assertEquals(0, choices.index(listOf(VideoScopeLayer(VideoSettingsScope.TITLE, "plex:m"))))
        // Choosing the title again resets the series.
        choices.choose(e1, e1[0])
        assertEquals(0, choices.index(episode("4", "s1")))
    }

    @Test fun choicesAreRestoredAndReportedForSaving() {
        var saved: Map<String, VideoScopeChoice> = emptyMap()
        val e1 = episode("1", "s1")
        val choices = VideoScopeChoices(onChange = { saved = it })
        choices.choose(e1, e1[2])
        assertEquals(VideoScopeChoice(VideoSettingsScope.SERIES, e1[2].key), saved[e1[2].key])
        // After a restart the saved choice still applies to further episodes.
        val restored = VideoScopeChoices(initial = saved.mapValues { decodeVideoScopeChoice(encodeVideoScopeChoice(it.value))!! })
        assertEquals(2, restored.index(episode("5", "s2")))
        // Back to the title removes the entry; an unchanged choice is not saved again.
        var calls = 0
        val counted = VideoScopeChoices(initial = saved, onChange = { calls++; saved = it })
        counted.choose(e1, e1[2])
        assertEquals(0, calls)
        counted.choose(e1, e1[0])
        assertEquals(1, calls)
        assertTrue(saved.isEmpty())
    }

    @Test fun storedChoiceFormat() {
        val choice = VideoScopeChoice(VideoSettingsScope.SEASON, "season:plex:3:srv:12")
        assertEquals("SEASON:season:plex:3:srv:12", encodeVideoScopeChoice(choice))
        assertEquals(choice, decodeVideoScopeChoice(encodeVideoScopeChoice(choice)))
        assertNull(decodeVideoScopeChoice("TITLE:plex:1"))
        assertNull(decodeVideoScopeChoice("NOPE:x"))
        assertNull(decodeVideoScopeChoice("SERIES:"))
        assertNull(decodeVideoScopeChoice("garbage"))
    }

    @Test fun diagnosticLineNamesLayersTargetWidthAndOrigins() {
        val layers = episode("1", "s1").zip(listOf(null, null, TitleVideoSettings(mpv = sharp)))
        val origins = videoSettingOrigins(layers, null)
        val line = videoScopeDiagnosticLine("plex:1", layers, 2, "width:1916", "width:1920", origins)
        assertTrue(line, line.contains("layers=[title=empty,season=empty,series=set]"))
        assertTrue(line, line.contains("remember=series"))
        assertTrue(line, line.contains("width=width:1916->width:1920(similar preset)"))
        assertTrue(line, line.contains("picture=series"))
        assertTrue(line, line.contains("player=default"))
        assertTrue(videoScopeDiagnosticLine("plex:1", layers, 0, null, null, origins).contains("width=unknown"))
        assertTrue(videoScopeDiagnosticLine("plex:1", layers, 0, "width:1280", null, origins).contains("width=width:1280(no preset)"))
    }

    @Test fun scopeIndexFallsBackToTheTitle() {
        val layers = episode("1", "s1")
        assertEquals(0, rememberScopeIndex(layers, null))
        assertEquals(0, rememberScopeIndex(layers, VideoScopeChoice(VideoSettingsScope.COLLECTION, "collection:plex:3:srv:c")))
        assertEquals(2, rememberScopeIndex(layers, VideoScopeChoice(VideoSettingsScope.SERIES, layers[2].key)))
        assertEquals(layers[2].key, rememberScopeAnchor(layers))
        assertEquals("plex:m", rememberScopeAnchor(listOf(VideoScopeLayer(VideoSettingsScope.TITLE, "plex:m"))))
    }
}

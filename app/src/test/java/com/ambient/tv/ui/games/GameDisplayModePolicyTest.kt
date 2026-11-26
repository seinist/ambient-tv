package com.ambient.tv.ui.games

import com.ambient.tv.games.GameDisplayProfile
import com.ambient.tv.games.GameDisplayScope
import com.ambient.tv.games.GameSettingsState
import com.ambient.tv.games.displayProfile
import com.ambient.tv.games.withDisplayProfile
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import org.junit.Assert.*
import org.junit.Test

class GameDisplayModePolicyTest {
    private fun mode(id: Int, w: Int, h: Int, hz: Float, hdr: Set<Int>? = null) =
        SupportedDisplayMode(DisplayRefreshRateMatcher.modeKey(w, h, hz), id, w, h, hz, hdr)

    private val uhd60 = mode(1, 3840, 2160, 60f, setOf(2))
    private val uhd50 = mode(2, 3840, 2160, 50f, setOf(2))
    private val hd60 = mode(3, 1920, 1080, 60f, setOf(2))
    private val hd50 = mode(4, 1920, 1080, 50f, setOf(2))
    private val hd120 = mode(5, 1920, 1080, 120f, emptySet())
    private val p720 = mode(6, 1280, 720, 60f, emptySet())
    private val p480 = mode(7, 720, 480, 60f, emptySet())
    private val hd5994 = mode(8, 1920, 1080, 59.94f, setOf(2))
    private val all = listOf(uhd60, uhd50, hd60, hd50, hd120, p720, p480, hd5994)
    private fun format(fps: Float? = 60f, w: Int = 256, h: Int = 224) =
        GameDisplayFormat(fps, GameContentFrame(w, h))

    @Test fun disabledIsDefaultAndNeverRequestsMode() {
        assertEquals(GameDisplayProfile(), GameSettingsState().displayProfile("snes9x", "game"))
        assertNull(selectGameDisplayMode(all, uhd60, format(), GameDisplayProfile(), false))
    }

    @Test fun palMatchesAtOriginalResolution() {
        assertEquals(uhd50, selectGameDisplayMode(all, uhd60, format(50f), GameDisplayProfile(matchRate = true), false))
    }

    @Test fun fractionalRateAndDoubleRateUseCoreCadenceNotVideoPulldown() {
        assertEquals(hd5994, selectGameDisplayMode(all, hd60, format(59.94f), GameDisplayProfile(matchRate = true), false))
        assertEquals(hd120, selectGameDisplayMode(all, hd60, format(), GameDisplayProfile(matchRate = true, preferDouble = true), false))
        assertNull(selectGameDisplayMode(listOf(hd60), hd60, format(24f), GameDisplayProfile(matchRate = true), false))
    }

    @Test fun nativePixelsDoNotForce480pAndPerformanceIs1080p() {
        assertEquals(p720, selectGameDisplayMode(all, uhd60, format(),
            GameDisplayProfile(true, GameDisplayProfile.GAME), false))
        assertEquals(hd60, selectGameDisplayMode(all, uhd60, format(),
            GameDisplayProfile(true, GameDisplayProfile.PERFORMANCE), false))
        assertEquals(uhd60, selectGameDisplayMode(all, hd60, format(),
            GameDisplayProfile(true, GameDisplayProfile.DISPLAY), false))
    }

    @Test fun cadenceWinsOverResolutionAndUnknownIsConservative() {
        assertEquals(hd50, selectGameDisplayMode(listOf(p720, hd50, uhd60), uhd60, format(50f),
            GameDisplayProfile(true, GameDisplayProfile.GAME), false))
        assertNull(selectGameDisplayMode(all, uhd60, format(null),
            GameDisplayProfile(true, GameDisplayProfile.GAME), false))
        assertEquals(uhd50, selectGameDisplayMode(all, uhd60, GameDisplayFormat(50f, null),
            GameDisplayProfile(true, GameDisplayProfile.GAME), false))
    }

    @Test fun manualIsExactAndRespectsDeviceWhitelistAndHdr() {
        val manual = GameDisplayProfile(resolution = hd120.key)
        assertEquals(hd120, selectGameDisplayMode(all, uhd60, format(null), manual, false))
        assertNull(selectGameDisplayMode(all, uhd60, format(), manual, false, setOf(hd60.key)))
        assertNull(selectGameDisplayMode(all, uhd60, format(), manual, false, requiredHdrType = 2))
        assertEquals(hd60, selectGameDisplayMode(all, hd60, format(),
            GameDisplayProfile(true, preferDouble = true), false, requiredHdrType = 2))
        assertNull(selectGameDisplayMode(all, uhd60, format(), GameDisplayProfile(resolution = "1920x1080@90.000"), false))
    }

    @Test fun handheldNeverChangesPhysicalResolution() {
        assertEquals(hd50, selectGameDisplayMode(all, hd60, format(50f),
            GameDisplayProfile(true, GameDisplayProfile.DISPLAY), true))
        assertNull(selectGameDisplayMode(all, hd60, format(), GameDisplayProfile(resolution = uhd60.key), true))
    }

    @Test fun resolutionOnlyPreservesRefreshRate() {
        assertEquals(hd50, selectGameDisplayMode(all, uhd50, format(60f),
            GameDisplayProfile(resolution = GameDisplayProfile.PERFORMANCE), false))
    }

    @Test fun scopesInheritAndExplicitOffOverridesGlobal() {
        val global = GameDisplayProfile(true, GameDisplayProfile.PERFORMANCE)
        val core = GameDisplayProfile(true, GameDisplayProfile.DISPLAY, true)
        val state = GameSettingsState().withDisplayProfile(GameDisplayScope.GLOBAL, "", "", global)
            .withDisplayProfile(GameDisplayScope.CORE, "snes9x", "", core)
            .withDisplayProfile(GameDisplayScope.GAME, "snes9x", "pal-game", GameDisplayProfile())
        assertEquals(global, state.displayProfile("other", "game"))
        assertEquals(core, state.displayProfile("snes9x", "other-game"))
        assertEquals(GameDisplayProfile(), state.displayProfile("snes9x", "pal-game"))
        assertEquals(core, state.withDisplayProfile(GameDisplayScope.GAME, "snes9x", "pal-game", null)
            .displayProfile("snes9x", "pal-game"))
        assertEquals(state.displayProfile("snes9x", "pal-game"),
            GameSettingsState(state.values.toMap()).displayProfile("snes9x", "pal-game"))
    }

    @Test fun invalidSyncedValuesFallbackWithoutRequests() {
        val state = GameSettingsState(mapOf("display.global" to "1|bad|0",
            "display.game.game" to "1|1920x1080@999.999|0"))
        assertEquals(GameDisplayProfile(), state.displayProfile("core", "game"))
        assertNull(GameDisplayProfile.decode("1|1920x1080@NaN|0"))
    }
}

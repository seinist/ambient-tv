package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import org.junit.Assert.*
import org.junit.Test

class SvpPolicyTest {
    @Test fun changedDisplayTargetRestartsActiveSvpOnly() {
        assertTrue(svpTargetChanged("light", 24.0, 60.0))
        assertTrue(svpTargetChanged("quality", 60.0, 59.94))
        assertFalse(svpTargetChanged("off", 24.0, 60.0))
        assertFalse(svpTargetChanged("light", 60.0, 60.000004))
    }
    @Test fun savedChoicesNeverBypassPerformanceGate() {
        val mpv = MpvImageOptions(svpMode = "quality")
        val vlc = VlcImageOptions(svpMode = "auto")
        for (low in listOf(false, true)) {
            assertEquals("off", effectiveMpvImageOptions(mpv, low).svpMode)
            assertEquals("off", effectiveVlcImageOptions(vlc, low).svpMode)
        }
        assertEquals("quality", effectiveMpvImageOptions(mpv, false, true).svpMode)
        assertEquals("light", effectiveVlcImageOptions(vlc, false, true).svpMode) // stored "auto" reads as light
        assertEquals("off", effectiveMpvImageOptions(mpv, true, true).svpMode)
        assertEquals("quality", mpv.svpMode) // No destructive preference rewrite.
    }
    @Test fun ratesAreValidatedWithoutUpconvertingHighFpsOrUnknownSources() {
        assertNull(mpvSvpFilter("off", 24.0, 60.0))
        assertNull(mpvSvpFilter("auto", null, 60.0))
        assertNull(mpvSvpFilter("auto", Double.NaN, 60.0))
        assertNull(mpvSvpFilter("auto", 60.0, 59.94))
        assertNull(mpvSvpFilter("auto", 24.0, 144.0))
        assertTrue(mpvSvpFilter("light", 24000.0/1001, 60000.0/1001)!!.contains("preset=1"))
        assertTrue(mpvSvpFilter("auto", 24000.0/1001, 60000.0/1001)!!.contains("preset=1"))
    }
    @Test fun vlcUsesCopyBackWithoutForcingSoftwareDecode() {
        val options = VlcImageOptions(svpMode = "light", softwareProcessing = false, denoise = "medium")
        val media = vlcImageMediaOptions(options, 59.94)
        assertTrue(":no-mediacodec-dr" in media)
        assertTrue(media.any { "ambientsvp{fps=59.94" in it })
        assertFalse(media.any { "hqdn3d" in it })
        assertFalse(vlcImageMediaOptions(effectiveVlcImageOptions(options, false), 60.0).any { "ambientsvp" in it })
        assertEquals("off", MpvImageOptions(svpMode = "bad").normalized().svpMode)
        assertEquals("off", VlcImageOptions(svpMode = "bad").normalized().svpMode)
    }

    @Test fun customParametersReachBothPlayersAndKeepFractionalCadence() {
        val tuning = com.ambient.tv.data.local.SvpOptions(rateDivisor = 2, algorithm = 21,
            artifacts = 3, scene = 1, block = 8, overlap = 4, pel = 2, search = 1, backend = 1, threads = 2)
        val display = 60000.0 / 1001
        val target = svpOutputFps(display, tuning)!!
        assertEquals(30000.0 / 1001, target, 0.000001)
        val mpv = mpvSvpFilter("balanced", 24000.0 / 1001, display, tuning)!!
        val vlc = vlcImageMediaOptions(VlcImageOptions(svpMode = "balanced", svp = tuning), target).joinToString()
        for (arg in listOf("algo=21", "artifacts=3", "scene=1", "block=8", "overlap=4", "pel=2", "search=1", "backend=1", "threads=2")) {
            assertTrue(mpv.contains(arg))
            assertTrue(vlc.contains(arg))
        }
        assertNull(mpvSvpFilter("balanced", 30.0, 60.0, tuning))
        assertNotEquals(mpv, mpvSvpFilter("balanced", 24000.0 / 1001, display, tuning.copy(algorithm = 23)))
    }

    @Test fun persistedOverridesRoundTripAndOldPreferencesKeepDefaults() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val options = MpvImageOptions(svpMode = "balanced", svp = com.ambient.tv.data.local.SvpOptions(block = 8, algorithm = 23))
        assertEquals(options, json.decodeFromString(MpvImageOptions.serializer(), json.encodeToString(MpvImageOptions.serializer(), options)))
        assertEquals(com.ambient.tv.data.local.SvpOptions(), json.decodeFromString(MpvImageOptions.serializer(), "{} ").svp)
        assertEquals(options.svp, effectiveMpvImageOptions(options, true, false).svp)
        val bad = com.ambient.tv.data.local.SvpOptions(rateDivisor = 0, algorithm = 999, block = -1, threads = 99)
        assertEquals(com.ambient.tv.data.local.SvpOptions(), bad.normalized())
    }

    @Test fun smallBlocksCannotRequestOddCpuOverlap() {
        val unsafe = com.ambient.tv.data.local.SvpOptions(block = 8, overlap = 2)
        assertEquals(3, unsafe.normalized().overlap)
        assertTrue(mpvSvpFilter("light", 24.0, 60.0, unsafe)!!.contains("overlap=3"))
        assertTrue(vlcImageMediaOptions(VlcImageOptions(svpMode = "light", svp = unsafe), 60.0)
            .joinToString().contains("overlap=3"))
        assertEquals(2, unsafe.copy(block = 16).normalized().overlap)
    }

    @Test fun qualityRecoveryRemovesExpensiveOverridesButKeepsOutputAndBackend() {
        val tuning = com.ambient.tv.data.local.SvpOptions(rateDivisor = 2, algorithm = 23,
            block = 8, overlap = 4, pel = 4, search = 3, artifacts = 3, backend = 1, threads = 2)
        val live = MpvImageOptions(svpMode = "quality", svp = tuning)
        val update = mpvSvpLevelAfterHeavyFailure(live, live)!!
        val probe = svpProbeOptions(tuning)
        assertEquals("light", update.live.svpMode)
        assertEquals(com.ambient.tv.data.local.SvpOptions(rateDivisor = 2, backend = 1, threads = 2), probe)
        assertEquals(probe, update.live.svp)
        assertEquals(live, update.stored)
        assertEquals(probe, mpvSvpLevelAfterHeavyFailure(live, live, saveImageDefaults = true)!!.stored.svp)
    }
}

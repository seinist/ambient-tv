package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.DeinterlaceCatalog
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class DeinterlacePolicyTest {
    @Test fun vlcExplicitMethodsGetReadableFramesWithoutDisablingHardwareDecode() {
        assertEquals(listOf("--deinterlace=1", "--deinterlace-mode=phosphor", "--no-mediacodec-dr"), vlcDeinterlaceOptions("on", "phosphor"))
        assertFalse(vlcDeinterlaceOptions("off", "phosphor").contains("--no-mediacodec-dr"))
        assertFalse(vlcDeinterlaceOptions("auto", "auto").contains("--no-mediacodec-dr"))
    }
    @Test fun autoDoesNotForceProgressiveFramesThroughExplicitFilters() {
        for (key in listOf("bwdif", "yadif2x", "w3fdif", "estdif2x", "nnedi")) {
            assertTrue(mpvDeinterlaceGraph(key, true, "/data/user/0/app/cache/weights.bin")!!.contains("deint=interlaced"))
            assertTrue(mpvDeinterlaceGraph(key, false, "/data/user/0/app/cache/weights.bin")!!.contains("deint=all"))
        }
    }

    @Test fun singleRateAndDoubleRateUseEachFiltersActualParameterVocabulary() {
        assertEquals("w3fdif=mode=frame:parity=auto:deint=all", mpvDeinterlaceGraph("w3fdif", false))
        assertEquals("estdif=mode=field:parity=auto:deint=all", mpvDeinterlaceGraph("estdif2x", false))
        assertEquals("bwdif=mode=send_field:parity=auto:deint=all", mpvDeinterlaceGraph("bwdif2x", false))
    }

    @Test fun filmReconstructionIncludesDecimationAndConditionalResidualCleanup() {
        assertEquals("fieldmatch=order=auto:combmatch=full,yadif=deint=interlaced,decimate", mpvDeinterlaceGraph("ivtc", false))
    }

    @Test fun nnediCannotBeStartedWithoutItsModel() {
        assertThrows(IllegalArgumentException::class.java) { mpvDeinterlaceGraph("nnedi", false) }
        assertThrows(IllegalArgumentException::class.java) { mpvDeinterlaceGraph("nnedi", false, "weights,scale=1") }
    }

    @Test fun lowModeUsesSingleRateMethodsRecognizedByTheSameBackend() {
        for (backend in listOf("mpv", "vlc")) {
            DeinterlaceCatalog.methods(backend).forEach {
                val effective = effectiveDeinterlaceFilter(it.key, true)
                assertEquals(effective, DeinterlaceCatalog.normalize(backend, effective))
                assertFalse(effective.endsWith("2x"))
                assertEquals(it.key, effectiveDeinterlaceFilter(it.key, false))
            }
        }
    }

    @Test fun rendererPreferencesMigrateThenRemainIndependent() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.tvheadendDeinterlaceFilterKey = "bob"
        assertEquals("yadif2x", prefs.tvheadendMpvDeinterlaceFilterKey)
        prefs.tvheadendMpvDeinterlaceFilterKey = "w3fdif2x"
        prefs.tvheadendDeinterlaceFilterKey = "phosphor"
        assertEquals("w3fdif2x", prefs.tvheadendMpvDeinterlaceFilterKey)
        assertEquals("phosphor", prefs.tvheadendDeinterlaceFilterKey)
    }

    @Test fun unsupportedMethodsAreNotAdvertisedOrPassedToBackends() {
        assertEquals("auto", DeinterlaceCatalog.normalize("vlc", "nnedi"))
        assertEquals("auto", DeinterlaceCatalog.normalize("mpv", "missing"))
        DeinterlaceCatalog.mpv.filter { it.key != "auto" }.forEach {
            assertNotNull(it.key, mpvDeinterlaceGraph(it.key, false, "/data/user/0/app/cache/weights.bin"))
        }
    }

    @Test fun mcdeintReceivesOneFramePerFieldWithMatchingParity() {
        assertEquals("yadif=mode=send_field_nospatial:parity=tff:deint=all,mcdeint=mode=fast:parity=tff:qp=1", mpvDeinterlaceGraph("mcdeint", false))
        assertEquals("yadif=mode=send_field_nospatial:parity=bff:deint=all,mcdeint=mode=fast:parity=bff:qp=1", mpvDeinterlaceGraph("mcdeint_bff", false))
    }
}

package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.SvpOptions
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFieldRateDeinterlaceTest {
    private fun rate(fps: Double?, key: String, mode: String = "on") =
        mpvLiveDisplayFps(fps, gpuFilters = true, deinterlaceMode = mode, deinterlaceFilter = key)!!

    @Test fun fieldRateClassification() {
        assertTrue(mpvFieldRateInput(50.0))
        assertTrue(mpvFieldRateInput(59.94))
        assertFalse(mpvFieldRateInput(25.0))
        assertFalse(mpvFieldRateInput(29.97))
        assertFalse(mpvFieldRateInput(23.976))
        assertFalse(mpvFieldRateInput(null))
        assertFalse(mpvFieldRateInput(Double.NaN))
    }

    @Test fun pal576i50AndHd1080i50ReportedAtFieldRateStayAt50() {
        for (key in listOf("auto", "bwdif2x", "yadif2x", "bwdif", "nnedi2x", "mcdeint")) {
            assertEquals(key, 50.0, rate(50.0, key), 0.001)
        }
    }

    @Test fun pal576i50And1080i50ReportedAsFramesDoubleTo50() {
        assertEquals(50.0, rate(25.0, "auto"), 0.001)
        assertEquals(50.0, rate(25.0, "bwdif2x"), 0.001)
        assertEquals(25.0, rate(25.0, "bwdif"), 0.001)
    }

    @Test fun ntsc1080i60BothReportings() {
        assertEquals(59.94, rate(29.97, "yadif2x"), 0.001)
        assertEquals(59.94, rate(59.94, "yadif2x"), 0.001)
        assertEquals(59.94, rate(59.94, "auto"), 0.001)
    }

    @Test fun progressiveAndDirectOutputUnchanged() {
        assertEquals(50.0, rate(50.0, "auto", mode = "off"), 0.001)
        assertEquals(25.0, rate(25.0, "bwdif2x", mode = "off"), 0.001)
        assertEquals(25.0, mpvLiveDisplayFps(25.0, gpuFilters = false, deinterlaceMode = "on", deinterlaceFilter = "bwdif2x")!!, 0.001)
        assertNull(mpvLiveDisplayFps(null, true, "on", "auto"))
    }

    @Test fun fieldRateInputGetsSingleRateGraphs() {
        assertEquals("bwdif=mode=send_frame:parity=auto:deint=all", mpvDeinterlaceGraph("auto", false, singleRate = true))
        assertEquals("bwdif=mode=send_frame:parity=auto:deint=interlaced", mpvDeinterlaceGraph("bwdif2x", true, singleRate = true))
        assertEquals("yadif=mode=send_frame:parity=auto:deint=all", mpvDeinterlaceGraph("yadif2x", false, singleRate = true))
        assertEquals("w3fdif=mode=frame:parity=auto:deint=all", mpvDeinterlaceGraph("w3fdif2x", false, singleRate = true))
        assertEquals("estdif=mode=frame:parity=auto:deint=all", mpvDeinterlaceGraph("estdif2x", false, singleRate = true))
        assertEquals("bwdif=mode=send_frame:parity=auto:deint=all", mpvDeinterlaceGraph("mcdeint", false, singleRate = true))
        assertTrue(mpvDeinterlaceGraph("nnedi2x", false, "/data/user/0/app/cache/w.bin", singleRate = true)!!.contains("field=a:"))
        // Single-rate methods and IVTC are unaffected by the guard.
        assertEquals(mpvDeinterlaceGraph("bwdif", false), mpvDeinterlaceGraph("bwdif", false, singleRate = true))
        assertEquals(mpvDeinterlaceGraph("ivtc", false), mpvDeinterlaceGraph("ivtc", false, singleRate = true))
    }

    @Test fun frameRateInputKeepsDoubleRateGraphs() {
        assertNull(mpvDeinterlaceGraph("auto", false))
        assertEquals("bwdif=mode=send_field:parity=auto:deint=all", mpvDeinterlaceGraph("bwdif2x", false))
    }

    @Test fun svpSeesDeinterlacedRate() {
        // 25 fps doubled to 50 on a 50 Hz display: nothing to interpolate.
        val doubled = mpvDeinterlacedFps(25.0, true, "auto", singleRate = false)
        assertEquals(SvpDecision.Skip(SvpInactiveReason.NOT_NEEDED), svpDecision(doubled, 50.0, SvpOptions(filmOnly = false)))
        // Field-rate 50 stays 50; on 60 Hz SVP interpolates 50 → 60, never from 100.
        val field = mpvDeinterlacedFps(50.0, true, "auto", singleRate = true)
        assertEquals(SvpDecision.Run(50.0, 60.0), svpDecision(field, 60.0, SvpOptions(filmOnly = false)))
        // Deinterlace off: unchanged.
        assertEquals(25.0, mpvDeinterlacedFps(25.0, false, "bwdif2x", singleRate = false)!!, 0.001)
    }

    @Test fun displayAgreesForBothFieldReportingsAndDoublesTo100OnlyOnRequest() {
        val modes = listOf(
            SupportedDisplayMode("1920x1080@50", 1, 1920, 1080, 50f),
            SupportedDisplayMode("1920x1080@60", 2, 1920, 1080, 60f),
            SupportedDisplayMode("1920x1080@100", 3, 1920, 1080, 100f),
            SupportedDisplayMode("1920x1080@25", 4, 1920, 1080, 25f),
        )
        val withoutFifty = modes.filter { it.refreshRate != 50f }
        for (reported in listOf(25.0, 50.0)) {
            val fps = rate(reported, "auto").toFloat()
            // Native field rate first, even with 100 Hz on offer and opted in.
            assertEquals(reported.toString(), 50f, DisplayRefreshRateMatcher.selectCadenceMode(modes, fps, double = true, threeTwo = true, highDouble = true)!!.refreshRate)
            assertEquals(reported.toString(), 50f, DisplayRefreshRateMatcher.selectCadenceMode(modes, fps, double = true, threeTwo = true, highDouble = false)!!.refreshRate)
            // 100 Hz only without a native 50 Hz mode and only on request.
            assertEquals(reported.toString(), 100f, DisplayRefreshRateMatcher.selectCadenceMode(withoutFifty, fps, double = true, threeTwo = true, highDouble = true)!!.refreshRate)
            assertNotEquals(reported.toString(), 100f, DisplayRefreshRateMatcher.selectCadenceMode(withoutFifty, fps, double = true, threeTwo = true, highDouble = false)?.refreshRate)
        }
    }
}

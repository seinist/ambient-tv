package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import org.junit.Assert.*
import org.junit.Test

class SvpPolicyTest {
    @Test fun changedDisplayTargetRestartsActiveSvpOnly() {
        assertTrue(svpTargetChanged("auto", 24.0, 60.0))
        assertTrue(svpTargetChanged("quality", 60.0, 59.94))
        assertFalse(svpTargetChanged("off", 24.0, 60.0))
        assertFalse(svpTargetChanged("auto", 60.0, 60.000004))
    }
    @Test fun savedChoicesNeverBypassPerformanceGate() {
        val mpv = MpvImageOptions(svpMode = "quality")
        val vlc = VlcImageOptions(svpMode = "auto")
        for (low in listOf(false, true)) {
            assertEquals("off", effectiveMpvImageOptions(mpv, low).svpMode)
            assertEquals("off", effectiveVlcImageOptions(vlc, low).svpMode)
        }
        assertEquals("quality", effectiveMpvImageOptions(mpv, false, true).svpMode)
        assertEquals("auto", effectiveVlcImageOptions(vlc, false, true).svpMode)
        assertEquals("off", effectiveMpvImageOptions(mpv, true, true).svpMode)
        assertEquals("quality", mpv.svpMode) // No destructive preference rewrite.
    }
    @Test fun ratesAreValidatedWithoutUpconvertingHighFpsOrUnknownSources() {
        assertNull(mpvSvpFilter("off", 24.0, 60.0))
        assertNull(mpvSvpFilter("auto", null, 60.0))
        assertNull(mpvSvpFilter("auto", Double.NaN, 60.0))
        assertNull(mpvSvpFilter("auto", 60.0, 59.94))
        assertNull(mpvSvpFilter("auto", 24.0, 144.0))
        assertTrue(mpvSvpFilter("auto", 24000.0/1001, 60000.0/1001)!!.contains("preset=0"))
    }
    @Test fun vlcUsesCopyBackWithoutForcingSoftwareDecode() {
        val options = VlcImageOptions(svpMode = "auto", softwareProcessing = false, denoise = "medium")
        val media = vlcImageMediaOptions(options, 59.94)
        assertTrue(":no-mediacodec-dr" in media)
        assertTrue(media.any { "ambientsvp{fps=59.94" in it })
        assertFalse(media.any { "hqdn3d" in it })
        assertFalse(vlcImageMediaOptions(effectiveVlcImageOptions(options, false), 60.0).any { "ambientsvp" in it })
        assertEquals("off", MpvImageOptions(svpMode = "bad").normalized().svpMode)
        assertEquals("off", VlcImageOptions(svpMode = "bad").normalized().svpMode)
    }
}

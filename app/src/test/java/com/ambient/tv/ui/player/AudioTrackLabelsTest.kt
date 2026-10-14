package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class AudioTrackLabelsTest {
    @Test fun titleDoesNotHideCodecAndChannelLayout() {
        assertEquals("Main · 7.1 · E-AC3", audioTrackLabel("Main", null, 8, "eac3"))
        assertEquals("Commentary · 2.0 · AC3", audioTrackLabel("Commentary", null, 2, "ac3"))
    }
    @Test fun atmosAndHdCodecArePreserved() {
        assertEquals("7.1 · E-AC3 Atmos", audioTrackLabel(null, null, 8, "audio/eac3-joc"))
        assertEquals("5.1 · DTS-HD", audioTrackLabel(null, null, 6, "audio/vnd.dts.hd"))
    }
    @Test fun unknownLayoutIsNotInvented() {
        assertEquals("TrueHD", audioTrackLabel(null, "und", -1, "truehd"))
        assertEquals("5ch", audioTrackLabel(null, null, 5, null))
        assertEquals("Audio", audioTrackLabel(null, null, null, null))
    }
    @Test fun conversionShowsActualSourceAndTarget() {
        assertEquals("E-AC3 7.1 → AC3 5.1", audioConversionFormats("audio/eac3", 8, "audio/ac3", 6))
        assertEquals("TrueHD 7.1 → PCM 2.0", audioConversionFormats("truehd", 8, "audio/raw", 2))
        assertEquals("E-AC3 7.1 → AC3", audioConversionFormats("eac3", 8, "ac3", null))
    }
}

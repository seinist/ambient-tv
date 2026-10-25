package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvVideoSyncTest {
    @Test fun displayResampleOnlyOnGpuWithPcmAndWithoutSvp() {
        assertEquals("display-resample", mpvVideoSync(syncToDisplay = true, gpuOutput = true, encodedAudio = false, svpActive = false))
        assertEquals("audio", mpvVideoSync(syncToDisplay = false, gpuOutput = true, encodedAudio = false, svpActive = false))
        assertEquals("direct surface has no mpv vsync timing", "audio",
            mpvVideoSync(syncToDisplay = true, gpuOutput = false, encodedAudio = false, svpActive = false))
        assertEquals("bitstream cannot be resampled", "audio",
            mpvVideoSync(syncToDisplay = true, gpuOutput = true, encodedAudio = true, svpActive = false))
        assertEquals("SVP re-times frames itself", "audio",
            mpvVideoSync(syncToDisplay = true, gpuOutput = true, encodedAudio = false, svpActive = true))
    }
}

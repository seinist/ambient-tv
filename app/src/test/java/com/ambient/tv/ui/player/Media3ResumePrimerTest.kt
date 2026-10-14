package com.ambient.tv.ui.player
import org.junit.Assert.*
import org.junit.Test
class Media3ResumePrimerTest {
    @Test fun onlyAffectedDolbyVisionColdResumeIsPrimed() {
        assertTrue(needsDolbyVisionResumePriming("NVIDIA", 30, HdrStandard.DOLBY_VISION, 30_000))
        assertFalse(needsDolbyVisionResumePriming("NVIDIA", 30, HdrStandard.DOLBY_VISION, 0))
        assertFalse(needsDolbyVisionResumePriming("NVIDIA", 34, HdrStandard.DOLBY_VISION, 30_000))
        assertFalse(needsDolbyVisionResumePriming("Google", 30, HdrStandard.DOLBY_VISION, 30_000))
        assertFalse(needsDolbyVisionResumePriming("NVIDIA", 30, HdrStandard.HDR10, 30_000))
        assertFalse(needsDolbyVisionResumePriming("NVIDIA", 30, null, 30_000))
    }
}

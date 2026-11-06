package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Media3RenderWorkloadTest {
    private fun workload(width: Int = 1920, height: Int = 1080, outputWidth: Int = 3840,
        outputHeight: Int = 2160, fps: Float = 24f, transfer: Int? = 3,
        kind: String = "cas") = media3RenderWorkloadSignature(
        listOf(Media3Stage.Sharpen(kind)), width, height, outputWidth, outputHeight,
        fps, transfer)

    @Test fun sameEffectsAtUhdNeedANewPerformanceSample() {
        assertNotEquals(workload(), workload(width = 3840, height = 2160))
    }

    @Test fun frameRateOutputAndColorChangesDoNotReuseAcceptedWorkload() {
        val original = workload()
        assertNotEquals(original, workload(fps = 60f))
        assertNotEquals(original, workload(outputWidth = 1920, outputHeight = 1080))
        assertNotEquals(original, workload(transfer = 1))
        assertNotEquals(original, workload(kind = "adaptive"))
    }

    @Test fun unchangedWorkloadDoesNotRepeatTheOverloadDialog() {
        assertEquals(workload(), workload())
        assertEquals(workload(fps = 30f), workload(fps = Float.NaN))
        assertEquals(workload(fps = 30f), workload(fps = 0f))
    }
}

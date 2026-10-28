package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import org.junit.Assert.*
import org.junit.Test

class RenderOverloadReducePolicyTest {
    private fun <T> steps(start: T, next: (T) -> T?): List<T> = generateSequence(next(start), next).toList()

    @Test fun media3StepsDownOneStageAtATime() {
        val start = Media3ImageOptions(enabled = true, scaler = "fsr", sharpening = "adaptive", denoise = "light", filmGrain = "medium", deband = "strong")
        val result = steps(start, ::media3ImageOptionsAfterOverload)
        assertEquals(listOf("cas", "off", "off", "off", "off", "off"), result.map { it.sharpening })
        assertEquals("light", result[1].denoise)
        assertEquals("off", result[2].denoise)
        assertEquals("off", result[3].filmGrain)
        assertEquals("off", result[4].deband)
        assertEquals("bilinear", result.last().scaler)
        assertTrue(result.all { it.enabled })
        assertNull(media3ImageOptionsAfterOverload(Media3ImageOptions(enabled = true, scaler = "bicubic", brightness = 5)))
    }

    @Test fun media3OverloadUsesTenPercentOfAllBuffers() {
        assertFalse(media3RenderOverloaded(0, 0))
        assertFalse(media3RenderOverloaded(10, 90))
        assertTrue(media3RenderOverloaded(11, 89))
    }

    @Test fun mpvStepsAfterSharpeningKeepDefaultDeband() {
        val start = MpvImageOptions(superResolution = true, denoise = "strong", deblock = "light", filmGrain = "light", deband = "strong")
        val result = steps(start, ::mpvImageOptionsAfterOverload)
        assertEquals(5, result.size)
        assertEquals(MpvImageOptions().deband, result.last().deband)
        assertNull(mpvImageOptionsAfterOverload(MpvImageOptions()))
    }

    @Test fun vlcDropsSvpThenFiltersThenSoftwareProcessing() {
        val start = VlcImageOptions(softwareProcessing = true, scaler = "lanczos", denoise = "light", deband = "light", svpMode = "balanced")
        val result = steps(start, ::vlcImageOptionsAfterOverload)
        assertEquals("off", result.first().svpMode)
        assertFalse(result.last().softwareProcessing)
        assertNull(vlcImageOptionsAfterOverload(VlcImageOptions()))
        assertEquals("auto", vlcDeinterlaceFilterAfterOverload("on", "yadif2x"))
        assertNull(vlcDeinterlaceFilterAfterOverload("off", "yadif2x"))
        assertNull(vlcDeinterlaceFilterAfterOverload("auto", "blend"))
    }
}

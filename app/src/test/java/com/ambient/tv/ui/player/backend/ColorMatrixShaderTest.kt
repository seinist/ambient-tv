package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import org.junit.Assert.*
import org.junit.Test

class ColorMatrixShaderTest {
    @Test fun correctionRunsFirstAndOnlyOnSdr() {
        assertEquals(listOf("matrix_601_to_709", "cas"),
            mpvImageShaderKeys(MpvImageOptions(colorMatrix = "601_to_709", sharpening = "cas"), sdr = true, upscaling = false))
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(colorMatrix = "601_to_709"), sdr = false))
        assertFalse(MPV_IMAGE_SHADERS.getValue("matrix_709_to_601").requiresCopyBack)
    }

    @Test fun unknownValuesNormalizeToAutoAndCountAsGpuProcessing() {
        assertEquals("auto", MpvImageOptions(colorMatrix = "bt.2020").normalized().colorMatrix)
        assertTrue(mpvNeedsGpuProcessing(MpvImageOptions(colorMatrix = "601_to_709", scaler = "bilinear",
            chromaScaler = "bilinear", downscaler = "bilinear", deband = "off", sigmoid = false)))
    }
}

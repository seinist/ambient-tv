package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import org.junit.Assert.*
import org.junit.Test

class CunnyVariantsTest {
    private val plain = listOf("cunny_fast_ds", "cunny_faster_ds", "cunny_fast_soft", "cunny_faster_soft", "cunny_veryfast_soft")
    private val dp4a = listOf("cunny_fast_soft_q", "cunny_faster_soft_q", "cunny_veryfast_soft_q")

    @Test fun variantsAreRegisteredAsLumaUpscalers() {
        for (key in plain + dp4a) {
            assertTrue(key, key in VideoImageCatalog.mpvShaders)
            assertTrue(key, mpvHeavyLumaUpscaler(key))
            assertTrue(key, MPV_IMAGE_SHADERS.getValue(key).requiresCopyBack)
            assertEquals(key, MpvImageOptions(scaler = key).normalized().scaler)
            assertFalse(mpvLumaUpscalerWouldRun(key, 1.3))
            assertTrue(mpvLumaUpscalerWouldRun(key, 1.5))
        }
    }

    @Test fun onlyDp4aVariantsNeedGpuNext() {
        plain.forEach { assertFalse(it, MPV_IMAGE_SHADERS.getValue(it).requiresGpuNext) }
        dp4a.forEach { assertTrue(it, MPV_IMAGE_SHADERS.getValue(it).requiresGpuNext) }
    }
}

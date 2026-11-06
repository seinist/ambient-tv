package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import org.junit.Assert.*
import org.junit.Test

class MpvUpscalerReconfigTest {
    @Test fun startupPlansReadableFramesForSourcePlaneUpscalers() {
        for (scaler in listOf("nnedi3_nns32", "ravu_lite_r3", "fsr", "nis", "artcnn16")) {
            assertTrue(scaler, mpvStartupRequiresCopyBack(MpvImageOptions(scaler = scaler)))
        }
        // MAIN/OUTPUT GPU effects can keep zero-copy. Hardware decode must not be disabled
        // for a shader such as the new RGB deblock.
        assertFalse(mpvStartupRequiresCopyBack(MpvImageOptions(scaler = "lanczos")))
        assertFalse(mpvStartupRequiresCopyBack(MpvImageOptions(deblock = "medium")))
        assertFalse(mpvStartupRequiresCopyBack(MpvImageOptions(scaler = "anime4k")))
        assertTrue(mpvStartupRequiresCopyBack(MpvImageOptions(denoise = "light")))
        assertTrue(mpvStartupRequiresCopyBack(MpvImageOptions(svpMode = "balanced")))
    }

    @Test fun runtimeOnlyReopensAnActuallyOpaqueOpenGlDecoderForReadableFrames() {
        val zeroCopy = MpvAccelerationPlan("gpu", "mediacodec", zeroCopy = true)
        val yuv = listOf("nnedi3_nns32")
        assertTrue(mpvProcessingNeedsCopyBackReopen(zeroCopy, "mediacodec", yuv, false, false))
        assertFalse(mpvProcessingNeedsCopyBackReopen(zeroCopy, "mediacodec-copy", yuv, false, false))
        assertFalse(mpvProcessingNeedsCopyBackReopen(zeroCopy.copy(zeroCopy = false), "mediacodec", yuv, false, false))
        assertFalse(mpvProcessingNeedsCopyBackReopen(zeroCopy, "mediacodec", listOf("deblock_medium"), false, false))
        assertTrue(mpvProcessingNeedsCopyBackReopen(zeroCopy, "mediacodec", emptyList(), true, false))
        assertFalse(mpvProcessingNeedsCopyBackReopen(MpvAccelerationPlan("vulkan", "vulkan"), "mediacodec", yuv, false, false))
    }

    @Test fun genericSourceAndSurfaceReconfigurationIsObservable() {
        val source = mpvUpscalerGeometrySignature(1920.0, 1080.0, 3840, 2160)
        assertEquals("1920x1080->3840x2160", source)
        assertNotEquals(source, mpvUpscalerGeometrySignature(1920.0, 1080.0, 1920, 1080))
        assertNotEquals(source, mpvUpscalerGeometrySignature(1280.0, 720.0, 3840, 2160))
        assertNotEquals(source, mpvUpscalerGeometrySignature(0.0, 0.0, 3840, 2160))

        val configured = MpvImageOptions(scaler = "nnedi3_nns32")
        val fourK = mpvFittedScale(1920.0, 1080.0, 3840, 2160)
        val logicalHd = mpvFittedScale(1920.0, 1080.0, 1920, 1080)
        assertEquals(2.0, fourK, 0.0)
        assertEquals(1.0, logicalHd, 0.0)
        assertEquals(listOf("nnedi3_nns32"), mpvImageShaderKeys(configured, sdr = true, fittedScale = fourK))
        assertEquals(emptyList<String>(), mpvImageShaderKeys(configured, sdr = true, fittedScale = logicalHd))
    }

    @Test fun nativeReconfigurationReinstallsSameShaderButStablePlaybackDoesNot() {
        val shader = listOf("nnedi3_nns32", "cas")
        // A VO rebuild or SurfaceView reattachment can invalidate native shader programs
        // even when the Java-side list still reports the old successful registrations.
        assertTrue(mpvImageShadersNeedReload(shader, shader, outputRecreated = true))
        assertFalse(mpvImageShadersNeedReload(shader, shader, outputRecreated = false))
        // A live choice must re-register immediately, without waiting for a new video load.
        assertTrue(mpvImageShadersNeedReload(listOf("fsr"), shader, outputRecreated = false))
        assertTrue(mpvImageShadersNeedReload(shader, shader, outputRecreated = false,
            transferProfileChanged = true))
        // A complete shader removal is itself a real reconfiguration.
        assertTrue(mpvImageShadersNeedReload(emptyList(), shader, outputRecreated = false))
    }

    @Test fun liveUpscalerCanSwitchAtNativeFourKScaleWithSvpOff() {
        val fitted = mpvFittedScale(1920.0, 1080.0, 3840, 2160)
        for (scaler in listOf("nnedi3_nns32", "fsr", "nis", "ravu_lite_r3", "anime4k")) {
            val keys = mpvImageShaderKeys(MpvImageOptions(scaler = scaler), sdr = true,
                fittedScale = fitted, svpActive = false)
            assertTrue(scaler, scaler in keys)
            assertTrue(mpvImageShadersNeedReload(keys, emptyList(), outputRecreated = false))
        }
        // The application's own UI-resolution target remains 1:1; the TV scales that.
        assertTrue(mpvImageShaderKeys(MpvImageOptions(scaler = "nnedi3_nns32"),
            sdr = true, fittedScale = 1.0, svpActive = false).isEmpty())
    }

    @Test fun hdrNeverEnablesAnSdrOnlyUpscaler() {
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(scaler = "nnedi3_nns32"), sdr = false, hdr = true, fittedScale = 2.0))
    }
}

package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.HdrStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MpvDecoderOptionsTest {
    private val patches: File = listOf(File("../native/ambientmpv/patches"), File("native/ambientmpv/patches")).first { it.isDirectory }
    private fun patch(name: String) = File(patches, name).readText()

    private val patched = setOf(MpvNativeFeatures.DV_ROUTING, MpvNativeFeatures.SD_SOFTWARE, MpvNativeFeatures.COLOR_INFO)

    @Test fun directSurfaceRoutesDolbyVisionByDisplay() {
        assertEquals("dolbyvision=1", mpvLavcDecoderOptions(true, setOf(HdrStandard.HDR10, HdrStandard.DOLBY_VISION), patched))
        // No DV display: profile 7/8 decode their HDR10 base layer, only profile 5 keeps the DV decoder.
        assertEquals("dolbyvision=2", mpvLavcDecoderOptions(true, setOf(HdrStandard.HDR10), patched))
        assertEquals("dolbyvision=2", mpvLavcDecoderOptions(true, emptySet(), patched))
    }

    @Test fun gpuOutputsLeaveSdMpegToSoftware() {
        assertEquals("hw_min_height=720", mpvLavcDecoderOptions(false, setOf(HdrStandard.DOLBY_VISION), patched))
    }

    @Test fun librariesWithoutThePatchesKeepTheOldOptions() {
        // An older libavcodec has dolbyvision as a 0..1 bool: 2 would be rejected (and ignored).
        assertEquals("dolbyvision=1", mpvLavcDecoderOptions(true, setOf(HdrStandard.HDR10), emptySet()))
        assertEquals("dolbyvision=1", mpvLavcDecoderOptions(true, emptySet(), setOf(MpvNativeFeatures.SD_SOFTWARE)))
        // No hw_min_height without the SD-software patch: nothing is set for GPU outputs.
        assertEquals("", mpvLavcDecoderOptions(false, emptySet(), emptySet()))
        assertEquals("", mpvLavcDecoderOptions(false, emptySet(), setOf(MpvNativeFeatures.DV_ROUTING)))
    }

    @Test fun nativeFeatureListParses() {
        assertEquals(setOf("ffmpeg-dv-routing", "mpv-egl-hdr-surface"), parseMpvNativeFeatures("ffmpeg-dv-routing, mpv-egl-hdr-surface,"))
        assertEquals(emptySet<String>(), parseMpvNativeFeatures(null))
        assertEquals(emptySet<String>(), parseMpvNativeFeatures(""))
    }

    @Test fun shimReportsEveryMarkerTheAppChecks() {
        val shim = listOf(File("../native/ambientmpv/ambientmpv.c"), File("native/ambientmpv/ambientmpv.c")).first { it.isFile }.readText()
        assertTrue(shim.contains("Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeFeatures"))
        listOf(MpvNativeFeatures.DV_ROUTING, MpvNativeFeatures.SD_SOFTWARE, MpvNativeFeatures.COLOR_INFO,
            MpvNativeFeatures.EGL_HDR_SURFACE).forEach { assertTrue(it, shim.contains("\"$it,")) }
        // The EGL marker is an exported symbol of the patched libmpv.
        assertTrue(patch("mpv-android-egl-hdr-surface.patch").contains("ambient_mpv_egl_hdr_surface"))
        assertTrue(shim.contains("ambient_mpv_egl_hdr_surface"))
    }

    @Test fun optionsExistInTheNativePatches() {
        val routing = patch("ffmpeg-dolby-vision-routing.patch")
        assertTrue(routing.contains("{ \"dolbyvision\","))
        assertTrue(routing.contains("AV_OPT_TYPE_INT, {.i64 = 0}, 0, 2, VD"))
        val sd = patch("ffmpeg-mediacodec-sd-software.patch")
        assertTrue(sd.contains("{ \"hw_min_height\","))
        assertTrue(sd.contains("AV_CODEC_ID_MPEG2VIDEO") && sd.contains("AV_CODEC_ID_MPEG4"))
        assertTrue(patch("ffmpeg-mediacodec-color-info.patch").contains("hdr-static-info"))
    }

    @Test fun patchSeriesKeepsItsApplyOrder() {
        // prepare_mpv_ffmpeg.sh applies in glob order; the incremental patches build on these.
        val ffmpeg = patches.list()!!.filter { it.startsWith("ffmpeg-") }.sorted()
        assertTrue(ffmpeg.indexOf("ffmpeg-dolby-vision-decoder.patch") < ffmpeg.indexOf("ffmpeg-dolby-vision-routing.patch"))
        assertTrue(ffmpeg.indexOf("ffmpeg-dolby-vision-routing.patch") < ffmpeg.indexOf("ffmpeg-mediacodec-color-info.patch"))
        assertTrue(ffmpeg.indexOf("ffmpeg-mediacodec-color-info.patch") < ffmpeg.indexOf("ffmpeg-mediacodec-sd-software.patch"))
        val mpv = patches.list()!!.filter { it.startsWith("mpv-") }.sorted()
        assertTrue(mpv.indexOf("mpv-audiotrack-clock.patch") < mpv.indexOf("mpv-audiotrack-passthrough.patch"))
        val audio = patch("mpv-audiotrack-passthrough.patch")
        assertTrue(audio.contains("CHANNEL_OUT_7POINT1_SURROUND"))
        assertTrue(audio.contains("if (p->format != AudioFormat.ENCODING_IEC61937)"))
        val egl = patch("mpv-android-egl-hdr-surface.patch")
        assertTrue(egl.contains("EGL_GL_COLORSPACE_BT2020_PQ_EXT") && egl.contains("EGL_SMPTE2086"))
    }
}

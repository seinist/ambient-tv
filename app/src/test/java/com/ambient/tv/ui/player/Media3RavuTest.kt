package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.player.backend.MPV_IMAGE_SHADERS
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAVU-Lite for Media3 is translated from the bundled MPV files, not a second copy. */
class Media3RavuTest {
    private fun mpvFile(key: String): String =
        File("src/main/res/raw").listFiles()!!.single { it.name == MPV_IMAGE_SHADERS.getValue(key).fileName }.readText()

    @Test fun everyOfferedRadiusTranslatesToGlslEs100() {
        for ((key, taps) in listOf("ravu_lite_r2" to 9, "ravu_lite_r3" to 25, "ravu_lite_r4" to 49)) {
            val source = media3RavuSource(mpvFile(key))
            assertNotNull(key, source)
            val glsl = source!!.step1
            assertTrue(key, glsl.startsWith("vec4 ravu(vec2 pos) {"))
            // Nothing MPV- or GLES3-specific is left: no hook macros, texture(), or mix() with a bool.
            for (token in listOf("HOOKED", "texture(", "ravu_lite_lut", "hook()")) assertFalse("$key $token", token in glsl)
            assertFalse(key, Regex("""mix\([^;]*(<|>=)""").containsMatchIn(glsl))
            assertEquals(key, taps, Regex("""lumaAt\(pos \+ vec2\(""").findAll(glsl).count())
            assertEquals(key, source.lutWidth, Regex("""lut\(vec2\(""").findAll(glsl).count())
            assertEquals(288, source.lutHeight)
            assertEquals(source.lutWidth * source.lutHeight * 4, source.lut.size)
            assertTrue(key, source.lut.all { it in -1f..2f })
        }
    }

    @Test fun zoomTranslatesToOnePassWithBothLuts() {
        val source = media3RavuZoomSource(mpvFile("ravu_zoom_r3"))
        assertNotNull(source)
        val glsl = source!!.glsl
        assertTrue(glsl.contains("vec4 ravuZoom() {"))
        assertTrue(glsl.contains("vec2 pos = vTexSamplingCoord * uInputSize;"))
        for (token in listOf("HOOKED", "texture(", "ravu_zoom_lut", "hook()")) assertFalse(token, token in glsl)
        assertFalse(Regex("""mix\([^;]*(<|>=)""").containsMatchIn(glsl))
        assertEquals(36, Regex("""lumaAtPos\(pos \+ vec2\(""").findAll(glsl).count())
        assertEquals(10, Regex("""lutA\(""").findAll(glsl).count())
        assertEquals(4, Regex("""lutB\(""").findAll(glsl).count())
        assertEquals(45 to 2592, source.lut.width to source.lut.height)
        assertEquals(18 to 2592, source.lutAr.width to source.lutAr.height)
        assertEquals(45 * 2592 * 4, source.lut.values.size)
        // The Lite translator still refuses Zoom and vice versa.
        assertNull(media3RavuZoomSource(mpvFile("ravu_lite_r3")))
    }

    @Test fun lutPacksIntoSixteenBitsPerWeight() {
        val lut = media3RavuSource(mpvFile("ravu_lite_r3"))!!.lut
        val planes = media3RavuLutPlanes(lut)
        val maxError = lut.indices.maxOf { kotlin.math.abs(media3RavuLutDecode(planes, it) - lut[it]) }
        assertTrue("max error $maxError", maxError <= planes.range / 65535f)
    }

    @Test fun unexpectedFilesAreRejectedInsteadOfCompiledHalfway() {
        assertNull(media3RavuSource(""))
        assertNull(media3RavuSource(mpvFile("ravu_lite_r3").replace("float coherence = mix(", "float coherence = max(")))
        assertNull(media3RavuSource(mpvFile("ravu_zoom_r3")))
    }

    @Test fun ravuIsAMedia3ScalerThatKeepsItsOwnFitPass() {
        for (key in MEDIA3_RAVU_SCALERS) {
            assertTrue(key in VideoImageCatalog.media3Scalers)
            assertEquals(VideoImageCatalog.mpvShaders[key], VideoImageCatalog.media3Scalers[key])
            assertEquals(key, Media3ImageOptions(scaler = key).normalized().scaler)
        }
        val plan = media3EffectPlan(Media3ImageOptions(enabled = true, scaler = "ravu_lite_r3", gamma = 4), upscaling = true)
        assertEquals(listOf(Media3Stage.Scale("ravu_lite_r3"), Media3Stage.Gamma(4)), plan)
        assertEquals(plan.map { Media3FusedStage(it) }, media3FusedStages(plan))
        assertTrue(MEDIA3_RAVU_MIN_SCALE > 1.41 && MEDIA3_RAVU_MIN_SCALE < 1.42)
    }
}

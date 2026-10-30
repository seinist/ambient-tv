package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FSRCNNX MPV-shader identity, half-float graph and safe Media3 processing budget. */
class Media3FsrcnnxTest {
    private fun model(total: Int): String {
        require(total in listOf(14, 26))
        val featureCount = if (total == 14) 2 else 4
        val items = mutableListOf<String>()
        repeat(featureCount) { n ->
            items += "//!HOOK LUMA\n//!DESC feature map " + (n + 1) + "\n//!BIND LUMA\n" +
                "//!SAVE FEATURE" + (n + 1) + "\nvec4 hook() { return vec4(LUMA_texOff(vec2(0))); }\n"
        }
        // Alternate reuse of the MODEL1 and MODEL2 aliases across four mapping levels.
        repeat(4 * featureCount) { n ->
            val input = if (n < featureCount) "FEATURE1" else "MODEL1"
            val output = "MAP" + (n + 1)
            items += "//!HOOK LUMA\n//!DESC mapping " + n + "\n//!BIND " + input +
                "\n//!SAVE " + output + "\nvec4 hook() { return " + input + "_texOff(0); }\n"
            if (n == 0) {
                // Subsequent mapping blocks use MODEL1 already generated.
                items[items.lastIndex] = items.last().replace("//!SAVE MAP1", "//!SAVE MODEL1")
            }
        }
        // Some model stages are residual and sub-convolution; last is aggregation.
        while (items.size < total - 1) {
            val n = items.size
            items += "//!HOOK LUMA\n//!DESC subband " + n +
                "\n//!BIND MODEL1\n//!SAVE RES" + n +
                "\nvec4 hook() { return MODEL1_texOff(0); }\n"
        }
        items += "//!HOOK LUMA\n//!DESC aggregation\n//!BIND SUBCONV1\n" +
            "vec4 hook() { return SUBCONV1_tex(SUBCONV1_pos); }\n"
        items[total - 2] = items[total - 2].replace(Regex("//!SAVE [A-Z0-9]+"), "//!SAVE SUBCONV1")
        return items.joinToString("")
    }

    @Test fun modelsCanBeParsedWithoutRewritingTheirLearnedWeights() {
        for (count in listOf(14, 26)) {
            val passes = requireNotNull(media3FsrcnnxPasses(model(count)))
            assertEquals(count, passes.size)
            assertEquals("FEATURE1", passes.first().save)
            assertNull(passes.last().save)
            assertEquals("aggregation", passes.last().description)
            assertEquals(listOf("SUBCONV1"), passes.last().bindings)
            val first = media3FsrcnnxFragment(passes.first())
            assertTrue(first.contains("precision highp float"))
            assertTrue(first.contains("inputLuma(vTexSamplingCoord"))
            assertTrue(first.contains("vec4 cnnHook()"))
            assertFalse(first.contains("//!HOOK"))
            val last = media3FsrcnnxFragment(passes.last())
            assertTrue(last.contains("uniform sampler2D uSUBCONV1;"))
        }
    }

    @Test fun invalidBindOrderOrMalformedShaderFailsClosed() {
        val valid = model(14)
        assertNull(media3FsrcnnxPasses(valid.replace("//!BIND MODEL1", "//!BIND UNKNOWN")))
        assertNull(media3FsrcnnxPasses(valid.replace("vec4 hook()", "float hook()")))
        assertNull(media3FsrcnnxPasses(valid.substringBefore("//!DESC aggregation")))
        assertNull(media3FsrcnnxPasses(""))
    }

    @Test fun budgetRequiresAtLeastOnePointThreeTimesAndStaysWithinSourceRate() {
        assertTrue(media3FsrcnnxBudgetAllows("fsrcnnx8", 640, 360, 1280, 720, 30f))
        assertFalse(media3FsrcnnxBudgetAllows("fsrcnnx8", 640, 360, 768, 432, 24f))
        assertFalse(media3FsrcnnxBudgetAllows("fsrcnnx8", 640, 360, 1920, 1080, 24f))
        assertFalse(media3FsrcnnxBudgetAllows("fsrcnnx8", 1280, 720, 1920, 1080, 30f))
        assertFalse(media3FsrcnnxBudgetAllows("fsrcnnx8", 640, 360, 1280, 720, 60f))
        assertTrue(media3FsrcnnxBudgetAllows("fsrcnnx16", 480, 270, 960, 540, 30f))
        assertFalse(media3FsrcnnxBudgetAllows("fsrcnnx16", 640, 360, 1280, 720, 30f))
        assertFalse(media3FsrcnnxBudgetAllows("fsr", 480, 270, 960, 540, 30f))
    }

    @Test fun plannerReportsRealFallbackRatherThanInactiveSelectedShader() {
        for (key in MEDIA3_FSRCNNX_SCALERS) {
            assertEquals(VideoImageCatalog.mpvShaders.getValue(key), VideoImageCatalog.media3Scalers.getValue(key))
            assertTrue(key in MEDIA3_HEAVY_SCALERS)
            assertTrue(key in MEDIA3_SHADER_SCALERS)
            val saved = Media3ImageOptions(enabled = true, scaler = key, superResolution = true)
            assertEquals(key, saved.normalized().scaler)
            assertFalse(saved.normalized().superResolution)
            assertFalse(media3SuperResRowVisible(saved, upscalerVisible = true))
            assertFalse(media3AntiRingRowVisible(saved, true, false))
            assertFalse(media3SigmoidRowVisible(saved, true))
            assertEquals(listOf(Media3Stage.Scale(key)), media3EffectPlan(saved, upscaling = true))
            val fallen = media3EffectPlan(saved, upscaling = true, fsrcnnxAllowed = false)
            assertEquals(listOf(Media3Stage.Scale("catmull_rom")), fallen)
            assertEquals("Catmull-Rom", media3AppliedProcessing(true, "catmull_rom", fallen).upscaler)
            assertTrue(media3EffectPlan(saved, upscaling = false).isEmpty())
        }
    }

    @Test fun oldAndNewSettingsRoundTrip() {
        val codec = Json { encodeDefaults = true }
        val previous = codec.decodeFromString<Media3ImageOptions>(
            """{"enabled":true,"scaler":"anime4k"}""")
        assertEquals("anime4k", previous.normalized().scaler)
        val next = Media3ImageOptions(enabled = true, scaler = "fsrcnnx16",
            sharpening = "cas", gamma = 4)
        assertEquals(next, codec.decodeFromString<Media3ImageOptions>(codec.encodeToString(next)))
    }
}

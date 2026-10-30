package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates the MPV Anime4K model parser, budgets and Media3 panel behaviour without a GPU. */
class Media3Anime4kTest {
    private fun dummyModel(medium: Boolean): String {
        val conv = if (medium)
            listOf("conv2d_tf", "conv2d_1_tf", "conv2d_2_tf", "conv2d_3_tf",
                "conv2d_4_tf", "conv2d_5_tf", "conv2d_6_tf", "conv2d_last_tf")
        else listOf("conv2d_tf", "conv2d_1_tf", "conv2d_2_tf", "conv2d_last_tf")
        val parts = conv.mapIndexed { index, output ->
            val inputs = if (medium && index == 7) conv.take(7) else listOf(
                if (index == 0) "MAIN" else conv[index - 1],
            )
            buildString {
                append("//!DESC Anime4K-v3.2-Upscale-CNN\n//!HOOK MAIN\n")
                inputs.forEach { append("//!BIND ").append(it).append("\n") }
                append("//!SAVE ").append(output).append("\n")
                append("#define go_0(x_off, y_off) vec4(0.0)\n")
                append("vec4 hook() { return mat4(1.0) * vec4(0.1); }\n")
            }
        }
        return parts.joinToString("") + buildString {
            append("//!DESC Anime4K-v3.2-Depth-to-Space\n//!HOOK MAIN\n")
            append("//!BIND MAIN\n//!BIND conv2d_last_tf\n//!SAVE MAIN\n")
            append("vec4 hook() { return MAIN_tex(MAIN_pos) + conv2d_last_tf_tex(conv2d_last_tf_pos); }\n")
        }
    }

    @Test fun sourceIsSplitIntoOrderedSignedFeaturePasses() {
        for ((medium, count) in listOf(false to 5, true to 9)) {
            val source = dummyModel(medium)
            val stages = requireNotNull(media3Anime4kPasses(source))
            assertEquals(count, stages.size)
            assertEquals("conv2d_tf", stages.first().save)
            assertEquals("conv2d_last_tf", stages[stages.lastIndex - 1].save)
            assertEquals(listOf("MAIN", "conv2d_last_tf"), stages.last().bindings)
            assertEquals("MAIN", stages.last().save)
            assertTrue(stages.first().body.contains("mat4(1.0)"))
            val cnn = media3Anime4kFragment(stages.first())
            assertTrue(cnn.startsWith("#version 100"))
            assertTrue(cnn.contains("precision highp float"))
            assertTrue(cnn.contains("uniform sampler2D uMAIN;"))
            assertTrue(cnn.contains("vec4 animeHook()"))
            assertTrue(cnn.contains("gl_FragColor = animeHook()"))
            assertFalse(cnn.contains("//!BIND"))
            val final = media3Anime4kFragment(stages.last())
            assertTrue(final.contains("uniform sampler2D uconv2d_last_tf;"))
            assertTrue(final.contains("vec3(2.2)"))
            assertEquals(count, stages.map { it.save }.distinct().size)
            if (medium) {
                assertEquals(7, stages[7].bindings.size)
                assertTrue(media3Anime4kFragment(stages[7]).contains("uconv2d_6_tf"))
            }
        }
    }

    @Test fun corruptedModelOrMissingInputsAreRejectedBeforeCompilingShaders() {
        val good = dummyModel(false)
        assertNull(media3Anime4kPasses(good.replace("//!BIND conv2d_1_tf",
            "//!BIND unexpected_input")))
        assertNull(media3Anime4kPasses(good.replace("//!SAVE conv2d_1_tf",
            "//!SAVE conv2d_tf")))
        assertNull(media3Anime4kPasses(good.replace("vec4 hook()", "float hook()")))
        assertNull(media3Anime4kPasses(good.substringBefore("//!DESC Anime4K-v3.2-Depth-to-Space")))
        assertNull(media3Anime4kPasses(""))
    }

    @Test fun anime4kBudgetUsesMpvsOnePointTwoTimesGateAndPerformanceLimits() {
        assertTrue(media3Anime4kBudgetAllows("anime4k", 1280, 720, 1920, 1080, 30f))
        assertTrue(media3Anime4kBudgetAllows("anime4k_denoise", 1280, 720, 1920, 1080, 24f))
        assertFalse(media3Anime4kBudgetAllows("anime4k", 1280, 720, 1408, 792, 24f))
        assertFalse(media3Anime4kBudgetAllows("anime4k", 1280, 720, 3840, 2160, 24f))
        assertFalse(media3Anime4kBudgetAllows("anime4k", 1920, 1080, 3840, 2160, 24f))
        assertFalse(media3Anime4kBudgetAllows("anime4k", 1280, 720, 1920, 1080, 60f))
        assertTrue(media3Anime4kBudgetAllows("anime4k_m", 960, 540, 1920, 1080, 30f))
        assertFalse(media3Anime4kBudgetAllows("anime4k_m", 1280, 720, 1920, 1080, 30f))
        assertFalse(media3Anime4kBudgetAllows("fsr", 640, 360, 1280, 720, 24f))
        assertFalse(media3Anime4kBudgetAllows("anime4k", 0, 1080, 1920, 1080, 24f))
    }

    @Test fun media3PlannerNeverStacksSsimOnAnime4kAndFallsBackWhenOverBudget() {
        for (key in MEDIA3_ANIME4K_SCALERS) {
            assertEquals(VideoImageCatalog.mpvShaders[key], VideoImageCatalog.media3Scalers[key])
            assertTrue(key in MEDIA3_HEAVY_SCALERS)
            val options = Media3ImageOptions(enabled = true, scaler = key, superResolution = true)
            assertFalse(options.normalized().superResolution)
            assertFalse(media3SuperResRowVisible(options, upscalerVisible = true))
            assertEquals(listOf(Media3Stage.Scale(key)),
                media3EffectPlan(options, upscaling = true))
            assertEquals(listOf(Media3Stage.Scale("catmull_rom")),
                media3EffectPlan(options, upscaling = true, anime4kAllowed = false))
            assertEquals(emptyList<Media3Stage>(),
                media3EffectPlan(options, upscaling = false))
            assertFalse(media3AntiRingRowVisible(options, true, false))
            assertFalse(media3SigmoidRowVisible(options, true))
        }
    }

    @Test fun savedImageOptionsRemainCompatibleWithPreviousProfiles() {
        val old = Json.decodeFromString<Media3ImageOptions>(
            """{"enabled":true,"scaler":"spline36","superResolution":true}""",
        )
        assertEquals("spline36", old.normalized().scaler)
        assertTrue(old.normalized().superResolution)
        val anime = Json.decodeFromString<Media3ImageOptions>(
            """{"enabled":true,"scaler":"anime4k_denoise_m"}""",
        )
        assertEquals("anime4k_denoise_m", anime.normalized().scaler)
        assertFalse(anime.normalized().superResolution)
    }
}

package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The original MPV compute graphs must be represented, not surrogate sharpening. */
class Media3NeuralComputeTest {
    private fun syntheticModel(key: String): String {
        val art = key in MEDIA3_ARTCNN_SCALERS
        val count = if (art) 8 else 4
        val prefix = if (art) "conv2d" else "conv"
        return buildString {
            repeat(count) { n ->
                val bind = if (n == 0) "LUMA" else prefix + (n - 1)
                append("//!DESC ").append(if (art) "ArtCNN" else "CuNNy").append(" pass ").append(n).append("\n")
                append("//!COMPUTE 24 32 12 16\n//!HOOK LUMA\n//!BIND ")
                    .append(bind).append('\n')
                if (n < count - 1) append("//!SAVE ").append(prefix).append(n).append('\n')
                append("//!WIDTH LUMA.w 2.0 *\n//!HEIGHT LUMA.h 2.0 *\n")
                append("#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable\n")
                append("#ifdef GL_EXT_shader_explicit_arithmetic_types_float16\n")
                append("# define V4 f16vec4\n# define M4 f16mat4\n# define F float16_t\n")
                append("#else\n# define V4 vec4\n# define M4 mat4\n# define F float\n#endif\n")
                append("void hook() {\n")
                append("  imageStore(out_image, ivec2(gl_GlobalInvocationID.xy), ")
                append("V4(0.25, 0.0, 0.0, 1.0));\n}\n")
            }
        }
    }

    @Test fun parsesComputeLayoutsAndRejectsQuantizedVulkanSource() {
        for (key in MEDIA3_NEURAL_COMPUTE_SCALERS) {
            val passes = requireNotNull(media3ComputePasses(key, syntheticModel(key)))
            assertEquals(if (key in MEDIA3_ARTCNN_SCALERS) 8 else 4, passes.size)
            assertEquals("LUMA", passes.first().bindings.first())
            assertNull(passes.last().save)
            assertEquals(listOf(24, 32, 12, 16),
                listOf(passes[0].tileWidth, passes[0].tileHeight,
                    passes[0].localWidth, passes[0].localHeight))
            assertEquals(2, passes[0].widthMultiplier)
            assertEquals(2, passes[0].heightMultiplier)
            val shader = media3ComputeShader(passes[0])
            assertTrue(shader.startsWith("#version 310 es"))
            assertTrue(shader.contains("layout(local_size_x = 12, local_size_y = 16"))
            assertTrue(shader.contains("layout(rgba16f, binding = 0)"))
            assertTrue(shader.contains("#define V4 vec4"))
            assertTrue(shader.contains("imageStore(out_image"))
            assertFalse(shader.contains("GL_EXT_shader_explicit_arithmetic_types_float16"))
        }
        for (key in listOf("cunny_fast_soft_q", "cunny_faster_soft_q", "cunny_veryfast_soft_q")) {
            assertFalse(key in MEDIA3_NEURAL_COMPUTE_SCALERS)
            assertNull(media3ComputePasses(key, syntheticModel("cunny_fast_soft")))
        }
        assertNull(media3ComputePasses("cunny_fast_soft",
            syntheticModel("cunny_fast_soft") + "\n#extension GL_EXT_spirv_intrinsics : require\n"))
    }

    @Test fun malformedWorkgroupOrDependenciesFailClosed() {
        val shader = syntheticModel("artcnn16")
        assertNull(media3ComputePasses("artcnn16", shader.replace("//!BIND conv2d0", "//!BIND MISSING")))
        assertNull(media3ComputePasses("artcnn16", shader.replace("//!COMPUTE 24 32 12 16", "//!COMPUTE 24 32 64 64")))
        assertNull(media3ComputePasses("artcnn16", shader.replace("void hook()", "vec4 hook()")))
        assertNull(media3ComputePasses("artcnn16", shader.substringBefore("//!DESC ArtCNN pass 7")))
    }

    @Test fun conservativeBudgetsAndFallbackArePerModel() {
        assertTrue(media3NeuralComputeBudgetAllows("artcnn16", 256, 144, 512, 288, 24f))
        assertFalse(media3NeuralComputeBudgetAllows("artcnn16", 640, 360, 1280, 720, 24f))
        assertTrue(media3NeuralComputeBudgetAllows("artcnn32", 160, 90, 320, 180, 24f))
        assertFalse(media3NeuralComputeBudgetAllows("artcnn32", 320, 180, 640, 360, 24f))
        assertTrue(media3NeuralComputeBudgetAllows("cunny_veryfast_soft", 640, 360, 1280, 720, 24f))
        assertFalse(media3NeuralComputeBudgetAllows("cunny_fast_ds", 640, 360, 1280, 720, 24f))
        assertFalse(media3NeuralComputeBudgetAllows("cunny_fast_soft", 640, 360, 768, 432, 24f))
        assertFalse(media3NeuralComputeBudgetAllows("cunny_fast_soft_q", 160, 90, 320, 180, 24f))
        assertFalse(media3NeuralComputeBudgetAllows("artcnn16", 0, 144, 512, 288, 24f))

        for (key in MEDIA3_NEURAL_COMPUTE_SCALERS) {
            assertTrue(key in MEDIA3_HEAVY_SCALERS)
            assertTrue(key in MEDIA3_SHADER_SCALERS)
            assertEquals(VideoImageCatalog.mpvShaders.getValue(key), VideoImageCatalog.media3Scalers.getValue(key))
            val o = Media3ImageOptions(enabled = true, scaler = key, superResolution = true)
            assertEquals(key, o.normalized().scaler)
            assertFalse(o.normalized().superResolution)
            assertFalse(media3SuperResRowVisible(o, upscalerVisible = true))
            assertFalse(media3AntiRingRowVisible(o, upscalerVisible = true, downscalerVisible = false))
            assertFalse(media3SigmoidRowVisible(o, upscalerVisible = true))
            assertEquals(listOf(Media3Stage.Scale(key)), media3EffectPlan(o, upscaling = true))
            assertEquals(listOf(Media3Stage.Scale("catmull_rom")),
                media3EffectPlan(o, upscaling = true, computeAllowed = false))
            assertTrue(media3EffectPlan(o, upscaling = false).isEmpty())
        }
    }
}

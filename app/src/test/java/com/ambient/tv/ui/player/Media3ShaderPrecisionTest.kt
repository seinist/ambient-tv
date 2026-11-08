package com.ambient.tv.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3ShaderPrecisionTest {
    @Test fun lightGles2ShadersKeepTheMediumpFallbackAndSamplerParametersFollowIt() {
        val source = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
vec4 sampleFeature(sampler2D feature, vec2 uv) { return texture2D(feature, uv); }
uniform sampler2D uImage;
void main() { gl_FragColor = sampleFeature(uImage, vec2(0.5)); }
"""
        val fragment = media3FragmentSamplerPrecision(source)
        assertTrue(fragment.startsWith("#version 100\n"))
        assertTrue(fragment.contains("#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\nprecision highp sampler2D;\n#else\nprecision mediump float;\nprecision mediump sampler2D;\n#endif"))
        assertEquals(source.substringAfter("#endif"), fragment.substringAfter("#endif"))
    }

    @Test fun ssimSamplerArgumentsAndLutFiltersUseHighpBeforeTheirDeclarations() {
        val sources = listOf(SSIM_FRAGMENT_COMMON + SSIM_RESIZE_SHADER,
            media3NisFragmentShader(media3NisConstants())) +
            MEDIA3_KERNELS.values.flatMap { kernel ->
                listOf(media3KernelShader(kernel), media3KernelShader(kernel, downscaling = true))
            }
        sources.forEach { source ->
            val fragment = media3FragmentSamplerPrecision(source)
            assertHighpSamplers(fragment)
            assertEquals(source, fragment.replace("\nprecision highp sampler2D;", "")
                .replace("\nprecision mediump sampler2D;", ""))
        }
    }

    @Test fun bothFsrcnnxModelsUsePortableAggregationAndPreciseFeatureReads() {
        val resources = rawFiles("quality_fsrcnnx", ".glsl")
        assertEquals(2, resources.size)
        resources.forEach { file ->
            val passes = requireNotNull(media3FsrcnnxPasses(file.readText()))
            passes.forEach { pass -> assertHighpSamplers(media3FragmentSamplerPrecision(media3FsrcnnxFragment(pass))) }
            passes.filter { "_texOff(0)" in it.body }.forEach { pass ->
                val fragment = media3FsrcnnxFragment(pass)
                assertTrue(fragment.contains("vTexSamplingCoord + vec2(offset) / uInputSize"))
            }
            val aggregation = media3FsrcnnxFragment(passes.last())
            assertFalse(file.name, aggregation.contains("res[index.x * 2 + index.y]"))
            assertTrue(file.name, aggregation.contains("fcoord.x < 0.5"))
            assertTrue(file.name, aggregation.contains("fcoord.y < 0.5 ? res.r : res.g"))
            assertTrue(file.name, aggregation.contains("fcoord.y < 0.5 ? res.b : res.a"))
        }
    }

    @Test fun nnedi3KeepsPackedIntegerWeightsAndFeatureReadsAtFullPrecision() {
        val resources = rawFiles("nnedi3_", ".hook")
        assertEquals(6, resources.size)
        resources.forEach { file ->
            requireNotNull(media3Nnedi3Stages(file.readText())).forEach { stage ->
                val fragment = media3FragmentSamplerPrecision(media3Nnedi3Fragment(stage))
                assertTrue(fragment.startsWith("#version 300 es\n"))
                assertTrue(fragment.contains("precision highp int;"))
                assertHighpSamplers(fragment)
                assertTrue(fragment.contains(stage.source.replace("vec4 hook()", "vec4 nnHook()")))
            }
        }
    }

    @Test fun allComputeModelsHaveAnEntryPointAndGlesCompatibleDeclarations() {
        val resources = rawFiles("quality_", ".glsl").filter {
            it.name.startsWith("quality_artcnn") || it.name.startsWith("quality_cunny_")
        }
        assertEquals(MEDIA3_NEURAL_COMPUTE_SCALERS.size, resources.size)
        resources.forEach { file ->
            val key = file.nameWithoutExtension.removePrefix("quality_").substringBeforeLast("_")
            requireNotNull(media3ComputePasses(key, file.readText())).forEach { pass ->
                val shader = media3ComputeShader(pass)
                assertEquals(1, Regex("void main\\(\\)").findAll(shader).count())
                assertTrue(shader.trimEnd().endsWith("void main() { hook(); }"))
                assertFalse(shader.contains("GL_EXT_shader_explicit_arithmetic_types_float16"))
                assertFalse("packed is a reserved GLSL keyword", Regex("\\bint packed\\b").containsMatchIn(shader))
                if (key in MEDIA3_ARTCNN_SCALERS) {
                    if ("const ivec2 ksize =" in pass.source)
                        assertTrue(shader.contains("const ivec2 ksize ="))
                    if ("uvec2 local_xy" in pass.source)
                        assertTrue(shader.contains("ivec2 local_xy = ivec2(gl_LocalInvocationID.xy);"))
                    assertFalse(shader.contains("for (uint "))
                    assertFalse(Regex("(?m)^[ \t]+const ").containsMatchIn(shader))
                }
            }
        }
    }

    @Test fun externalHdrSamplerMatchesItsHighpColourConversion() {
        assertTrue(SdrHdrShader.fragment.contains("uniform highp samplerExternalOES video;"))
    }

    private fun assertHighpSamplers(fragment: String) {
        val declaration = fragment.indexOf("precision highp sampler2D;")
        assertTrue("No highp sampler default", declaration >= 0)
        val firstUse = Regex("(?:uniform|\\()[^;\n]*sampler2D").find(fragment)
        assertTrue("Sampler default must precede uniforms and parameters", firstUse != null && declaration < firstUse.range.first)
    }

    private fun rawFiles(prefix: String, suffix: String): List<File> =
        requireNotNull(File("src/main/res/raw").listFiles())
            .filter { it.name.startsWith(prefix) && it.name.endsWith(suffix) }.sortedBy { it.name }
}

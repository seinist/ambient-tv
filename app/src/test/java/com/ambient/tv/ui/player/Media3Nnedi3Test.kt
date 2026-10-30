package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.Nnedi3QualityCatalog
import com.ambient.tv.data.local.VideoImageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3Nnedi3Test {
    private fun source(): String = listOf(
        "double_y", "combine_y", "double_x", "combine_x",
    ).map { name ->
        val isDouble = name.startsWith("double_")
        buildString {
            append("//!DESC NNEDI3 (" + name + ", nns16, win8x4)\n//!HOOK LUMA\n//!BIND HOOKED\n")
            if (isDouble) append("//!SAVE nnedi3_int\n")
            else append("//!BIND nnedi3_int\n")
            if (isDouble) append("float nnedi3(vec4 samples[8]) { return intBitsToFloat(1048576000); }\n")
            append("vec4 hook() { return ")
            append(if (isDouble) "vec4(0.5);" else "HOOKED_texOff(vec2(0));")
            append(" }\n")
        }
    }.joinToString("\n")

    @Test fun originalHookOrderAndGles3FragmentArePreserved() {
        val stages = requireNotNull(media3Nnedi3Stages(source()))
        assertEquals(listOf("double_y", "combine_y", "double_x", "combine_x"),
            stages.map { it.name })
        assertTrue(media3Nnedi3Fragment(stages.first()).startsWith("#version 300 es"))
        assertTrue(media3Nnedi3Fragment(stages.first()).contains("intBitsToFloat"))
        assertTrue(media3Nnedi3Fragment(stages[1]).contains("nnedi3_int_texOff"))
        assertTrue(media3Nnedi3Fragment(stages[3]).contains("uniform sampler2D uPredict;"))
        assertTrue(NNEDI3_VERTEX_ES3.contains("in vec4 aFramePosition;"))
    }

    @Test fun malformedOrMissingHooksAreRejected() {
        assertNull(media3Nnedi3Stages(""))
        assertNull(media3Nnedi3Stages(source().replace("combine_y", "combine_missing")))
        assertNull(media3Nnedi3Stages(source().replace("//!SAVE nnedi3_int", "//!SAVE wrong")))
        assertNull(media3Nnedi3Stages(source().replace("intBitsToFloat", "convert")))
        assertNull(media3Nnedi3Stages(source().substringBefore("//!DESC NNEDI3 (combine_x")))
    }

    @Test fun limitsRespectNeuronCountAndMpvsOnePointFourTimesGate() {
        assertTrue(media3Nnedi3BudgetAllows("nnedi3_nns16", 640, 360, 1280, 720, 24f))
        assertFalse(media3Nnedi3BudgetAllows("nnedi3_nns16", 640, 360, 768, 432, 24f))
        assertFalse(media3Nnedi3BudgetAllows("nnedi3_nns16", 640, 360, 1920, 1080, 24f))
        assertFalse(media3Nnedi3BudgetAllows("nnedi3_nns16", 640, 360, 1280, 720, 60f))
        assertTrue(media3Nnedi3BudgetAllows("nnedi3_nns32", 480, 270, 960, 540, 24f))
        assertFalse(media3Nnedi3BudgetAllows("nnedi3_nns32", 640, 360, 1280, 720, 24f))
        assertTrue(media3Nnedi3BudgetAllows("nnedi3_nns64", 320, 180, 640, 360, 24f))
        assertFalse(media3Nnedi3BudgetAllows("nnedi3_nns128", 640, 360, 1280, 720, 24f))
        assertFalse(media3Nnedi3BudgetAllows("nnedi3_nns256", 640, 360, 1280, 720, 24f))
        assertTrue(media3Nnedi3BudgetAllows("nnedi3_nns256_win8x6", 96, 54, 192, 108, 24f))
        assertFalse(media3Nnedi3BudgetAllows("invalid", 96, 54, 192, 108, 24f))
    }

    @Test fun allSixOriginalPresetsAreInMedia3AndNeverStackSsim() {
        for (preset in Nnedi3QualityCatalog.presets) {
            val key = preset.key
            assertTrue(key in MEDIA3_NNEDI3_SCALERS)
            assertTrue(key in MEDIA3_HEAVY_SCALERS)
            assertTrue(key in MEDIA3_SHADER_SCALERS)
            assertEquals(preset.displayName, VideoImageCatalog.media3Scalers.getValue(key))
            val o = Media3ImageOptions(enabled = true, scaler = key, superResolution = true)
            assertFalse(o.normalized().superResolution)
            assertFalse(media3SuperResRowVisible(o, true))
            assertFalse(media3SigmoidRowVisible(o, true))
            assertFalse(media3AntiRingRowVisible(o, true, false))
            assertEquals(listOf(Media3Stage.Scale(key)), media3EffectPlan(o, upscaling = true))
            assertEquals(listOf(Media3Stage.Scale("catmull_rom")),
                media3EffectPlan(o, upscaling = true, nnedi3Allowed = false))
            assertTrue(media3EffectPlan(o, upscaling = false).isEmpty())
        }
    }
}

package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Nnedi3QualityCatalogTest {
    @Test
    fun qualityLadderCoversEveryUpstreamNeuronTierForWin8x4() {
        assertEquals(listOf(16, 32, 64, 128, 256), Nnedi3QualityCatalog.presets.filter { it.window == "win8x4" }.map { it.neurons })
        assertEquals(listOf(256), Nnedi3QualityCatalog.presets.filter { it.window == "win8x6" }.map { it.neurons })
        assertEquals(Nnedi3QualityCatalog.presets.size, Nnedi3QualityCatalog.presets.map { it.key }.distinct().size)
        Nnedi3QualityCatalog.presets.forEach { preset ->
            assertTrue(preset.upstreamUrl.contains(Nnedi3QualityCatalog.REVISION))
            assertTrue(preset.sourceFileName.endsWith("-${preset.window}.hook"))
        }
    }

    @Test
    fun shaderValidationRequiresAllFourNnediStages() {
        val preset = Nnedi3QualityCatalog.presets.first { it.neurons == 64 }
        val valid = buildString {
            append("GNU Lesser General Public License\n//!HOOK LUMA\n")
            repeat(1_100) { append('x') }
            listOf("double_y", "combine_y", "double_x", "combine_x").forEach { stage ->
                append("\n//!DESC NNEDI3 ($stage, nns64, win8x4)\n")
            }
        }
        assertTrue(validateNnedi3ShaderSource(valid, preset))
        assertFalse(validateNnedi3ShaderSource(valid.replace("combine_x", "combine_z"), preset))
        assertFalse(validateNnedi3ShaderSource(valid.replace("nns64", "nns32"), preset))
    }
}

package com.ambient.tv.ui.player.backend

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NisOpenGlTest {
    private val original = File("src/main/res/raw/quality_nis_nvscaler_bac778ec.glsl").readText()

    @Test fun glesConversionsPreserveCoefficientsTablesAndHookLayout() {
        val adapted = nisOpenGlSource(original)
        assertNotEquals(original, adapted)
        assertFalse(Regex("""(?m)^[ \t]+const """).containsMatchIn(adapted))
        assertTrue(adapted.contains("const float sharpen_slider ="))
        assertTrue(adapted.contains("const float kDetectRatio = 2.0 * 1127.f / 1024.f;"))
        assertEquals(original.lines().filter { it.startsWith("//!") }, adapted.lines().filter { it.startsWith("//!") })
        assertEquals(original.substringAfter("//!TEXTURE coef_scaler"), adapted.substringAfter("//!TEXTURE coef_scaler"))
        assertEquals(numbers(original), numbers(adapted))
        assertEquals(adapted, nisOpenGlSource(adapted))
    }

    @Test fun integerArithmeticAndUnsignedPromotionPrecedeFloatConversion() {
        val adapted = nisOpenGlSource(original)
        assertTrue(adapted.contains("uvec2 blockIdx = gl_WorkGroupID.xy;"))
        assertTrue(adapted.contains("uint threadIdx = gl_LocalInvocationID.x;"))
        assertEquals(2, adapted.lines().count { "for (uint i = threadIdx * 2u;" in it })
        assertTrue(adapted.contains("0.5f - float((kSupportSize - 1) / 2)"))
        assertTrue(adapted.contains("(float(dstBlockX + NIS_BLOCK_WIDTH) + 0.5f)"))
        assertTrue(adapted.contains("(float(dstBlockY + NIS_BLOCK_HEIGHT) + 0.5f)"))
        assertTrue(adapted.contains("(float(uint(srcBlockStartX) + px) + kShift)"))
        assertTrue(adapted.contains("(float(uint(srcBlockStartY) + py) + kShift)"))
        // The later px/py are signed output coordinates, unlike the tile-loading uint variables.
        assertTrue(adapted.contains("int startEdgeMapIdx = py * kEdgeMapPitch + px;"))
        assertTrue(adapted.contains("int startTileIdx = py * kTilePitch + px;"))
    }

    @Test fun bothGatherAndTextureSamplingPathsHaveExplicitTypes() {
        val adapted = nisOpenGlSource(original)
        assertTrue(adapted.contains("#ifdef HOOKED_gather"))
        assertTrue(adapted.contains("HOOKED_gather(vec2(tx, ty), 0)"))
        assertTrue(adapted.contains("HOOKED_tex(vec2(tx + float(k) * kSrcNormX, ty + float(j) * kSrcNormY))"))
        assertTrue(adapted.contains("#define saturate(x) clamp(x, 0.0, 1.0)"))
    }

    private fun numbers(source: String): List<Double> {
        val code = source.substringBefore("//!TEXTURE coef_scaler").lines()
            .joinToString("\n") { it.substringBefore("//") }
        return Regex("""(?<![A-Za-z0-9_])(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?[fFuU]?(?![A-Za-z0-9_])""")
            .findAll(code).map { it.value.trimEnd('f', 'F', 'u', 'U').toDouble() }.toList()
    }
}

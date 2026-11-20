package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameShader
import com.swordfish.libretrodroid.ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameHdrToneMapShaderTest {
    @Test fun sdrAndVulkanFallbackNeverAlterTheSelectedFilter() {
        assertEquals(ShaderConfig.CRT, GameHdrToneMapShader.forGame(GameShader.CRT, GameHdrToneMap.OFF, true))
        assertEquals(ShaderConfig.Sharp, GameHdrToneMapShader.forGame(GameShader.SHARP, GameHdrToneMap.PQ, false))
    }

    @Test fun pqAndHlgSelectDistinctGpuConversion() {
        val pq = GameHdrToneMapShader.forGame(GameShader.PIXEL, GameHdrToneMap.PQ, true) as ShaderConfig.Custom
        val hlg = GameHdrToneMapShader.forGame(GameShader.PIXEL, GameHdrToneMap.HLG, true) as ShaderConfig.Custom
        assertEquals(1, pq.passes.size)
        assertEquals(1, hlg.passes.size)
        assertTrue(pq.passes[0].fragment.contains("const int INPUT_HLG = 0"))
        assertTrue(hlg.passes[0].fragment.contains("const int INPUT_HLG = 1"))
        assertTrue(pq.passes[0].fragment.contains("pqEotf"))
        assertTrue(pq.passes[0].fragment.contains("BT.2020 -> BT.709"))
        assertTrue(hlg.passes[0].fragment.contains("hlgScene"))
    }
}

package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameHdrToneMapShaderTest {
    @Test fun sdrAndVulkanFallbackNeverAlterTheSelectedFilter() {
        assertEquals(ShaderConfig.CRT, GameHdrToneMapShader.forGame(GameShader.CRT, GameHdrToneMap.OFF, true))
        assertEquals(ShaderConfig.Sharp, GameHdrToneMapShader.forGame(GameShader.SHARP, GameHdrToneMap.PQ, false))
    }

    @Test fun pixelPictureChoiceUsesRealNearestPreset() {
        assertEquals(ShaderConfig.Pixel, GameShader.PIXEL.config())
        val hdrPixel = GameHdrToneMapShader.forGame(
            GameShader.PIXEL, GameHdrToneMap.PQ, true) as ShaderConfig.PrependPass
        assertEquals(ShaderConfig.Pixel, hdrPixel.filter)
        assertFalse(hdrPixel.pass.linear)
    }

    @Test fun fp16BridgeIsOptInAndRequiresVulkan() {
        assertFalse(GameHdrToneMapShader.prefersFp16Bridge(GameHdrToneMap.OFF, true))
        assertFalse(GameHdrToneMapShader.prefersFp16Bridge(GameHdrToneMap.PQ, false))
        assertTrue(GameHdrToneMapShader.prefersFp16Bridge(GameHdrToneMap.PQ, true))
        assertTrue(GameHdrToneMapShader.prefersFp16Bridge(GameHdrToneMap.HLG, true))
    }

    @Test fun allOriginalNativePictureFiltersStayInChainAfterHdrConversion() {
        GameShader.entries.forEach { filter ->
            val shader = GameHdrToneMapShader.forGame(filter, GameHdrToneMap.PQ, true) as ShaderConfig.PrependPass
            assertEquals(filter.config(), shader.filter)
            val linearFilters = setOf(GameShader.SHARP, GameShader.CRT, GameShader.LCD)
            assertEquals(filter.key, filter in linearFilters, shader.pass.linear)
            assertTrue(shader.pass.fragment.contains("pqEotf"))
            assertTrue(shader.pass.fragment.contains("BT.2020 -> BT.709"))
            assertFalse(shader.pass.fragment.contains("PICTURE_MODE"))
        }
        assertEquals(ShaderConfig.CUT3, (GameHdrToneMapShader.forGame(
            GameShader.SMOOTH, GameHdrToneMap.HLG, true) as ShaderConfig.PrependPass).filter)
        assertEquals(ShaderConfig.CRT, (GameHdrToneMapShader.forGame(
            GameShader.CRT, GameHdrToneMap.PQ, true) as ShaderConfig.PrependPass).filter)
        assertEquals(ShaderConfig.LCD, (GameHdrToneMapShader.forGame(
            GameShader.LCD, GameHdrToneMap.PQ, true) as ShaderConfig.PrependPass).filter)
    }

    @Test fun pqAndHlgPrepassesPreserveIndependentTransferMath() {
        val pq = GameHdrToneMapShader.forGame(GameShader.PIXEL, GameHdrToneMap.PQ, true) as ShaderConfig.PrependPass
        val hlg = GameHdrToneMapShader.forGame(GameShader.PIXEL, GameHdrToneMap.HLG, true) as ShaderConfig.PrependPass
        assertTrue(pq.pass.fragment.contains("const int INPUT_HLG = 0"))
        assertTrue(hlg.pass.fragment.contains("const int INPUT_HLG = 1"))
        assertTrue(pq.pass.fragment.contains("pqEotf"))
        assertTrue(pq.pass.fragment.contains("BT.2020 -> BT.709"))
        assertTrue(hlg.pass.fragment.contains("hlgScene"))
    }
}

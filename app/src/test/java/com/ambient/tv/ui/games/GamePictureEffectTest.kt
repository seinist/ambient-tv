package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GamePictureDefaults
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePictureEffectTest {
    @Test fun noEffectLeavesTheFilterUnchanged() {
        assertSame(ShaderConfig.CRT, GamePictureEffect.apply(ShaderConfig.CRT, null))
        // A filter that is not an effect is never used as one.
        assertSame(ShaderConfig.CRT, GamePictureEffect.apply(ShaderConfig.CRT, GameShader.LCD))
    }

    @Test fun builtInFiltersGetTheEffectAsTheirPrefix() {
        val chained = GamePictureEffect.apply(ShaderConfig.Sharp, GameShader.NTSC_COMPOSITE) as ShaderConfig.PrependPass
        assertEquals(ShaderConfig.Sharp, chained.filter)
        assertTrue(chained.pass.linear)
        val pixel = GamePictureEffect.apply(ShaderConfig.Pixel, GameShader.COLOR_BOOST, pixel = true) as ShaderConfig.PrependPass
        assertFalse(pixel.pass.linear)
        val cut = GamePictureEffect.apply(ShaderConfig.CUT3, GameShader.VHS_SOFT) as ShaderConfig.PrependPass
        assertFalse(cut.pass.linear)
    }

    @Test fun glslFiltersGetTheEffectAsTheirFirstPass() {
        val filter = GameShader.SHARP_BILINEAR.config() as ShaderConfig.Custom
        val chained = GamePictureEffect.apply(filter, GameShader.FILM_GRAIN) as ShaderConfig.Custom
        assertEquals(filter.passes.size + 1, chained.passes.size)
        assertEquals(filter.passes, chained.passes.drop(1))
        assertFalse(chained.passes.first().linear)
    }

    @Test fun everyEffectBuildsASinglePass() {
        GamePictureDefaults.effects.forEach { effect ->
            assertTrue(effect.key, GamePictureEffect.apply(ShaderConfig.LCD, effect) is ShaderConfig.PrependPass)
        }
    }

    @Test fun hdrConversionKeepsTheOnlyPrefix() {
        val hdr = GameHdrToneMapShader.forGame(GameShader.CRT, GameHdrToneMap.PQ, true, effect = GameShader.NTSC_COMPOSITE)
            as ShaderConfig.PrependPass
        assertEquals(ShaderConfig.CRT, hdr.filter)
        val sdr = GameHdrToneMapShader.forGame(GameShader.CRT, GameHdrToneMap.OFF, true, effect = GameShader.NTSC_COMPOSITE)
            as ShaderConfig.PrependPass
        assertEquals(ShaderConfig.CRT, sdr.filter)
    }
}

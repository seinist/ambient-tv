package com.ambient.tv.ui.games

import com.ambient.tv.games.RetroShaderConfig
import com.swordfish.libretrodroid.ShaderConfig
import org.junit.Assert.*
import org.junit.Test

class BundledRetroShaderAdapterTest {
    @Test fun everyCustomPassFieldSurvivesConversion() {
        val pass = RetroShaderConfig.Custom.ShaderPass("vertex", "fragment", true, 3f, true, 1.25f)
        val native = RetroShaderConfig.Custom(listOf(pass)).toLibretroShader() as ShaderConfig.Custom
        assertEquals(ShaderConfig.Custom.ShaderPass("vertex", "fragment", true, 3f, true, 1.25f), native.passes.single())
    }

    @Test fun hdrPrefixKeepsBuiltInCutDefaultsAndSampling() {
        val pass = RetroShaderConfig.Custom.ShaderPass("v", "f")
        val native = RetroShaderConfig.PrependPass(RetroShaderConfig.CUT3, pass).toLibretroShader() as ShaderConfig.PrependPass
        assertEquals(ShaderConfig.CUT3(), native.filter)
        assertFalse(native.pass.linear)
        assertEquals(0f, native.pass.ambientIntensity, 0f)
    }
}

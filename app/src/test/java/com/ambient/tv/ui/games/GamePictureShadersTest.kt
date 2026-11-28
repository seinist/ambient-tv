package com.ambient.tv.ui.games

import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePictureShadersTest {
    @Test fun presetsUseTheLibretroDroidChain() {
        assertTrue(GameShader.CUT2.config() is ShaderConfig.CUT2)
        assertTrue(GameShader.SMOOTH.config() is ShaderConfig.CUT3)
        assertTrue(GameShader.BICUBIC.config() is ShaderConfig.Custom)
        assertTrue(GameShader.ADAPTIVE.config() is ShaderConfig.Custom)
    }

    @Test fun customEffectsSampleFromTheActualInputTexture() {
        val bicubic = (GamePictureShaders.bicubic() as ShaderConfig.Custom).passes.single()
        val sharpen = (GamePictureShaders.adaptiveSharpen() as ShaderConfig.Custom).passes.single()
        assertEquals(false, bicubic.linear)
        assertEquals(false, sharpen.linear)
        assertTrue(bicubic.fragment.contains("textureSize"))
        assertTrue(bicubic.fragment.contains("cubic("))
        assertTrue(sharpen.fragment.contains("textureSize"))
        assertTrue(sharpen.fragment.contains("smoothstep"))
    }
}

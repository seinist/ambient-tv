package com.ambient.tv.ui.games

import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig

/** One dispatch for initial playback, live changes, effects and HDR conversion. */
internal fun GameShader.config(intensity: Float = 1f): ShaderConfig = when (this) {
    GameShader.SHARP -> ShaderConfig.Sharp
    GameShader.PIXEL -> ShaderConfig.Pixel
    GameShader.SMOOTH -> ShaderConfig.CUT3
    GameShader.CUT2 -> ShaderConfig.CUT2
    GameShader.BICUBIC -> GamePictureShaders.bicubic()
    GameShader.ADAPTIVE -> GamePictureShaders.adaptiveSharpen()
    GameShader.CRT -> ShaderConfig.CRT
    GameShader.LCD -> ShaderConfig.LCD
    GameShader.SCALEFX, GameShader.SABR, GameShader.XBR_LV2, GameShader.XBRZ_FREESCALE,
    GameShader.XSAL_2X, GameShader.SUPER_2XSAI, GameShader.SUPER_EAGLE, GameShader.SCALEHQ2X ->
        GameUpscaleShaders.config(this)
    else -> GameRetroShaders.config(this, intensity)
}

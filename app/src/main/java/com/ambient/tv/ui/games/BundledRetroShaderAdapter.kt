package com.ambient.tv.ui.games

import com.ambient.tv.games.RetroShaderConfig
import com.swordfish.libretrodroid.ShaderConfig

/** Convert only at the bundled frontend boundary, not during shader selection,
 * signed-pack parsing, settings, or host composition policy.
 */
internal fun RetroShaderConfig.toLibretroShader(): ShaderConfig = when (this) {
    RetroShaderConfig.Sharp -> ShaderConfig.Sharp
    RetroShaderConfig.Pixel -> ShaderConfig.Pixel
    RetroShaderConfig.CRT -> ShaderConfig.CRT
    RetroShaderConfig.LCD -> ShaderConfig.LCD
    RetroShaderConfig.CUT2 -> ShaderConfig.CUT2()
    RetroShaderConfig.CUT3 -> ShaderConfig.CUT3()
    is RetroShaderConfig.Custom -> ShaderConfig.Custom(passes.map { it.toLibretroPass() })
    is RetroShaderConfig.PrependPass -> ShaderConfig.PrependPass(filter.toLibretroShader(), pass.toLibretroPass())
}

private fun RetroShaderConfig.Custom.ShaderPass.toLibretroPass() = ShaderConfig.Custom.ShaderPass(
    vertex = vertex,
    fragment = fragment,
    linear = linear,
    scale = scale,
    floatFramebuffer = floatFramebuffer,
    ambientIntensity = ambientIntensity,
)

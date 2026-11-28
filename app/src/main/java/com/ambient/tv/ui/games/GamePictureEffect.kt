package com.ambient.tv.ui.games

import com.ambient.tv.games.GamePictureDefaults
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig

/**
 * A second picture stage: one of the [GamePictureDefaults.effects] runs on the original frame,
 * then the chosen filter scales it exactly as it would the core's own frame. Built-in filters
 * get the effect as LibretroDroid's single prefix pass; GLSL filters get it as their first pass.
 */
internal object GamePictureEffect {
    fun apply(filter: ShaderConfig, effect: GameShader?, pixel: Boolean = false): ShaderConfig {
        if (effect == null || effect !in GamePictureDefaults.effects) return filter
        val effectPass = (GameRetroShaders.config(effect, 1f) as? ShaderConfig.Custom)?.passes?.singleOrNull()
            ?: return filter
        // The effect's output replaces the core frame, so the filter samples it as it would
        // sample the core frame: nearest for Pixel, CUT and GLSL chains, linear otherwise.
        val prefix = effectPass.copy(linear = sourceLinear(filter, pixel))
        return when (filter) {
            is ShaderConfig.Custom -> ShaderConfig.Custom(listOf(prefix) + filter.passes)
            // Already prefixed (HDR conversion): one prefix only.
            is ShaderConfig.PrependPass -> filter
            else -> ShaderConfig.PrependPass(filter, prefix)
        }
    }

    internal fun sourceLinear(filter: ShaderConfig, pixel: Boolean): Boolean = when {
        pixel -> false
        filter is ShaderConfig.Custom || filter is ShaderConfig.Pixel -> false
        filter is ShaderConfig.CUT2 || filter is ShaderConfig.CUT3 -> false
        else -> true
    }
}

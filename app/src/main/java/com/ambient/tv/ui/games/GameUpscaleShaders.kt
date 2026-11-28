package com.ambient.tv.ui.games

import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig

/**
 * Authentic, pinned Libretro pixel-art upscalers. The generated sources retain
 * upstream algorithms, default parameters and copyright notices; only their
 * graphics API interface is adapted for our GLES compositor.
 *
 * Source text is bundled once in DEX, with no shader download or native runtime.
 * Only the selected chain is assembled and cached. Fixed 2x/3x passes render at
 * their intended size before the last pass fits the game's existing viewport.
 */
internal object GameUpscaleShaders {
    private val cached = mutableMapOf<GameShader, ShaderConfig.Custom>()

    fun supports(shader: GameShader): Boolean = when (shader) {
        GameShader.SCALEFX, GameShader.SABR, GameShader.XBR_LV2,
        GameShader.XBRZ_FREESCALE, GameShader.XSAL_2X, GameShader.SUPER_2XSAI,
        GameShader.SUPER_EAGLE, GameShader.SCALEHQ2X -> true
        else -> false
    }

    @Synchronized
    fun config(shader: GameShader): ShaderConfig.Custom = cached.getOrPut(shader) {
        val sources = GameUpscaleShaderSources
        val passes = when (shader) {
            GameShader.SCALEFX -> listOf(
                pass(sources.SCALEFX_0, floatFramebuffer = true),
                pass(sources.SCALEFX_1, floatFramebuffer = true),
                pass(sources.SCALEFX_2),
                pass(sources.SCALEFX_3),
                pass(sources.SCALEFX_4, scale = 3f),
                pass(sources.STOCK),
            )
            GameShader.SABR -> listOf(pass(sources.SABR))
            GameShader.XBR_LV2 -> listOf(pass(sources.XBR_LV2))
            GameShader.XBRZ_FREESCALE -> listOf(pass(sources.XBRZ_FREESCALE))
            GameShader.XSAL_2X -> fixed2x(sources.XSAL_2X, linearResolve = true)
            GameShader.SUPER_2XSAI -> fixed2x(sources.SUPER_2XSAI)
            GameShader.SUPER_EAGLE -> fixed2x(sources.SUPER_EAGLE)
            GameShader.SCALEHQ2X -> fixed2x(sources.SCALEHQ2X)
            else -> error("Not a Libretro upscaler: ${shader.key}")
        }
        // A hardware core can render into a padded/flipped FBO. Normalize the
        // actual content with its native UVs exactly once before neighbours or
        // ScaleFX's relative source reference sample it using normalized UVs.
        ShaderConfig.Custom(listOf(pass(sources.STOCK)) + passes)
    }

    private fun fixed2x(source: String, linearResolve: Boolean = false) = listOf(
        // LibretroDroid's linear flag controls sampling of this pass's OUTPUT;
        // the source image remains nearest, exactly as the upstream presets ask.
        pass(source, scale = 2f, linear = linearResolve),
        pass(GameUpscaleShaderSources.STOCK),
    )

    private fun pass(
        source: String, scale: Float = 1f, linear: Boolean = false, floatFramebuffer: Boolean = false,
    ) = ShaderConfig.Custom.ShaderPass(
        vertex = "#version 100\n#define VERTEX\n$source",
        fragment = "#version 100\n#define FRAGMENT\n$source",
        linear = linear,
        scale = scale,
        floatFramebuffer = floatFramebuffer,
    )
}

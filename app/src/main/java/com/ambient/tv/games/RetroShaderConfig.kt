package com.ambient.tv.games

/** Host-owned shader description, independent of the selected frontend classloader.
 * Built-in entries map to the unmodified pinned frontend defaults. A custom pass
 * carries every field consumed by the patched native shader bridge.
 */
sealed interface RetroShaderConfig {
    data object Sharp : RetroShaderConfig
    data object Pixel : RetroShaderConfig
    data object CRT : RetroShaderConfig
    data object LCD : RetroShaderConfig
    data object CUT2 : RetroShaderConfig
    data object CUT3 : RetroShaderConfig

    data class PrependPass(val filter: RetroShaderConfig, val pass: Custom.ShaderPass) : RetroShaderConfig
    data class Custom(val passes: List<ShaderPass>) : RetroShaderConfig {
        data class ShaderPass(
            val vertex: String,
            val fragment: String,
            val linear: Boolean = false,
            val scale: Float = 1f,
            val floatFramebuffer: Boolean = false,
            val ambientIntensity: Float = 0f,
        )
    }
}

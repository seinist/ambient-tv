package com.ambient.tv.ui.player.backend

/**
 * Use the pinned non-quantized CuNNy kernels' float32 fallback on mpv's OpenGL renderer.
 * Its wrapper emits uniforms before the hook body, where a #extension directive is illegal.
 * The quantized kernels require SPIR-V intrinsics and remain exclusive to gpu-next/Vulkan.
 */
internal fun cunnyOpenGlSource(source: String): String = source.replace(
    Regex(
        """#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable\s+#ifdef GL_EXT_shader_explicit_arithmetic_types_float16.*?#else\s+(.*?)#endif""",
        RegexOption.DOT_MATCHES_ALL,
    ),
) { it.groupValues[1] }

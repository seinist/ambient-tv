package com.ambient.tv.ui.player.backend

/**
 * Shared GLES adaptation for the pinned ArtCNN compute bodies in MPV and Media3.
 * Use upstream's float32 fallback and GLES-compatible local declarations. Preserve
 * global constants because they size the compute workgroup's shared arrays.
 */
internal fun artCnnGlesSource(source: String): String = source
    .replace(Regex("""#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable\s+#ifdef GL_EXT_shader_explicit_arithmetic_types_float16.*?#else\s+(.*?)#endif""", RegexOption.DOT_MATCHES_ALL)) {
        it.groupValues[1]
    }
    // All local declarations in these pinned kernels are indented; globals are not.
    .replace(Regex("""(?m)^([ \t]+)const """), "$1")
    .replace("uvec2 local_xy = gl_LocalInvocationID.xy;", "ivec2 local_xy = ivec2(gl_LocalInvocationID.xy);")
    .replace("for (uint ", "for (int ")

/** MPV's final LUMA hook is single-channel; feature hooks retain all four components. */
internal fun artCnnOpenGlSource(source: String): String = artCnnGlesSource(source)
    .replace(Regex("""(//!DESC ArtCNN[^\n]*\(Depth-To-Space\).*?)//!COMPONENTS 4""", RegexOption.DOT_MATCHES_ALL), "$1//!COMPONENTS 1")

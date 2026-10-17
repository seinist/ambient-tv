package com.ambient.tv.ui.player.backend

/** Adapt the pinned ArtCNN kernels for mpv vo=gpu's OpenGL shader wrapper.
 * The wrapper inserts declarations before the hook, where #extension is illegal.
 * Use the upstream float32 fallback and GLSL ES-compatible local declarations.
 * Keep global constants: they size the compute workgroup's shared arrays.
 */
internal fun artCnnOpenGlSource(source: String): String = source
    .replace(Regex("""#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable\s+#ifdef GL_EXT_shader_explicit_arithmetic_types_float16.*?#else\s+(.*?)#endif""", RegexOption.DOT_MATCHES_ALL)) {
        it.groupValues[1]
    }
    // All local declarations in these pinned kernels are indented; globals are not.
    .replace(Regex("""(?m)^([ \t]+)const """), "$1")
    .replace("uvec2 local_xy = gl_LocalInvocationID.xy;", "ivec2 local_xy = ivec2(gl_LocalInvocationID.xy);")
    .replace("for (uint ", "for (int ")
    // The final pass replaces the single-channel LUMA plane, unlike intermediate
    // feature maps which need all four components. vo=gpu rejects four here.
    .replace(Regex("""(//!DESC ArtCNN[^\n]*\(Depth-To-Space\).*?)//!COMPONENTS 4""", RegexOption.DOT_MATCHES_ALL), "$1//!COMPONENTS 1")

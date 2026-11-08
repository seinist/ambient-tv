package com.ambient.tv.ui.player.backend

/**
 * Keep the pinned Anime4K models, but express their hooks portably for Android GLES.
 * GLSL ES 1.00 does not require dynamic vector indexing in fragment shaders. A rejected
 * depth-to-space pass makes MPV display its blue error frame instead of the video.
 * Media3's GLSL 100 wrapper needs the same adaptation as MPV's GLES wrapper.
 */
internal fun anime4kGlesSource(source: String): String = source
    // Explicit vector operands also avoid older Android drivers' scalar/vector max overloads.
    // Only the ReLU macros in these pinned models contain this suffix; weights stay untouched.
    .replace("), 0.0))", "), vec4(0.0)))")
    .replace(
        """    ivec2 i0 = ivec2(f0 * vec2(2.0));
    float c0 = conv2d_last_tf_tex((vec2(0.5) - f0) * conv2d_last_tf_pt + conv2d_last_tf_pos)[i0.y * 2 + i0.x];""",
        """    vec4 residual = conv2d_last_tf_tex((vec2(0.5) - f0) * conv2d_last_tf_pt + conv2d_last_tf_pos);
    float c0 = f0.y < 0.5
        ? (f0.x < 0.5 ? residual.r : residual.g)
        : (f0.x < 0.5 ? residual.b : residual.a);""",
    )

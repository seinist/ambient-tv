package com.ambient.tv.ui.player.backend

/**
 * FSRCNNX packs its four predicted pixels in X-major order. Use fixed components so
 * its aggregation pass also works on GLES fragment compilers without dynamic vector indexing.
 * The texture sample, learned weights and 2x pixel arrangement stay the same in both renderers.
 */
internal fun fsrcnnxGlesSource(source: String): String = source.replace(
    """ivec2 index = ivec2(fcoord * vec2(2));
vec4 res = SUBCONV1_tex(base);
return vec4(res[index.x * 2 + index.y], 0, 0, 1);""",
    """vec4 res = SUBCONV1_tex(base);
float prediction = fcoord.x < 0.5
    ? (fcoord.y < 0.5 ? res.r : res.g)
    : (fcoord.y < 0.5 ? res.b : res.a);
return vec4(prediction, 0, 0, 1);""",
)

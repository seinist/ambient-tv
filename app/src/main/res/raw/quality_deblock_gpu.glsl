// Ambient TV GPU deblock. Original shader, CC0 1.0 (public domain dedication).
// RGB pass at decoded source resolution, before scaling/sharpening.
// This is optional post-decoding artifact cleanup, not codec in-loop deblocking.
// MAIN runs after RGB conversion, preserving hardware zero-copy where available.
// The application enables this hook for SDR GPU output only.

//!HOOK MAIN
//!BIND HOOKED
//!DESC Ambient TV GPU deblock

#define DEBLOCK_STRENGTH 0.50

// Preserve genuine edges: only blend across a weak 8x8 boundary when both
// neighbourhoods are locally smooth. No sampling outside the input image.
highp vec3 smoothBlockEdge(highp vec2 direction, highp vec3 center) {
    highp vec3 across = HOOKED_texOff(direction).rgb;
    highp vec3 inside = HOOKED_texOff(-direction).rgb;
    highp vec3 outside = HOOKED_texOff(2.0 * direction).rgb;
    // Contrast/edge decisions on perceptual luma, but correct all RGB channels.
    highp vec3 weights = vec3(0.2126, 0.7152, 0.0722);
    float discontinuity = abs(dot(center - across, weights));
    float local = max(abs(dot(center - inside, weights)),
                      abs(dot(across - outside, weights)));
    float flatness = (1.0 - smoothstep(0.06, 0.22, discontinuity)) *
                     (1.0 - smoothstep(0.035, 0.14, local));
    return mix(center, 0.5 * (center + across), DEBLOCK_STRENGTH * flatness);
}

vec4 hook() {
    highp vec2 pixel = floor(HOOKED_pos * HOOKED_size);
    highp vec2 block = mod(pixel, 8.0);
    vec4 color = HOOKED_tex(HOOKED_pos);
    // Guard 2-pixel neighbour taps at the source-frame borders.
    if (pixel.x > 1.0 && pixel.x < HOOKED_size.x - 2.0) {
        if (block.x < 1.0)
            color.rgb = smoothBlockEdge(vec2(-1.0, 0.0), color.rgb);
        else if (block.x > 6.0)
            color.rgb = smoothBlockEdge(vec2(1.0, 0.0), color.rgb);
    }
    if (pixel.y > 1.0 && pixel.y < HOOKED_size.y - 2.0) {
        if (block.y < 1.0)
            color.rgb = smoothBlockEdge(vec2(0.0, -1.0), color.rgb);
        else if (block.y > 6.0)
            color.rgb = smoothBlockEdge(vec2(0.0, 1.0), color.rgb);
    }
    return color;
}

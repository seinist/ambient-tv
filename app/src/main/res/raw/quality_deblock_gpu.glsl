// Ambient TV GPU deblock. Original shader, CC0 1.0 (public domain dedication).
// One luma pass at decoded source resolution, before scaling/sharpening.
// This is optional post-decoding artifact cleanup, not codec in-loop deblocking.
// The application enables this hook for SDR GPU output only.

//!HOOK LUMA
//!BIND HOOKED
//!DESC Ambient TV GPU deblock

#define DEBLOCK_STRENGTH 0.50

// Preserve genuine edges: only blend across a weak 8x8 boundary when both
// neighbourhoods are locally smooth. No sampling outside the input image.
highp float smoothBlockEdge(highp vec2 direction, highp float center) {
    float across = HOOKED_texOff(direction).r;
    float inside = HOOKED_texOff(-direction).r;
    float outside = HOOKED_texOff(2.0 * direction).r;
    float discontinuity = abs(center - across);
    float local = max(abs(center - inside), abs(across - outside));
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
            color.r = smoothBlockEdge(vec2(-1.0, 0.0), color.r);
        else if (block.x > 6.0)
            color.r = smoothBlockEdge(vec2(1.0, 0.0), color.r);
    }
    if (pixel.y > 1.0 && pixel.y < HOOKED_size.y - 2.0) {
        if (block.y < 1.0)
            color.r = smoothBlockEdge(vec2(0.0, -1.0), color.r);
        else if (block.y > 6.0)
            color.r = smoothBlockEdge(vec2(0.0, 1.0), color.r);
    }
    return color;
}

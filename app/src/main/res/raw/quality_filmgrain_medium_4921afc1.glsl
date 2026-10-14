// Ambient TV film grain (medium). Written for Ambient TV, released under CC0 1.0.
// Fragment-only (no compute) so it runs on OpenGL ES and Vulkan alike:
// per-frame Gaussian-like noise, smoothed over a 3x3 neighbourhood, added to luma.

//!HOOK LUMA
//!BIND HOOKED
//!DESC Ambient TV film grain (medium)

#define INTENSITY 0.06

highp float grain_hash(highp vec2 p) {
    highp vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

highp float grain_noise(highp vec2 p, highp float seed) {
    // Sum of two uniforms minus one: zero-mean, triangular, cheap Gaussian stand-in.
    return grain_hash(p + seed * 113.0) + grain_hash(p * 1.37 + seed * 71.0) - 1.0;
}

vec4 hook() {
    highp vec2 p = floor(HOOKED_pos * HOOKED_size);
    highp float seed = fract(random) * 1000.0;
    highp float g = 0.0;
    g += 4.0 * grain_noise(p, seed);
    g += 2.0 * (grain_noise(p + vec2(1.0, 0.0), seed) + grain_noise(p - vec2(1.0, 0.0), seed)
              + grain_noise(p + vec2(0.0, 1.0), seed) + grain_noise(p - vec2(0.0, 1.0), seed));
    g += grain_noise(p + vec2(1.0, 1.0), seed) + grain_noise(p - vec2(1.0, 1.0), seed)
       + grain_noise(p + vec2(1.0, -1.0), seed) + grain_noise(p - vec2(1.0, -1.0), seed);
    g /= 16.0;
    vec4 color = HOOKED_tex(HOOKED_pos);
    color.x += INTENSITY * 2.0 * g;
    return color;
}

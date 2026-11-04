// Ambient TV colour matrix correction. Written for Ambient TV, released under CC0 1.0.
// For decoders/drivers that converted BT.601 (SD) video with the BT.709 matrix:
// R'G'B'(601) = M601 * inverse(M709) * R'G'B'(709), on gamma-encoded RGB.

//!HOOK MAIN
//!BIND HOOKED
//!DESC Ambient TV colour matrix BT.709 -> BT.601

vec4 hook() {
    vec4 c = HOOKED_texOff(vec2(0.0));
    vec3 o = vec3(
        dot(vec3( 0.913600,  0.078478,  0.007922), c.rgb),
        dot(vec3(-0.105040,  1.172167, -0.067127), c.rgb),
        dot(vec3( 0.009578,  0.032222,  0.958200), c.rgb));
    return vec4(clamp(o, 0.0, 1.0), c.a);
}

// Ambient TV colour matrix correction. Written for Ambient TV, released under CC0 1.0.
// For decoders/drivers that converted BT.709 video with the BT.601 matrix (red, too warm
// picture): R'G'B'(709) = M709 * inverse(M601) * R'G'B'(601), on gamma-encoded RGB.

//!HOOK MAIN
//!BIND HOOKED
//!DESC Ambient TV colour matrix BT.601 -> BT.709

vec4 hook() {
    vec4 c = HOOKED_texOff(vec2(0.0));
    vec3 o = vec3(
        dot(vec3( 1.086400, -0.072349, -0.014051), c.rgb),
        dot(vec3( 0.096546,  0.845052,  0.058402), c.rgb),
        dot(vec3(-0.014106, -0.027694,  1.041800), c.rgb));
    return vec4(clamp(o, 0.0, 1.0), c.a);
}

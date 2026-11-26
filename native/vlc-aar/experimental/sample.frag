#version 450
layout(set = 0, binding = 0) uniform sampler2D decoder_image;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 rgb;
void main() {
    // Immutable Vulkan sampler performs YCbCr conversion, not transfer-function
    // conversion. Preserve nonlinear RGB for libplacebo's SDR color management.
    rgb = vec4(texture(decoder_image, uv).rgb, 1.0);
}

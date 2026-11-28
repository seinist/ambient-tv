package com.ambient.tv.ui.games

import com.ambient.tv.games.RetroShaderConfig as ShaderConfig

/**
 * GLES picture options built from the LibretroDroid shader chain. textureSize is a
 * built-in LibretroDroid uniform for each pass. These effects apply AFTER the
 * Vulkan-to-GLES bridge, not inside the experimental native WSI swapchain.
 */
internal object GamePictureShaders {
    private const val VERTEX = """
        attribute vec4 vPosition;
        attribute vec2 vCoordinate;
        varying highp vec2 coords;
        void main() {
            coords = vCoordinate;
            gl_Position = vPosition;
        }
    """

    /** Four neighbours + original sample; no external textures, no driver extensions. */
    fun adaptiveSharpen(): ShaderConfig = ShaderConfig.Custom(listOf(
        ShaderConfig.Custom.ShaderPass(vertex = VERTEX, fragment = ADAPTIVE, linear = false),
    ))

    /** Separable Catmull-Rom cubic with 16 texel reads; opt-in on slower devices. */
    fun bicubic(): ShaderConfig = ShaderConfig.Custom(listOf(
        ShaderConfig.Custom.ShaderPass(vertex = VERTEX, fragment = BICUBIC, linear = false),
    ))

    internal const val ADAPTIVE = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D texture;
        uniform vec2 textureSize;
        varying highp vec2 coords;
        void main() {
            vec2 stepSize = 1.0 / max(textureSize, vec2(1.0));
            vec4 center = texture2D(texture, coords);
            vec3 n = texture2D(texture, coords + vec2(0.0, -stepSize.y)).rgb;
            vec3 s = texture2D(texture, coords + vec2(0.0,  stepSize.y)).rgb;
            vec3 e = texture2D(texture, coords + vec2( stepSize.x, 0.0)).rgb;
            vec3 w = texture2D(texture, coords + vec2(-stepSize.x, 0.0)).rgb;
            vec3 low = min(center.rgb, min(min(n, s), min(e, w)));
            vec3 high = max(center.rgb, max(max(n, s), max(e, w)));
            float contrast = dot(high - low, vec3(0.299, 0.587, 0.114));
            float strength = 0.65 * smoothstep(0.025, 0.3, contrast);
            vec3 edge = center.rgb - (n + s + e + w) * 0.25;
            vec3 sharpened = center.rgb + strength * edge;
            gl_FragColor = vec4(clamp(sharpened, max(vec3(0.0), low - vec3(0.075)),
                min(vec3(1.0), high + vec3(0.075))), center.a);
        }
    """

    internal const val BICUBIC = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D texture;
        uniform vec2 textureSize;
        varying highp vec2 coords;
        float cubic(float x) {
            x = abs(x);
            if (x < 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
            if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
            return 0.0;
        }
        void main() {
            vec2 texel = coords * max(textureSize, vec2(1.0)) - 0.5;
            vec2 origin = floor(texel);
            vec2 fraction = texel - origin;
            vec4 sum = vec4(0.0);
            for (int j = -1; j <= 2; ++j) {
                for (int i = -1; i <= 2; ++i) {
                    vec2 offset = vec2(float(i), float(j));
                    vec2 uv = (origin + offset + 0.5) / max(textureSize, vec2(1.0));
                    float weight = cubic(float(i) - fraction.x) * cubic(float(j) - fraction.y);
                    sum += texture2D(texture, clamp(uv, vec2(0.0), vec2(1.0))) * weight;
                }
            }
            gl_FragColor = clamp(sum, 0.0, 1.0);
        }
    """
}

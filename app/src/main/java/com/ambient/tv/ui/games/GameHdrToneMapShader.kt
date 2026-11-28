package com.ambient.tv.ui.games

import com.ambient.tv.games.GameHdrToneMap
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig

/**
 * The Vulkan core can export into an optional FP16 AHardwareBuffer for GLES when supported.
 * A requested PQ/HLG INPUT -> SDR conversion runs only when the native renderer
 * confirms the matching transfer for that frame. Ordinary or rejected HDR input
 * passes through unchanged. Most libretro cores output SDR: default is off.
 *
 * Unavailable FP16 formats or GLES import failures fall back to RGBA8 for the session.
 * Keep the normal picture filter stored and restore it when HDR conversion is off.
 */
internal object GameHdrToneMapShader {
    /** FP16 only for explicitly selected HDR input while the core uses Vulkan. */
    fun prefersFp16Bridge(input: GameHdrToneMap, vulkan: Boolean): Boolean =
        vulkan && input != GameHdrToneMap.OFF

    fun forGame(
        filter: GameShader, input: GameHdrToneMap, vulkan: Boolean, intensity: Float = 1f, effect: GameShader? = null,
    ): ShaderConfig = forConfig(filter.config(intensity), input, vulkan, pixel = filter == GameShader.PIXEL, effect = effect)

    /**
     * Same mandatory HDR-to-SDR conversion for built-ins and authenticated external GLSL.
     * The optional [effect] runs in front of the filter; the native chain keeps a single
     * prefix, so while the HDR conversion occupies it the effect is left out.
     */
    fun forConfig(
        filter: ShaderConfig, input: GameHdrToneMap, vulkan: Boolean, pixel: Boolean = false, effect: GameShader? = null,
    ): ShaderConfig {
        if (!vulkan || input == GameHdrToneMap.OFF) return GamePictureEffect.apply(filter, effect, pixel)
        // Convert PQ/HLG to SDR in a prefix, then execute the exact original
        // native CRT/LCD/CUT3/Sharp/Pixel picture shader chain unchanged.
        return ShaderConfig.PrependPass(
            filter = filter,
            pass = ShaderConfig.Custom.ShaderPass(
                vertex = VERTEX,
                fragment = fragment(input),
                // Pixel-art edge detectors need the same exact texels after
                // decoding as they get from an SDR core. CUT and custom GLSL
                // chains must not silently gain bilinear input here either.
                linear = GamePictureEffect.sourceLinear(filter, pixel),
            ),
        )
    }

    private const val VERTEX = """
        attribute vec4 vPosition;
        attribute vec2 vCoordinate;
        varying highp vec2 coords;
        void main() {
            coords = vCoordinate;
            gl_Position = vPosition;
        }
    """

    internal fun fragment(input: GameHdrToneMap): String {
        require(input != GameHdrToneMap.OFF)
        val hlg = if (input == GameHdrToneMap.HLG) 1 else 0
        return """
            precision highp float;
            // GLES sampler precision is independent of float precision. Keep
            // FP16/PQ source channels intact before the transfer conversion.
            uniform highp sampler2D texture;
            // Set by the native renderer for this exact frame. A saved menu
            // choice must never turn an ordinary SDR core frame into PQ/HLG.
            uniform int ambientCoreTransfer;
            varying highp vec2 coords;
            const int INPUT_HLG = $hlg;

            // SMPTE ST 2084 EOTF: BT.2020 PQ -> absolute cd/m².
            float pqEotf(float code) {
                const float m1 = 0.1593017578125;
                const float m2 = 78.84375;
                const float c1 = 0.8359375;
                const float c2 = 18.8515625;
                const float c3 = 18.6875;
                float p = pow(clamp(code, 0.0, 1.0), 1.0 / m2);
                return 10000.0 * pow(max(p - c1, 0.0) / max(c2 - c3 * p, 0.00001), 1.0 / m1);
            }

            // ITU-R BT.2100 HLG inverse OETF, approximate 1000-nit OOTF.
            float hlgScene(float v) {
                const float a = 0.17883277;
                const float b = 0.28466892;
                const float c = 0.55991073;
                return v <= 0.5 ? (v * v) / 3.0 : (exp((v - c) / a) + b) / 12.0;
            }

            vec3 encodedToNits(vec3 code) {
                if (INPUT_HLG == 1) {
                    vec3 scene = vec3(hlgScene(code.r), hlgScene(code.g), hlgScene(code.b));
                    float sceneLuma = dot(scene, vec3(0.2627, 0.6780, 0.0593));
                    return scene * pow(max(sceneLuma, 0.00001), 0.2) * 1000.0;
                }
                return vec3(pqEotf(code.r), pqEotf(code.g), pqEotf(code.b));
            }

            vec3 sRgbEncode(vec3 linearRgb) {
                vec3 x = clamp(linearRgb, 0.0, 1.0);
                vec3 low = 12.92 * x;
                vec3 high = 1.055 * pow(x, vec3(1.0 / 2.4)) - 0.055;
                return mix(low, high, step(vec3(0.0031308), x));
            }

            void main() {
                vec4 source = texture2D(texture, coords);
                if (ambientCoreTransfer != (INPUT_HLG == 1 ? 2 : 1)) {
                    gl_FragColor = source;
                    return;
                }
                vec3 nits2020 = encodedToNits(source.rgb);
                float luminance = dot(nits2020, vec3(0.2627, 0.6780, 0.0593));

                // Luminance-preserving extended Reinhard (203-nit reference white).
                float ratio = luminance / (luminance + 203.0);
                float white = 1000.0 / (1000.0 + 203.0);
                float mappedLuma = ratio / white;
                vec3 mapped2020 = nits2020 * (mappedLuma / max(luminance, 0.00001));

                // Linear BT.2020 -> BT.709 (D65), then SDR sRGB transfer.
                mat3 to709 = mat3(
                     1.6605, -0.1246, -0.0182,
                    -0.5876,  1.1329, -0.1006,
                    -0.0728, -0.0083,  1.1187
                );
                // Original native picture filter(s) process these SDR values in
                // their own passes, never in encoded PQ/HLG source space.
                gl_FragColor = vec4(sRgbEncode(max(to709 * mapped2020, 0.0)), source.a);
            }
        """.trimIndent()
    }
}

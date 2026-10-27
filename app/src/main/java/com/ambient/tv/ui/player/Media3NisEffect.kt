@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.ambient.tv.ui.player.backend.MPV_IMAGE_SHADERS
import java.util.Locale

/*
 * NVIDIA Image Scaling v1.0.2 (NVScaler, MIT) for Media3: the algorithm of the bundled MPV compute
 * shader, per output pixel as a GLSL ES 1.00 fragment shader. MPV's workgroup shares the luma tile,
 * edge map and filter banks; here every pixel reads its own 6x6 luma support, derives the 2x2
 * edge map from it and fetches its phases from the same coef_scaler/coef_usm tables, which come
 * from the MPV file as one 4x64 table packed into two RGBA8 textures (16 bit per coefficient).
 *
 * MPV runs it on the luma plane; Media3 has RGB, so the scaled luma is computed from Rec.709 luma
 * and its correction against the bilinear tap is added to the bilinear RGB picture, which is what
 * the MPV shader does with `op.x += corr` on the luma plane (chroma stays with MPV's scaler).
 */

/** The NVScaler constants of the MPV shader for [sharpness] (MPV: SHARPNESS 0.25) in SDR mode. */
internal data class Media3NisConstants(
    val detectRatio: Float,
    val detectThres: Float,
    val minContrastRatio: Float,
    val ratioNorm: Float,
    val sharpStartY: Float,
    val sharpScaleY: Float,
    val sharpStrengthMin: Float,
    val sharpStrengthScale: Float,
    val sharpLimitMin: Float,
    val sharpLimitScale: Float,
)

internal const val MEDIA3_NIS_SHARPNESS = 0.25f

/** Same formulas as the MPV shader's const block with NIS_HDR_MODE 0. */
internal fun media3NisConstants(sharpness: Float = MEDIA3_NIS_SHARPNESS): Media3NisConstants {
    val slider = sharpness.coerceIn(0f, 1f) - 0.5f
    val maxScale = if (slider >= 0f) 1.25f else 1.75f
    val minScale = if (slider >= 0f) 1.25f else 1.0f
    val limitScale = if (slider >= 0f) 1.25f else 1.0f
    val minContrast = 2f
    val maxContrast = 10f
    val sharpStartY = 0.45f
    val sharpEndY = 0.9f
    val strengthMin = maxOf(0f, 0.4f + slider * minScale * 1.2f)
    val strengthMax = 1.6f + slider * maxScale * 1.8f
    val limitMin = maxOf(0.1f, 0.14f + slider * limitScale * 0.32f)
    val limitMax = 0.5f + slider * limitScale * 0.6f
    return Media3NisConstants(
        detectRatio = 2f * 1127f / 1024f,
        detectThres = 64f / 1024f,
        minContrastRatio = minContrast,
        ratioNorm = 1f / (maxContrast - minContrast),
        sharpStartY = sharpStartY,
        sharpScaleY = 1f / (sharpEndY - sharpStartY),
        sharpStrengthMin = strengthMin,
        sharpStrengthScale = strengthMax - strengthMin,
        sharpLimitMin = limitMin,
        sharpLimitScale = limitMax - limitMin,
    )
}

/** coef_scaler and coef_usm of the MPV file side by side: 4 texels per phase, 64 phases. */
internal class Media3NisCoefficients(val values: FloatArray) {
    val width = 4
    val height = 64
}

/** Null when the MPV file does not carry the two 2x64 filter banks. */
internal fun media3NisCoefficients(mpvShader: String): Media3NisCoefficients? {
    val luts = media3RavuLuts(mpvShader) ?: return null
    val scaler = luts["coef_scaler"]?.takeIf { it.width == 2 && it.height == 64 } ?: return null
    val usm = luts["coef_usm"]?.takeIf { it.width == 2 && it.height == 64 } ?: return null
    val values = FloatArray(4 * 64 * 4)
    for (phase in 0 until 64) {
        for (texel in 0 until 2) {
            for (c in 0 until 4) {
                values[(phase * 4 + texel) * 4 + c] = scaler.values[(phase * 2 + texel) * 4 + c]
                values[(phase * 4 + 2 + texel) * 4 + c] = usm.values[(phase * 2 + texel) * 4 + c]
            }
        }
    }
    return Media3NisCoefficients(values)
}

@Volatile private var cachedNisCoefficients: Media3NisCoefficients? = null

private fun nisCoefficients(context: Context): Media3NisCoefficients? = cachedNisCoefficients ?: run {
    val resource = MPV_IMAGE_SHADERS["nis"]?.resourceId ?: return null
    val text = runCatching { context.resources.openRawResource(resource).bufferedReader().use { it.readText() } }.getOrNull()
    text?.let(::media3NisCoefficients)?.also { cachedNisCoefficients = it }
}

/** NVScaler fitted to the output size; includes its own sharpening like FSR's RCAS. */
internal class Media3NisEffect(private val outputWidth: Int, private val outputHeight: Int) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight) == (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        media3RequireFragmentHighp("NIS")
        val coefficients = nisCoefficients(context)
            ?: throw VideoFrameProcessingException("NIS filter banks unavailable")
        return Media3NisShaderProgram(useHdr, coefficients, outputWidth, outputHeight)
    }
}

private class Media3NisShaderProgram(
    useHighPrecision: Boolean,
    coefficients: Media3NisCoefficients,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : BaseGlShaderProgram(useHighPrecision, /* texturePoolCapacity= */ 1) {
    private val program = media3GlProgram(media3NisFragmentShader(media3NisConstants()))
    private val planes = media3RavuLutPlanes(coefficients.values)
    private val coefHigh = media3LutTexture(planes.high, coefficients.width, coefficients.height)
    private val coefLow = media3LutTexture(planes.low, coefficients.width, coefficients.height)
    private var inputSize = floatArrayOf(1f, 1f)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inputSize = floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat())
        return media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight).let { Size(it.first, it.second) }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            media3LinearFilter(inputTexId)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uCoefHigh", coefHigh, 1)
            program.setSamplerTexIdUniform("uCoefLow", coefLow, 2)
            program.setFloatsUniformIfPresent("uInputSize", inputSize)
            program.setFloatsUniformIfPresent("uCoef", floatArrayOf(planes.min, planes.range))
            media3DrawQuad(program)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            GlUtil.deleteTexture(coefHigh)
            GlUtil.deleteTexture(coefLow)
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

private fun Float.glsl(): String = String.format(Locale.ROOT, "%.9f", this)

/** The fragment shader with [c] baked in as constants, like MPV's compile-time const block. */
internal fun media3NisFragmentShader(c: Media3NisConstants): String = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform sampler2D uCoefHigh;
uniform sampler2D uCoefLow;
uniform vec2 uInputSize;
uniform vec2 uCoef;
varying vec2 vTexSamplingCoord;

const float kDetectRatio = ${c.detectRatio.glsl()};
const float kDetectThres = ${c.detectThres.glsl()};
const float kMinContrastRatio = ${c.minContrastRatio.glsl()};
const float kRatioNorm = ${c.ratioNorm.glsl()};
const float kSharpStartY = ${c.sharpStartY.glsl()};
const float kSharpScaleY = ${c.sharpScaleY.glsl()};
const float kSharpStrengthMin = ${c.sharpStrengthMin.glsl()};
const float kSharpStrengthScale = ${c.sharpStrengthScale.glsl()};
const float kSharpLimitMin = ${c.sharpLimitMin.glsl()};
const float kSharpLimitScale = ${c.sharpLimitScale.glsl()};
const float kContrastBoost = 1.0;
const float kEps = 1.0;

float lumaOf(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float lumaAt(vec2 texel) { return lumaOf(texture2D(uTexSampler, (texel + 0.5) / uInputSize).rgb); }

// One texel of the 4x64 coefficient table: columns 0-1 coef_scaler, 2-3 coef_usm.
vec4 coef(float column, float phase) {
  vec2 uv = vec2((column + 0.5) / 4.0, (phase + 0.5) / 64.0);
  // 16-bit value from two bytes; factors below 1 keep mediump in range.
  vec4 q = texture2D(uCoefHigh, uv) * (65280.0 / 65535.0) + texture2D(uCoefLow, uv) * (255.0 / 65535.0);
  return q * uCoef.y + uCoef.x;
}

// GetEdgeMap on the 3x3 neighbourhood aRC (row R, column C) around one source pixel.
vec4 edgeMap(float a00, float a01, float a02, float a10, float a12, float a20, float a21, float a22) {
  float g0 = abs(a00 + a01 + a02 - a20 - a21 - a22);
  float g45 = abs(a10 + a00 + a01 - a21 - a22 - a12);
  float g90 = abs(a00 + a10 + a20 - a02 - a12 - a22);
  float g135 = abs(a10 + a20 + a21 - a01 - a02 - a12);
  float g090Max = max(g0, g90);
  float g090Min = min(g0, g90);
  float g45135Max = max(g45, g135);
  float g45135Min = min(g45, g135);
  if (g090Max + g45135Max == 0.0) return vec4(0.0);
  float e090 = min(g090Max / (g090Max + g45135Max), 1.0);
  float e45135 = 1.0 - e090;
  bool c090 = (g090Max > (g090Min * kDetectRatio)) && (g090Max > kDetectThres) && (g090Max > g45135Min);
  bool c45135 = (g45135Max > (g45135Min * kDetectRatio)) && (g45135Max > kDetectThres) && (g45135Max > g090Min);
  bool cg090 = g090Max == g0;
  bool cg45135 = g45135Max == g45;
  float fe090 = (c090 && c45135) ? e090 : 1.0;
  float fe45135 = (c090 && c45135) ? e45135 : 1.0;
  return vec4(
    (c090 && cg090) ? fe090 : 0.0,
    (c090 && !cg090) ? fe090 : 0.0,
    (c45135 && cg45135) ? fe45135 : 0.0,
    (c45135 && !cg45135) ? fe45135 : 0.0);
}

float calcLti(float p0, float p1, float p2, float p3, float p4, float p5, float phase) {
  bool selector = phase <= 32.0;
  float sel = selector ? p0 : p3;
  float aMin = min(min(p1, p2), sel);
  float aMax = max(max(p1, p2), sel);
  sel = selector ? p2 : p5;
  float bMin = min(min(p3, p4), sel);
  float bMax = max(max(p3, p4), sel);
  float aCont = aMax - aMin;
  float bCont = bMax - bMin;
  float contRatio = max(aCont, bCont) / (min(aCont, bCont) + kEps);
  return (1.0 - clamp((contRatio - kMinContrastRatio) * kRatioNorm, 0.0, 1.0)) * kContrastBoost;
}

// EvalPoly6: scaler taps plus luma-ramped, limited and LTI-damped USM taps.
float evalPoly6(vec4 a, vec2 b, float phase) {
  vec4 s0 = coef(0.0, phase);
  vec4 s1 = coef(1.0, phase);
  vec4 u0 = coef(2.0, phase);
  vec4 u1 = coef(3.0, phase);
  float y = dot(s0, a) + s1.x * b.x + s1.y * b.y;
  float yUsm = dot(u0, a) + u1.x * b.x + u1.y * b.y;
  float yScale = 1.0 - clamp((y - kSharpStartY) * kSharpScaleY, 0.0, 1.0);
  yUsm *= yScale * kSharpStrengthScale + kSharpStrengthMin;
  float limit = (yScale * kSharpLimitScale + kSharpLimitMin) * y;
  yUsm = min(limit, max(-limit, yUsm));
  yUsm *= calcLti(a.x, a.y, a.z, a.w, b.x, b.y, phase);
  return y + yUsm;
}

void main() {
  vec2 src = vTexSamplingCoord * uInputSize - 0.5;
  vec2 base = floor(src);
  float fx = src.x - base.x;
  float fy = src.y - base.y;
  float phX = floor(fx * 64.0);
  float phY = floor(fy * 64.0);

  // 6x6 luma support, p[i * 6 + j]: row i = y (base.y - 2 + i), column j = x (base.x - 2 + j).
  float p[36];
  for (int i = 0; i < 6; i++) {
    for (int j = 0; j < 6; j++) {
      p[i * 6 + j] = lumaAt(base + vec2(float(j - 2), float(i - 2)));
    }
  }

  // Edge map of the 2x2 source pixels around the output position, interpolated to it.
  vec4 e00 = edgeMap(p[7], p[8], p[9], p[13], p[15], p[19], p[20], p[21]);
  vec4 e01 = edgeMap(p[8], p[9], p[10], p[14], p[16], p[20], p[21], p[22]);
  vec4 e10 = edgeMap(p[13], p[14], p[15], p[19], p[21], p[25], p[26], p[27]);
  vec4 e11 = edgeMap(p[14], p[15], p[16], p[20], p[22], p[26], p[27], p[28]);
  vec4 w = mix(mix(e00, e01, fx), mix(e10, e11, fx), fy);
  float baseWeight = 1.0 - w.x - w.y - w.z - w.w;

  // FilterNormal: separable 6-tap scaler, vertical phase first.
  vec4 cy0 = coef(0.0, phY);
  vec4 cy1 = coef(1.0, phY);
  vec4 cx0 = coef(0.0, phX);
  vec4 cx1 = coef(1.0, phX);
  float v[6];
  for (int j = 0; j < 6; j++) {
    v[j] = dot(cy0, vec4(p[j], p[6 + j], p[12 + j], p[18 + j])) + cy1.x * p[24 + j] + cy1.y * p[30 + j];
  }
  float opY = (dot(cx0, vec4(v[0], v[1], v[2], v[3])) + cx1.x * v[4] + cx1.y * v[5]) * baseWeight;

  // AddDirFilters
  if (w.x > 0.0) {
    // 0 deg
    opY += evalPoly6(
      vec4(mix(p[2], p[3], fx), mix(p[8], p[9], fx), mix(p[14], p[15], fx), mix(p[20], p[21], fx)),
      vec2(mix(p[26], p[27], fx), mix(p[32], p[33], fx)), phY) * w.x;
  }
  if (w.y > 0.0) {
    // 90 deg
    opY += evalPoly6(
      vec4(mix(p[12], p[18], fy), mix(p[13], p[19], fy), mix(p[14], p[20], fy), mix(p[15], p[21], fy)),
      vec2(mix(p[16], p[22], fy), mix(p[17], p[23], fy)), phX) * w.y;
  }
  if (w.z > 0.0) {
    // 45 deg
    float b45 = 0.5 + 0.5 * (fx - fy);
    float t1 = mix(p[13], p[8], b45);
    float t3 = mix(p[20], p[15], b45);
    float t5 = mix(p[27], p[22], b45);
    b45 -= 0.5;
    bool pos45 = b45 >= 0.0;
    float t0 = mix(p[7], pos45 ? p[2] : p[12], abs(b45));
    float t2 = mix(p[14], pos45 ? p[9] : p[19], abs(b45));
    float t4 = mix(p[21], pos45 ? p[16] : p[26], abs(b45));
    float t6 = mix(p[28], pos45 ? p[23] : p[33], abs(b45));
    float p45 = fx + fy;
    if (p45 >= 1.0) {
      opY += evalPoly6(vec4(t1, t2, t3, t4), vec2(t5, t6), floor((p45 - 1.0) * 64.0)) * w.z;
    } else {
      opY += evalPoly6(vec4(t0, t1, t2, t3), vec2(t4, t5), floor(p45 * 64.0)) * w.z;
    }
  }
  if (w.w > 0.0) {
    // 135 deg
    float b135 = 0.5 * (fx + fy);
    float t1 = mix(p[19], p[26], b135);
    float t3 = mix(p[14], p[21], b135);
    float t5 = mix(p[9], p[16], b135);
    b135 -= 0.5;
    bool pos135 = b135 >= 0.0;
    float t0 = mix(p[25], pos135 ? p[32] : p[18], abs(b135));
    float t2 = mix(p[20], pos135 ? p[27] : p[13], abs(b135));
    float t4 = mix(p[15], pos135 ? p[22] : p[8], abs(b135));
    float t6 = mix(p[10], pos135 ? p[17] : p[3], abs(b135));
    float p135 = 1.0 + (fx - fy);
    if (p135 >= 1.0) {
      opY += evalPoly6(vec4(t1, t2, t3, t4), vec2(t5, t6), floor((p135 - 1.0) * 64.0)) * w.w;
    } else {
      opY += evalPoly6(vec4(t0, t1, t2, t3), vec2(t4, t5), floor(p135 * 64.0)) * w.w;
    }
  }

  // Bilinear tap at the output position plus the luma correction (MPV: op.x += corr).
  vec3 rgb = texture2D(uTexSampler, vTexSamplingCoord).rgb;
  gl_FragColor = vec4(clamp(rgb + (opY - lumaOf(rgb)), 0.0, 1.0), 1.0);
}
"""

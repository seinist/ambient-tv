@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.roundToInt

/*
 * Single-pass fragment shaders for Media3's SDR effect chain (deband, cubic and FSR upscaling,
 * unsharp, CAS, RCAS, Adaptive Sharpen, film grain, denoise, gamma). Inputs are gamma-encoded SDR RGB in 0..1; the chain only runs for
 * SDR tracks (see media3ImageEffectsAllowed), HDR keeps the direct decoder surface.
 */

/** Size the source is fitted to inside the output, keeping its aspect; never smaller than the source. */
internal fun media3FitSize(inputWidth: Int, inputHeight: Int, outputWidth: Int, outputHeight: Int): Pair<Int, Int> {
    if (inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return inputWidth to inputHeight
    val scale = minOf(outputWidth.toDouble() / inputWidth, outputHeight.toDouble() / inputHeight)
    if (scale <= 1.0) return inputWidth to inputHeight
    return (inputWidth * scale).roundToInt().coerceAtMost(outputWidth) to
        (inputHeight * scale).roundToInt().coerceAtMost(outputHeight)
}

private const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

private const val FRAGMENT_HEADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uInputSize;
varying vec2 vTexSamplingCoord;
vec3 tap(vec2 texel) { return texture2D(uTexSampler, (texel + 0.5) / uInputSize).rgb; }
uniform float uOutGamma;
vec4 finish(vec3 c) { return uOutGamma == 1.0 ? vec4(c, 1.0) : vec4(pow(max(c, vec3(0.0)), vec3(uOutGamma)), 1.0); }
"""

private abstract class Media3FragmentShaderProgram(
    useHighPrecision: Boolean,
    fragmentBody: String,
    /** Gamma exponent fused into this pass (1 = none), saving the separate gamma texture. */
    private val outGamma: Float = 1f,
) : BaseGlShaderProgram(useHighPrecision, /* texturePoolCapacity= */ 1) {
    private val program: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_HEADER + fragmentBody).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var inputSize = floatArrayOf(1f, 1f)
    /** Frame being drawn, for per-frame noise. */
    protected var presentationTimeUs = 0L

    open fun outputSize(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)
    open fun setUniforms(program: GlProgram) {}

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inputSize = floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat())
        return outputSize(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            this.presentationTimeUs = presentationTimeUs
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatsUniformIfPresent("uInputSize", inputSize)
            program.setFloatsUniformIfPresent("uOutGamma", floatArrayOf(outGamma))
            setUniforms(program)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

private fun GlProgram.float(name: String, value: Float) = setFloatsUniformIfPresent(name, floatArrayOf(value))

/**
 * Bilateral denoise over a 5x5 neighbourhood without corners: neighbours count by distance and
 * by how close their color is, so noise in flat areas averages out while edges stay.
 */
internal class Media3DenoiseEffect(private val strength: String, private val gamma: Int = 0) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : Media3FragmentShaderProgram(useHdr, DENOISE, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) {
                val (spatial, range) = media3DenoiseSigmas(strength)
                program.float("uSpatial", 1f / (2f * spatial * spatial))
                program.float("uRange", 1f / (2f * range * range))
            }
        }
}

/** Spatial (pixels) and range (0..1 color distance) sigmas per level. */
internal fun media3DenoiseSigmas(strength: String): Pair<Float, Float> = when (strength) {
    "medium" -> 1.5f to 0.05f
    "strong" -> 2.0f to 0.08f
    else -> 1.2f to 0.03f
}

/** Smooths 8-bit color steps in flat areas; edges above the threshold stay untouched. */
internal class Media3DebandEffect(private val strength: String, private val gamma: Int = 0) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : Media3FragmentShaderProgram(useHdr, DEBAND, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) {
                val (range, threshold, grain) = when (strength) {
                    "medium" -> Triple(16f, 0.008f, 0.003f)
                    "strong" -> Triple(24f, 0.012f, 0.004f)
                    else -> Triple(8f, 0.004f, 0.002f)
                }
                program.float("uRange", range)
                program.float("uThreshold", threshold)
                program.float("uGrain", grain)
            }
        }
}

/** Cubic B/C spline upscale to the fitted output size (mpv: bicubic B=1 C=0, catmull_rom B=0 C=0.5). */
internal class Media3CubicScaleEffect(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val b: Float,
    private val c: Float,
    private val gamma: Int = 0,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        gamma == 0 && media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight) == (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : Media3FragmentShaderProgram(useHdr, CUBIC, media3GammaExponent(gamma)) {
            override fun outputSize(inputWidth: Int, inputHeight: Int) =
                media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight).let { Size(it.first, it.second) }
            override fun setUniforms(program: GlProgram) {
                program.float("uB", b)
                program.float("uC", c)
            }
        }
}

/** AMD FidelityFX FSR 1 EASU (edge-adaptive upscale) on RGB; [Media3SharpenEffect] "rcas" follows it. */
internal class Media3FsrEasuEffect(private val outputWidth: Int, private val outputHeight: Int, private val gamma: Int = 0) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        gamma == 0 && media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight) == (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : Media3FragmentShaderProgram(useHdr, FSR_EASU, media3GammaExponent(gamma)) {
            override fun outputSize(inputWidth: Int, inputHeight: Int) =
                media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight).let { Size(it.first, it.second) }
        }
}

/** light/medium: unsharp mask; cas: AMD CAS; rcas: FSR 1 RCAS. */
internal class Media3SharpenEffect(private val kind: String, private val gamma: Int = 0) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = when (kind) {
        "cas" -> object : Media3FragmentShaderProgram(useHdr, CAS, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) = program.float("uSharpness", 0.6f)
        }
        // Same curve height as MPV's bundled Adaptive Sharpen; runs post-resize like there.
        "adaptive" -> object : Media3FragmentShaderProgram(useHdr, ADAPTIVE_SHARPEN, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) = program.float("uCurveHeight", 1f)
        }
        "rcas" -> object : Media3FragmentShaderProgram(useHdr, RCAS, media3GammaExponent(gamma)) {
            // Same strength as the MPV FSR shader (0.2 stops below maximum).
            override fun setUniforms(program: GlProgram) = program.float("uSharpnessStops", 0.2f)
        }
        else -> object : Media3FragmentShaderProgram(useHdr, UNSHARP, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) = program.float("uAmount", when (kind) { "medium" -> 0.8f; "strong" -> 1.2f; else -> 0.4f })
        }
    }
}

/**
 * Ambient TV film grain as in MPV (same noise and intensities): equal grain on R, G and B, i.e.
 * luma grain, fresh per frame.
 */
internal class Media3FilmGrainEffect(private val strength: String, private val gamma: Int = 0) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : Media3FragmentShaderProgram(useHdr, FILM_GRAIN, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) {
                program.float("uIntensity", media3GrainIntensity(strength))
                program.float("uSeed", media3GrainSeed(presentationTimeUs))
            }
        }
}

/** INTENSITY of MPV's quality_filmgrain_* shaders. */
internal fun media3GrainIntensity(strength: String): Float = when (strength) {
    "medium" -> 0.06f
    "strong" -> 0.10f
    else -> 0.03f
}

/** Per-frame seed in 0..1000 like MPV's `fract(random) * 1000`, stable for a repeated frame. */
internal fun media3GrainSeed(presentationTimeUs: Long): Float {
    var x = presentationTimeUs * -0x61c8864680b583ebL
    x = x xor (x ushr 29)
    return ((x ushr 11) % 1_000_000L).toFloat() / 1000f
}

/** Gamma like VLC's adjust filter: positive values brighten midtones. */
internal class Media3GammaEffect(private val gamma: Int) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = gamma == 0

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : Media3FragmentShaderProgram(useHdr, GAMMA) {
            override fun setUniforms(program: GlProgram) = program.float("uInvGamma", media3GammaExponent(gamma))
        }
}

/** Exponent applied to each channel: 1 / (1 + gamma / 100). */
internal fun media3GammaExponent(gamma: Int): Float = 1f / (1f + gamma.coerceIn(-20, 20) / 100f)

private const val DEBAND = """
uniform float uRange;
uniform float uThreshold;
uniform float uGrain;
#ifdef GL_FRAGMENT_PRECISION_HIGH
float rand(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
#else
// mediump (fp16): the sin hash collapses to a fixed pattern; interleaved gradient noise on a
// 64-pixel tile keeps every product small enough to stay random.
float rand(vec2 p) { p = mod(p, 64.0); return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
#endif
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 px = 1.0 / uInputSize;
  vec2 pos = floor(uv * uInputSize);
  vec4 center = texture2D(uTexSampler, uv);
  float angle = rand(pos) * 6.2831853;
  float dist = uRange * (0.5 + 0.5 * rand(pos + 17.0));
  vec2 o = vec2(cos(angle), sin(angle)) * dist * px;
  vec2 q = vec2(-o.y, o.x);
  vec3 avg = (texture2D(uTexSampler, uv + o).rgb + texture2D(uTexSampler, uv - o).rgb +
      texture2D(uTexSampler, uv + q).rgb + texture2D(uTexSampler, uv - q).rgb) * 0.25;
  vec3 flatMask = step(abs(center.rgb - avg), vec3(uThreshold));
  vec3 color = mix(center.rgb, avg, flatMask);
  color += (rand(pos + 31.0) - 0.5) * uGrain;
  gl_FragColor = finish(clamp(color, 0.0, 1.0));
}
"""

private const val CUBIC = """
uniform float uB;
uniform float uC;
float cubicWeight(float x) {
  x = abs(x);
  if (x < 1.0) return ((12.0 - 9.0 * uB - 6.0 * uC) * x * x * x + (-18.0 + 12.0 * uB + 6.0 * uC) * x * x + (6.0 - 2.0 * uB)) / 6.0;
  if (x < 2.0) return ((-uB - 6.0 * uC) * x * x * x + (6.0 * uB + 30.0 * uC) * x * x + (-12.0 * uB - 48.0 * uC) * x + (8.0 * uB + 24.0 * uC)) / 6.0;
  return 0.0;
}
void main() {
  vec2 pp = vTexSamplingCoord * uInputSize - 0.5;
  vec2 base = floor(pp);
  vec2 f = pp - base;
  vec3 sum = vec3(0.0);
  float weights = 0.0;
  for (int j = -1; j <= 2; j++) {
    float wy = cubicWeight(float(j) - f.y);
    for (int i = -1; i <= 2; i++) {
      float w = cubicWeight(float(i) - f.x) * wy;
      sum += tap(base + vec2(float(i), float(j))) * w;
      weights += w;
    }
  }
  gl_FragColor = finish(clamp(sum / weights, 0.0, 1.0));
}
"""

// FidelityFX FSR 1.0.2 EASU, Copyright (c) 2021 Advanced Micro Devices, Inc. (MIT license, see
// res/raw/quality_fsr_*.glsl). RGB port: luma (0.5 R + G + 0.5 B) drives the analysis like AMD's
// original, the taps filter RGB. Fast reciprocal approximations are replaced by plain divisions.
private const val FSR_EASU = """
float luma(vec3 c) { return c.g + 0.5 * (c.r + c.b); }
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
  float dc = lD - lC;
  float cb = lC - lB;
  float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1e-5);
  float dirX = lD - lB;
  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
  lenX *= lenX;
  float ec = lE - lC;
  float ca = lC - lA;
  float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1e-5);
  float dirY = lE - lA;
  lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
  lenY *= lenY;
  dir += vec2(dirX, dirY) * w;
  len += dot(vec2(w), vec2(lenX, lenY));
}
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x) * len;
  float d2 = min(v.x * v.x + v.y * v.y, clp);
  float wB = 0.4 * d2 - 1.0;
  float wA = lob * d2 - 1.0;
  wB *= wB;
  wA *= wA;
  wB = 1.5625 * wB - 0.5625;
  float w = wB * wA;
  aC += c * w;
  aW += w;
}
void main() {
  vec2 pp = vTexSamplingCoord * uInputSize - 0.5;
  vec2 fp = floor(pp);
  pp -= fp;
  vec3 b = tap(fp + vec2(0.0, -1.0));
  vec3 c = tap(fp + vec2(1.0, -1.0));
  vec3 e = tap(fp + vec2(-1.0, 0.0));
  vec3 f = tap(fp);
  vec3 g = tap(fp + vec2(1.0, 0.0));
  vec3 h = tap(fp + vec2(2.0, 0.0));
  vec3 i = tap(fp + vec2(-1.0, 1.0));
  vec3 j = tap(fp + vec2(0.0, 1.0));
  vec3 k = tap(fp + vec2(1.0, 1.0));
  vec3 l = tap(fp + vec2(2.0, 1.0));
  vec3 n = tap(fp + vec2(0.0, 2.0));
  vec3 o = tap(fp + vec2(1.0, 2.0));
  float bL = luma(b); float cL = luma(c); float eL = luma(e); float fL = luma(f);
  float gL = luma(g); float hL = luma(h); float iL = luma(i); float jL = luma(j);
  float kL = luma(k); float lL = luma(l); float nL = luma(n); float oL = luma(o);
  vec2 dir = vec2(0.0);
  float len = 0.0;
  easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
  easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
  easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
  easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
  vec2 dir2 = dir * dir;
  float dirR = dir2.x + dir2.y;
  bool zro = dirR < (1.0 / 32768.0);
  dirR = zro ? 1.0 : inversesqrt(dirR);
  dir.x = zro ? 1.0 : dir.x;
  dir *= vec2(dirR);
  len = len * 0.5;
  len *= len;
  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(max(abs(dir.x), abs(dir.y)), 1e-5);
  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
  float clp = 1.0 / lob;
  vec3 aC = vec3(0.0);
  float aW = 0.0;
  easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
  easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
  easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
  easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
  easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
  easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
  easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
  easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
  easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
  easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
  easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
  easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
  vec3 color = aC / aW;
  vec3 mn = min(min(f, g), min(j, k));
  vec3 mx = max(max(f, g), max(j, k));
  gl_FragColor = finish(clamp(clamp(color, mn, mx), 0.0, 1.0));
}
"""

// FidelityFX FSR 1.0.2 RCAS (MIT, AMD), RGB with luma noise detection as in AMD's original.
private const val RCAS = """
uniform float uSharpnessStops;
float luma(vec3 c) { return c.g + 0.5 * (c.r + c.b); }
void main() {
  vec2 pos = floor(vTexSamplingCoord * uInputSize);
  vec3 b = tap(pos + vec2(0.0, -1.0));
  vec3 d = tap(pos + vec2(-1.0, 0.0));
  vec3 e = tap(pos);
  vec3 f = tap(pos + vec2(1.0, 0.0));
  vec3 h = tap(pos + vec2(0.0, 1.0));
  vec3 mn4 = min(min(b, d), min(f, h));
  vec3 mx4 = max(max(b, d), max(f, h));
  vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-5));
  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-5));
  vec3 lobeRgb = max(-hitMin, hitMax);
  float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobeRgb.r, max(lobeRgb.g, lobeRgb.b)), 0.0)) * exp2(-clamp(uSharpnessStops, 0.0, 2.0));
  float bL = luma(b); float dL = luma(d); float eL = luma(e); float fL = luma(f); float hL = luma(h);
  float range = max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL);
  float nz = clamp(abs(0.25 * (bL + dL + fL + hL) - eL) / max(range, 1e-5), 0.0, 1.0);
  lobe *= 1.0 - 0.5 * nz;
  vec3 color = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
  gl_FragColor = finish(clamp(color, 0.0, 1.0));
}
"""

// AMD FidelityFX CAS (MIT), non-scaling variant on RGB.
private const val CAS = """
uniform float uSharpness;
void main() {
  vec2 pos = floor(vTexSamplingCoord * uInputSize);
  vec3 a = tap(pos + vec2(-1.0, -1.0));
  vec3 b = tap(pos + vec2(0.0, -1.0));
  vec3 c = tap(pos + vec2(1.0, -1.0));
  vec3 d = tap(pos + vec2(-1.0, 0.0));
  vec3 e = tap(pos);
  vec3 f = tap(pos + vec2(1.0, 0.0));
  vec3 g = tap(pos + vec2(-1.0, 1.0));
  vec3 h = tap(pos + vec2(0.0, 1.0));
  vec3 i = tap(pos + vec2(1.0, 1.0));
  vec3 mnRgb = min(min(min(d, e), min(f, b)), h);
  vec3 mnRgb2 = min(mnRgb, min(min(a, c), min(g, i)));
  mnRgb += mnRgb2;
  vec3 mxRgb = max(max(max(d, e), max(f, b)), h);
  vec3 mxRgb2 = max(mxRgb, max(max(a, c), max(g, i)));
  mxRgb += mxRgb2;
  vec3 amp = sqrt(clamp(min(mnRgb, 2.0 - mxRgb) / max(mxRgb, vec3(1e-5)), 0.0, 1.0));
  float peak = -1.0 / mix(8.0, 5.0, clamp(uSharpness, 0.0, 1.0));
  vec3 w = amp * peak;
  vec3 color = (b * w + d * w + f * w + h * w + e) / (1.0 + 4.0 * w);
  gl_FragColor = finish(clamp(color, 0.0, 1.0));
}
"""

private const val UNSHARP = """
uniform float uAmount;
void main() {
  vec2 pos = floor(vTexSamplingCoord * uInputSize);
  vec3 e = tap(pos);
  vec3 crossSum = tap(pos + vec2(0.0, -1.0)) + tap(pos + vec2(-1.0, 0.0)) + tap(pos + vec2(1.0, 0.0)) + tap(pos + vec2(0.0, 1.0));
  vec3 corners = tap(pos + vec2(-1.0, -1.0)) + tap(pos + vec2(1.0, -1.0)) + tap(pos + vec2(-1.0, 1.0)) + tap(pos + vec2(1.0, 1.0));
  vec3 blur = (4.0 * e + 2.0 * crossSum + corners) / 16.0;
  gl_FragColor = finish(clamp(e + uAmount * (e - blur), 0.0, 1.0));
}
"""

private const val GAMMA = """
uniform float uInvGamma;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = vec4(pow(max(c.rgb, vec3(0.0)), vec3(uInvGamma)), 1.0);
}
"""

// Ambient TV film grain (CC0 1.0), the MPV shader on RGB: per-frame triangular noise smoothed
// over 3x3, the same value added to all channels.
private const val FILM_GRAIN = """
uniform float uIntensity;
uniform float uSeed;
#ifdef GL_FRAGMENT_PRECISION_HIGH
float grainHash(vec2 p) {
  vec3 q = fract(vec3(p.xyx) * 0.1031);
  q += dot(q, q.yzx + 33.33);
  return fract((q.x + q.y) * q.z);
}
float grainNoise(vec2 p) {
  return grainHash(p + uSeed * 113.0) + grainHash(p * 1.37 + uSeed * 71.0) - 1.0;
}
#else
// mediump (fp16): large hash inputs lose every fractional bit and the grain freezes into a
// pattern. Interleaved gradient noise on a 64-pixel tile, shifted per frame, stays small.
float grainHash(vec2 p) { p = mod(p, 64.0); return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
float grainNoise(vec2 p) {
  vec2 shift = floor(mod(vec2(uSeed, uSeed * 0.37), 64.0));
  return grainHash(p + shift) + grainHash(p.yx + shift.yx + 17.0) - 1.0;
}
#endif
void main() {
  vec2 p = floor(vTexSamplingCoord * uInputSize);
  float g = 4.0 * grainNoise(p);
  g += 2.0 * (grainNoise(p + vec2(1.0, 0.0)) + grainNoise(p - vec2(1.0, 0.0))
            + grainNoise(p + vec2(0.0, 1.0)) + grainNoise(p - vec2(0.0, 1.0)));
  g += grainNoise(p + vec2(1.0, 1.0)) + grainNoise(p - vec2(1.0, 1.0))
     + grainNoise(p + vec2(1.0, -1.0)) + grainNoise(p - vec2(1.0, -1.0));
  g /= 16.0;
  vec3 color = texture2D(uTexSampler, vTexSamplingCoord).rgb + vec3(uIntensity * 2.0 * g);
  gl_FragColor = finish(clamp(color, 0.0, 1.0));
}
"""

// Adaptive sharpen 2021-10-17, Copyright (c) 2015-2021, bacondither. All rights reserved.
// BSD 2-clause; full notice in res/raw/image_shader_licenses.txt. Ported from the bundled MPV
// variant (overshoot_ctrl off, pm_p 1) to GLSL ES 1.00: arrays filled element by element, and the
// fwidth() edge values as forward differences of the already sampled neighbours, so no
// GL_OES_standard_derivatives is needed.
private const val ADAPTIVE_SHARPEN = """
uniform float uCurveHeight;
float sat1(float x) { return clamp(x, 0.0, 1.0); }
float ctl(vec3 c) { vec3 s = clamp(c, 0.0, 1.0); return sqrt(dot(s * s, vec3(0.2126, 0.7152, 0.0722))); }
float dxdy(vec3 c, vec3 right, vec3 down) { return length(abs(right - c) + abs(down - c)); }
float softLim(float v, float s) { float t = v / s; return sat1(abs(t) * (27.0 + t * t) / (27.0 + 9.0 * t * t)) * s; }
float wpmean(float a, float b, float w) { return w * abs(a) + abs(1.0 - w) * abs(b); }
void main() {
  vec2 pos = floor(vTexSamplingCoord * uInputSize);
  // [                c22               ]
  // [           c24, c9,  c23          ]
  // [      c21, c1,  c2,  c3, c18      ]
  // [ c19, c10, c4,  c0,  c5, c11, c16 ]
  // [      c20, c6,  c7,  c8, c17      ]
  // [           c15, c12, c14          ]
  // [                c13               ]
  vec3 c[25];
  c[0] = tap(pos);
  c[1] = tap(pos + vec2(-1.0, -1.0));
  c[2] = tap(pos + vec2(0.0, -1.0));
  c[3] = tap(pos + vec2(1.0, -1.0));
  c[4] = tap(pos + vec2(-1.0, 0.0));
  c[5] = tap(pos + vec2(1.0, 0.0));
  c[6] = tap(pos + vec2(-1.0, 1.0));
  c[7] = tap(pos + vec2(0.0, 1.0));
  c[8] = tap(pos + vec2(1.0, 1.0));
  c[9] = tap(pos + vec2(0.0, -2.0));
  c[10] = tap(pos + vec2(-2.0, 0.0));
  c[11] = tap(pos + vec2(2.0, 0.0));
  c[12] = tap(pos + vec2(0.0, 2.0));
  c[13] = tap(pos + vec2(0.0, 3.0));
  c[14] = tap(pos + vec2(1.0, 2.0));
  c[15] = tap(pos + vec2(-1.0, 2.0));
  c[16] = tap(pos + vec2(3.0, 0.0));
  c[17] = tap(pos + vec2(2.0, 1.0));
  c[18] = tap(pos + vec2(2.0, -1.0));
  c[19] = tap(pos + vec2(-3.0, 0.0));
  c[20] = tap(pos + vec2(-2.0, 1.0));
  c[21] = tap(pos + vec2(-2.0, -1.0));
  c[22] = tap(pos + vec2(0.0, -3.0));
  c[23] = tap(pos + vec2(1.0, -2.0));
  c[24] = tap(pos + vec2(-1.0, -2.0));

  // Edge value per pixel: its right and lower neighbour (fwidth's one-pixel differences).
  float e[13];
  e[0] = dxdy(c[0], c[5], c[7]);
  e[1] = dxdy(c[1], c[2], c[4]);
  e[2] = dxdy(c[2], c[3], c[0]);
  e[3] = dxdy(c[3], c[18], c[5]);
  e[4] = dxdy(c[4], c[0], c[6]);
  e[5] = dxdy(c[5], c[11], c[8]);
  e[6] = dxdy(c[6], c[7], c[15]);
  e[7] = dxdy(c[7], c[8], c[12]);
  e[8] = dxdy(c[8], c[17], c[14]);
  e[9] = dxdy(c[9], c[23], c[2]);
  e[10] = dxdy(c[10], c[4], c[20]);
  e[11] = dxdy(c[11], c[16], c[17]);
  e[12] = dxdy(c[12], c[14], c[13]);

  float luma[25];
  for (int i = 0; i < 25; i++) luma[i] = ctl(c[i]);
  float c0Y = luma[0];

  // Blur, gauss 3x3
  float blur = (2.0 * (luma[2] + luma[4] + luma[5] + luma[7]) + (luma[1] + luma[3] + luma[6] + luma[8]) + 4.0 * luma[0]) / 16.0;
  // Contrast compression, center = 0.5
  float cComp = sat1(0.266666681 + 0.9 * exp2(blur * blur * -7.4));
  float edge = (1.38 * (blur - luma[0]) * (blur - luma[0])
      + 1.15 * ((blur - luma[2]) * (blur - luma[2]) + (blur - luma[4]) * (blur - luma[4]) + (blur - luma[5]) * (blur - luma[5]) + (blur - luma[7]) * (blur - luma[7]))
      + 0.92 * ((blur - luma[1]) * (blur - luma[1]) + (blur - luma[3]) * (blur - luma[3]) + (blur - luma[6]) * (blur - luma[6]) + (blur - luma[8]) * (blur - luma[8]))
      + 0.23 * ((blur - luma[9]) * (blur - luma[9]) + (blur - luma[10]) * (blur - luma[10]) + (blur - luma[11]) * (blur - luma[11]) + (blur - luma[12]) * (blur - luma[12]))) * cComp;
  vec2 cs = vec2(0.167, 0.250);

  // Squared kernel weights; concave kernel above the edge threshold.
  vec3 w1 = vec3(0.5, 1.0, 1.41421356237);
  vec3 w2 = vec3(0.86602540378, 1.0, 0.54772255751);
  vec3 dW = pow(mix(w1, w2, sat1(2.4 * edge - 0.82)), vec3(2.0));
  // Lower weights for pixels in a more active area than the center: narrower overshoots.
  float modifE0 = 3.0 * e[0] + 0.02 / 2.5;
  float weights[12];
  weights[0] = min(modifE0 / max(e[1], 1e-6), dW.y);
  weights[1] = dW.x;
  weights[2] = min(modifE0 / max(e[3], 1e-6), dW.y);
  weights[3] = dW.x;
  weights[4] = dW.x;
  weights[5] = min(modifE0 / max(e[6], 1e-6), dW.y);
  weights[6] = dW.x;
  weights[7] = min(modifE0 / max(e[8], 1e-6), dW.y);
  weights[8] = min(modifE0 / max(e[9], 1e-6), dW.z);
  weights[9] = min(modifE0 / max(e[10], 1e-6), dW.z);
  weights[10] = min(modifE0 / max(e[11], 1e-6), dW.z);
  weights[11] = min(modifE0 / max(e[12], 1e-6), dW.z);
  weights[0] = (max(max((weights[8] + weights[9]) / 4.0, weights[0]), 0.25) + weights[0]) / 2.0;
  weights[2] = (max(max((weights[8] + weights[10]) / 4.0, weights[2]), 0.25) + weights[2]) / 2.0;
  weights[5] = (max(max((weights[9] + weights[11]) / 4.0, weights[5]), 0.25) + weights[5]) / 2.0;
  weights[7] = (max(max((weights[10] + weights[11]) / 4.0, weights[7]), 0.25) + weights[7]) / 2.0;

  // Negative part of the laplace kernel and the low threshold weight.
  float lowthrsum = 0.0;
  float weightsum = 0.0;
  float negLaplace = 0.0;
  for (int pix = 0; pix < 12; pix++) {
    float lowthr = sat1(20.0 * 4.5 * cComp * e[pix + 1] - 0.221);
    negLaplace += luma[pix + 1] * luma[pix + 1] * weights[pix] * lowthr;
    weightsum += weights[pix] * lowthr;
    lowthrsum += lowthr / 12.0;
  }
  // Flat areas: no qualifying edge, the pixel passes unchanged (no 0/0).
  if (weightsum <= 1e-6 || lowthrsum <= 1e-6) { gl_FragColor = finish(c[0]); return; }
  negLaplace = sqrt(max(negLaplace / weightsum, 0.0));

  float sharpenVal = uCurveHeight / (uCurveHeight * 0.5 * edge + 0.625);
  float sharpdiff = (c0Y - negLaplace) * (lowthrsum * sharpenVal + 0.01);

  // Local near min & max, partial sort.
  float temp;
  for (int i1 = 0; i1 < 24; i1 += 2) {
    temp = luma[i1];
    luma[i1] = min(luma[i1], luma[i1 + 1]);
    luma[i1 + 1] = max(temp, luma[i1 + 1]);
  }
  for (int i2 = 24; i2 > 0; i2 -= 2) {
    temp = luma[0];
    luma[0] = min(luma[0], luma[i2]);
    luma[i2] = max(temp, luma[i2]);
    temp = luma[24];
    luma[24] = max(luma[24], luma[i2 - 1]);
    luma[i2 - 1] = min(temp, luma[i2 - 1]);
  }
  float minDist = min(abs(luma[24] - c0Y), abs(c0Y - luma[0]));
  minDist = min(minDist, 0.1 * (1.0 - 0.056) + minDist * 0.056);
  if (minDist <= 1e-6) { gl_FragColor = finish(c[0]); return; }

  // Soft limited anti-ringing with tanh, wpmean to control compression slope.
  sharpdiff = wpmean(max(sharpdiff, 0.0), softLim(max(sharpdiff, 0.0), minDist), cs.x)
            - wpmean(min(sharpdiff, 0.0), softLim(min(sharpdiff, 0.0), minDist), cs.y);
  float sharpdiffLim = sat1(c0Y + sharpdiff) - c0Y;
  gl_FragColor = finish(clamp(c[0] + sharpdiffLim, 0.0, 1.0));
}
"""

// Bilateral filter, 21 taps (5x5 without the corners). uSpatial and uRange are 1 / (2 sigma^2).
private const val DENOISE = """
uniform float uSpatial;
uniform float uRange;
void main() {
  vec2 pos = floor(vTexSamplingCoord * uInputSize);
  vec3 center = tap(pos);
  vec3 sum = center;
  float weights = 1.0;
  for (int y = -2; y <= 2; y++) {
    for (int x = -2; x <= 2; x++) {
      float d2 = float(x * x + y * y);
      if (d2 == 0.0 || d2 > 5.0) continue;
      vec3 c = tap(pos + vec2(float(x), float(y)));
      vec3 diff = c - center;
      float w = exp(-d2 * uSpatial - dot(diff, diff) * (uRange / 3.0));
      sum += c * w;
      weights += w;
    }
  }
  gl_FragColor = finish(clamp(sum / weights, 0.0, 1.0));
}
"""

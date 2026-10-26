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
 * unsharp, CAS, RCAS, gamma). Inputs are gamma-encoded SDR RGB in 0..1; the chain only runs for
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

    open fun outputSize(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)
    open fun setUniforms(program: GlProgram) {}

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inputSize = floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat())
        return outputSize(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
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

/** Smooths 8-bit color steps in flat areas; edges above the threshold stay untouched. */
internal class Media3DebandEffect(private val strength: String, private val gamma: Int = 0) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : Media3FragmentShaderProgram(useHdr, DEBAND, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) {
                val medium = strength == "medium"
                program.float("uRange", if (medium) 16f else 8f)
                program.float("uThreshold", if (medium) 0.008f else 0.004f)
                program.float("uGrain", if (medium) 0.003f else 0.002f)
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
        "rcas" -> object : Media3FragmentShaderProgram(useHdr, RCAS, media3GammaExponent(gamma)) {
            // Same strength as the MPV FSR shader (0.2 stops below maximum).
            override fun setUniforms(program: GlProgram) = program.float("uSharpnessStops", 0.2f)
        }
        else -> object : Media3FragmentShaderProgram(useHdr, UNSHARP, media3GammaExponent(gamma)) {
            override fun setUniforms(program: GlProgram) = program.float("uAmount", if (kind == "medium") 0.8f else 0.4f)
        }
    }
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
float rand(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
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

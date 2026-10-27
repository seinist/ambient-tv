@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * MPV's built-in scalers (video/out/filter_kernels.c) for Media3. Like MPV, each kernel is
 * evaluated exactly on the CPU and sampled into a lookup table the shader interpolates; orthogonal
 * kernels run as two separable passes (horizontal, then vertical), polar (EWA) kernels as one
 * radial pass over every texel inside the radius. Weights are normalized per output pixel.
 * Upscaling only (Media3 plans scalers only then), so the kernels are never stretched.
 *
 * Like MPV's defaults (sigmoid-upscaling=yes, center 0.75, slope 6.5) the samples are linearized
 * (BT.1886, gamma 2.4) and sigmoidized before scaling and converted back after it, which keeps
 * the ringing of sharp kernels from turning into bright halos. Between the separable passes the
 * picture stays in sigmoid space, on a half-float target where the GPU offers one.
 */

/**
 * One MPV filter: [base] at x / [blur], times [window] stretched over the whole [support]
 * ([windowRadius] is the window's own radius). As in MPV's mp_init_filter, blur also scales the
 * support: radius = blur * f.radius. Zero beyond [support].
 */
internal class Media3Kernel(
    val radius: Double,
    val polar: Boolean,
    val base: (Double) -> Double,
    val window: ((Double) -> Double)? = null,
    val windowRadius: Double = 1.0,
    val blur: Double = 1.0,
) {
    /** The kernel's extent in source pixels (MPV: filter->radius = blur * f.radius). */
    val support: Double get() = radius * blur

    /** MPV's sample_filter for |x| in source pixels. */
    fun value(x: Double): Double {
        val ax = abs(x)
        if (ax >= support) return 0.0
        val w = window?.let { it(ax / support * windowRadius) } ?: 1.0
        return w * base(ax / blur)
    }

    /** Taps per side the shader loops over. */
    val taps: Int get() = ceil(support).toInt().coerceAtLeast(1)
}

internal const val JINC_R3 = 3.2383154841662362
internal const val JINC_R4 = 4.2410628637960699
/** First zero of jinc: the radius of the jinc window. */
internal const val JINC_WINDOW_RADIUS = 1.2196698912665045

internal fun media3Sinc(x: Double): Double = if (abs(x) < 1e-8) 1.0 else sin(PI * x) / (PI * x)

internal fun media3Jinc(x: Double): Double = if (abs(x) < 1e-8) 1.0 else 2.0 * besselJ1(PI * x) / (PI * x)

/** Bessel J1 (Numerical Recipes' bessj1, ~1e-8), as MPV's jinc uses libm's j1. */
internal fun besselJ1(x: Double): Double {
    val ax = abs(x)
    return if (ax < 8.0) {
        val y = x * x
        val a = x * (72362614232.0 + y * (-7895059235.0 + y * (242396853.1 + y * (-2972611.439 + y * (15704.48260 + y * -30.16036606)))))
        val b = 144725228442.0 + y * (2300535178.0 + y * (18583304.74 + y * (99447.43394 + y * (376.9991397 + y))))
        a / b
    } else {
        val z = 8.0 / ax
        val y = z * z
        val xx = ax - 2.356194491
        val a = 1.0 + y * (0.183105e-2 + y * (-0.3516396496e-4 + y * (0.2457520174e-5 + y * -0.240337019e-6)))
        val b = 0.04687499995 + y * (-0.2002690873e-3 + y * (0.8449199096e-5 + y * (-0.88228987e-6 + y * 0.105787412e-6)))
        val r = sqrt(0.636619772 / ax) * (cos(xx) * a - z * sin(xx) * b)
        if (x < 0.0) -r else r
    }
}

/** Mitchell-Netravali cubic with MPV's B and C. */
internal fun media3CubicBc(b: Double, c: Double): (Double) -> Double = { x ->
    val ax = abs(x)
    when {
        ax < 1.0 -> ((12 - 9 * b - 6 * c) * ax * ax * ax + (-18 + 12 * b + 6 * c) * ax * ax + (6 - 2 * b)) / 6
        ax < 2.0 -> ((-b - 6 * c) * ax * ax * ax + (6 * b + 30 * c) * ax * ax + (-12 * b - 48 * c) * ax + (8 * b + 24 * c)) / 6
        else -> 0.0
    }
}

private fun spline16(x: Double): Double = if (x < 1.0) ((x - 9.0 / 5.0) * x - 1.0 / 5.0) * x + 1.0
    else { val t = x - 1.0; ((-1.0 / 3.0 * t + 4.0 / 5.0) * t - 7.0 / 15.0) * t }

private fun spline36(x: Double): Double = when {
    x < 1.0 -> ((13.0 / 11.0 * x - 453.0 / 209.0) * x - 3.0 / 209.0) * x + 1.0
    x < 2.0 -> { val t = x - 1.0; ((-6.0 / 11.0 * t + 270.0 / 209.0) * t - 156.0 / 209.0) * t }
    else -> { val t = x - 2.0; ((1.0 / 11.0 * t - 45.0 / 209.0) * t + 26.0 / 209.0) * t }
}

private fun spline64(x: Double): Double = when {
    x < 1.0 -> ((49.0 / 41.0 * x - 6387.0 / 2911.0) * x - 3.0 / 2911.0) * x + 1.0
    x < 2.0 -> { val t = x - 1.0; ((-24.0 / 41.0 * t + 4032.0 / 2911.0) * t - 2328.0 / 2911.0) * t }
    x < 3.0 -> { val t = x - 2.0; ((6.0 / 41.0 * t - 1008.0 / 2911.0) * t + 582.0 / 2911.0) * t }
    else -> { val t = x - 3.0; ((-1.0 / 41.0 * t + 168.0 / 2911.0) * t - 97.0 / 2911.0) * t }
}

private val SQRT2 = sqrt(2.0)
private val ROBIDOUX = media3CubicBc(12 / (19 + 9 * SQRT2), 113 / (58 + 216 * SQRT2))
private val ROBIDOUX_SHARP = media3CubicBc(6 / (13 + 7 * SQRT2), 7 / (2 + 12 * SQRT2))

/** MPV's built-in kernels Media3 does not already have (bilinear, bicubic, Catmull-Rom, Lanczos). */
internal val MEDIA3_KERNELS: Map<String, Media3Kernel> = linkedMapOf(
    "mitchell" to Media3Kernel(2.0, false, media3CubicBc(1.0 / 3.0, 1.0 / 3.0)),
    "hermite" to Media3Kernel(1.0, false, media3CubicBc(0.0, 0.0)),
    "robidoux" to Media3Kernel(2.0, false, ROBIDOUX),
    "robidouxsharp" to Media3Kernel(2.0, false, ROBIDOUX_SHARP),
    "spline16" to Media3Kernel(2.0, false, ::spline16),
    "spline36" to Media3Kernel(3.0, false, ::spline36),
    "spline64" to Media3Kernel(4.0, false, ::spline64),
    "sinc" to Media3Kernel(2.0, false, ::media3Sinc),
    // MPV: sinc windowed by jinc.
    "ginseng" to Media3Kernel(3.0, false, ::media3Sinc, ::media3Jinc, JINC_WINDOW_RADIUS),
    "gaussian" to Media3Kernel(2.0, false, { x -> exp(-2.0 * x * x) }),
    "ewa_lanczos" to Media3Kernel(JINC_R3, true, ::media3Jinc, ::media3Jinc, JINC_WINDOW_RADIUS),
    "ewa_lanczossharp" to Media3Kernel(JINC_R3, true, ::media3Jinc, ::media3Jinc, JINC_WINDOW_RADIUS, blur = 0.9812505837223707),
    "ewa_lanczos4sharpest" to Media3Kernel(JINC_R4, true, ::media3Jinc, ::media3Jinc, JINC_WINDOW_RADIUS, blur = 0.8845120932605005),
    "ewa_lanczossoft" to Media3Kernel(JINC_R3, true, ::media3Jinc, ::media3Jinc, JINC_WINDOW_RADIUS, blur = 1.0164667662867047),
    "ewa_robidoux" to Media3Kernel(2.0, true, ROBIDOUX),
    "ewa_robidouxsharp" to Media3Kernel(2.0, true, ROBIDOUX_SHARP),
    "nearest" to Media3Kernel(0.5, false, { 1.0 }),
)

/** Media3 keys handled here: the kernels plus MPV's "oversample". */
internal val MEDIA3_KERNEL_SCALERS: Set<String> = MEDIA3_KERNELS.keys + "oversample"

internal const val MEDIA3_KERNEL_LUT_SIZE = 1024

/** The kernel over |x| in 0..support, LUT_SIZE samples (texel i at x = i / (size - 1) * support). */
internal fun media3KernelLut(kernel: Media3Kernel, size: Int = MEDIA3_KERNEL_LUT_SIZE): FloatArray =
    FloatArray(size) { i ->
        // The last sample sits on the support edge, where MPV's window is already zero; keep the limit.
        val x = minOf(i.toDouble() / (size - 1) * kernel.support, kernel.support * (1 - 1e-9))
        kernel.value(x).toFloat()
    }

/** MPV's scaler [key], fitted to the output size. */
internal class Media3KernelEffect(
    private val key: String,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight) == (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        media3RequireFragmentHighp("MPV scaler $key")
        if (key == "oversample") return Media3OversampleShaderProgram(useHdr, outputWidth, outputHeight)
        val kernel = MEDIA3_KERNELS[key] ?: throw VideoFrameProcessingException("Unknown scaler $key")
        return Media3KernelShaderProgram(useHdr, kernel, outputWidth, outputHeight)
    }
}

private class Media3KernelShaderProgram(
    useHighPrecision: Boolean,
    private val kernel: Media3Kernel,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : BaseGlShaderProgram(useHighPrecision, /* texturePoolCapacity= */ 1) {
    private val useHighPrecision = useHighPrecision
    private val program = media3GlProgram(media3KernelShader(kernel))
    private val planes = media3RavuLutPlanes(media3KernelLut(kernel))
    /** r = high byte, g = low byte; LINEAR stays exact because the decode is linear in both. */
    private val lut = media3LutTexture(
        ByteArray(MEDIA3_KERNEL_LUT_SIZE * 4) { i -> when (i % 4) { 0 -> planes.high[i / 4]; 1 -> planes.low[i / 4]; else -> 0 } },
        MEDIA3_KERNEL_LUT_SIZE, 1, android.opengl.GLES20.GL_LINEAR,
    )
    private var inputWidth = 1
    private var inputHeight = 1
    private var fitWidth = 1
    private var fitHeight = 1
    private var mid: Media3Intermediate? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseIntermediate()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val (width, height) = media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight)
        fitWidth = width
        fitHeight = height
        // Horizontal pass result: output width, source height.
        if (!kernel.polar) mid = media3Intermediate(fitWidth, inputHeight, useHighPrecision)
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val mid = mid
            if (kernel.polar || mid == null) {
                pass(inputTexId, inputWidth, inputHeight, floatArrayOf(1f, 1f), pre = true, post = true)
                return
            }
            val outputFbo = IntArray(1).also { android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
            GlUtil.focusFramebufferUsingCurrentContext(mid.fbo, fitWidth, inputHeight)
            pass(inputTexId, inputWidth, inputHeight, floatArrayOf(1f, 0f), pre = true, post = false)
            GlUtil.focusFramebufferUsingCurrentContext(outputFbo, fitWidth, fitHeight)
            pass(mid.texture, fitWidth, inputHeight, floatArrayOf(0f, 1f), pre = false, post = true)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /**
     * One pass reading [texture] of [width]x[height]; [direction] (1,0)/(0,1) separable, (1,1)
     * polar. [pre] converts samples into sigmoid space, [post] converts the result back.
     */
    private fun pass(texture: Int, width: Int, height: Int, direction: FloatArray, pre: Boolean, post: Boolean) {
        // Taps sit on texel centers, so the image's own filtering does not change them.
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", texture, 0)
        program.setSamplerTexIdUniform("uLut", lut, 1)
        program.setFloatsUniformIfPresent("uInputSize", floatArrayOf(width.toFloat(), height.toFloat()))
        program.setFloatsUniformIfPresent("uDir", direction)
        program.setFloatsUniformIfPresent("uLutValue", floatArrayOf(planes.min, planes.range))
        program.setFloatsUniformIfPresent("uSigmoid", floatArrayOf(if (pre) 1f else 0f, if (post) 1f else 0f))
        media3DrawQuad(program)
    }

    override fun release() {
        super.release()
        try {
            releaseIntermediate()
            GlUtil.deleteTexture(lut)
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun releaseIntermediate() {
        mid?.release()
        mid = null
    }
}

/** MPV's oversample: nearest neighbour, pixels straddling an output edge blended linearly. */
private class Media3OversampleShaderProgram(
    useHighPrecision: Boolean,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : BaseGlShaderProgram(useHighPrecision, /* texturePoolCapacity= */ 1) {
    private val program = media3GlProgram(MEDIA3_OVERSAMPLE)
    private var inputSize = floatArrayOf(1f, 1f)
    private var scale = floatArrayOf(1f, 1f)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val (width, height) = media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight)
        inputSize = floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat())
        scale = floatArrayOf(width.toFloat() / inputWidth, height.toFloat() / inputHeight)
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            media3LinearFilter(inputTexId)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniformIfPresent("uInputSize", inputSize)
            program.setFloatsUniformIfPresent("uScale", scale)
            media3DrawQuad(program)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
}

private const val KERNEL_HEADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform vec2 uInputSize;
uniform vec2 uDir;
uniform vec2 uLutValue;
uniform vec2 uSigmoid;
varying vec2 vTexSamplingCoord;
// MPV's sigmoid-upscaling (center 0.75, slope 6.5) in BT.1886 linear light.
const float kSigCenter = 0.75;
const float kSigSlope = 6.5;
const float kSigOffset = 0.0075772413;
const float kSigScale = 0.8279063;
vec3 toSigmoid(vec3 c) {
  vec3 l = pow(clamp(c, 0.0, 1.0), vec3(2.4));
  return kSigCenter - log(1.0 / (l * kSigScale + kSigOffset) - 1.0) / kSigSlope;
}
vec3 fromSigmoid(vec3 c) {
  vec3 l = (1.0 / (1.0 + exp(kSigSlope * (kSigCenter - clamp(c, 0.0, 1.0)))) - kSigOffset) / kSigScale;
  return pow(clamp(l, 0.0, 1.0), vec3(1.0 / 2.4));
}
vec3 finishColor(vec3 c) { return uSigmoid.y > 0.5 ? fromSigmoid(c) : clamp(c, 0.0, 1.0); }
"""

/** Shader for [kernel], with its radius and tap loop baked in like MPV's generated code. */
internal fun media3KernelShader(kernel: Media3Kernel): String {
    val taps = kernel.taps
    val radius = String.format(java.util.Locale.ROOT, "%.9f", kernel.support)
    val size = MEDIA3_KERNEL_LUT_SIZE
    val weight = """
// Kernel weight at distance d (source pixels) from the LUT; zero at and beyond the radius.
float weight(float d) {
  float t = d / $radius;
  if (t >= 1.0) return 0.0;
  vec2 q = texture2D(uLut, vec2((t * ${size - 1}.0 + 0.5) / $size.0, 0.5)).rg;
  // 16-bit value from two bytes; factors below 1 keep mediump in range.
  return (q.r * (65280.0 / 65535.0) + q.g * (255.0 / 65535.0)) * uLutValue.y + uLutValue.x;
}
vec3 texel(vec2 t) {
  vec3 c = texture2D(uTexSampler, (t + 0.5) / uInputSize).rgb;
  return uSigmoid.x > 0.5 ? toSigmoid(c) : c;
}
"""
    val main = if (kernel.polar) """
void main() {
  vec2 src = vTexSamplingCoord * uInputSize - 0.5;
  vec2 base = floor(src);
  vec2 f = src - base;
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int y = ${1 - taps}; y <= $taps; y++) {
    for (int x = ${1 - taps}; x <= $taps; x++) {
      vec2 o = vec2(float(x), float(y));
      float w = weight(length(o - f));
      if (w != 0.0) {
        sum += texel(base + o) * w;
        wsum += w;
      }
    }
  }
  vec3 color = wsum > 1e-6 ? sum / wsum : texel(floor(src + 0.5));
  gl_FragColor = vec4(finishColor(color), 1.0);
}
""" else """
void main() {
  vec2 src = vTexSamplingCoord * uInputSize - 0.5;
  float along = dot(src, uDir);
  float base = floor(along);
  float f = along - base;
  // The other axis is not scaled in this pass: its texel row or column.
  vec2 across = (1.0 - uDir) * floor(vTexSamplingCoord * uInputSize);
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int i = ${1 - taps}; i <= $taps; i++) {
    float w = weight(abs(float(i) - f));
    sum += texel(across + uDir * (base + float(i))) * w;
    wsum += w;
  }
  vec3 color = abs(wsum) > 1e-6 ? sum / wsum : texel(across + uDir * floor(along + 0.5));
  gl_FragColor = vec4(finishColor(color), 1.0);
}
"""
    return KERNEL_HEADER + weight + main
}

// MPV's pass_sample_oversample (threshold 0): round to nearest, blend only across the texel edge.
private const val MEDIA3_OVERSAMPLE = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uInputSize;
uniform vec2 uScale;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 pos = vTexSamplingCoord - 0.5 / uInputSize;
  vec2 fcoord = fract(pos * uInputSize - 0.5);
  vec2 coeff = clamp(fcoord * uScale, 0.0, 1.0);
  vec3 color = texture2D(uTexSampler, pos + (coeff - fcoord) / uInputSize).rgb;
  gl_FragColor = vec4(color, 1.0);
}
"""

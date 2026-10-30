@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * SSimSuperRes adapted from the LGPL-3.0-or-later quality_ssim_super_*.glsl shader.
 *
 * A single Media3 effect wraps the selected upscaler: the original input survives until
 * the SSim correction finishes. This avoids a race-prone global PREKERNEL frame cache and
 * retains the chosen scaler (including its internal multiple passes).
 */
internal class Media3SsimSuperEffect(private val upscale: GlEffect) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        upscale.isNoOp(inputWidth, inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        media3RequireFragmentHighp("SSimSuperRes")
        if (useHdr) throw VideoFrameProcessingException("SSimSuperRes is SDR only")
        return Media3SsimSuperShaderProgram(upscale.toGlShaderProgram(context, false))
    }
}

private class Media3SsimSuperShaderProgram(
    private val upscale: GlShaderProgram,
) : Media3SsimProgram() {
    private val resize = shader(SSIM_RESIZE_SHADER)
    private val variance = shader(SSIM_SUPER_VARIANCE)
    private val correct = shader(SSIM_SUPER_CORRECT)
    private var inputWidth = 1
    private var inputHeight = 1
    private var outWidth = 1
    private var outHeight = 1
    private lateinit var enlarged: Media3Intermediate
    private lateinit var lowHorizontal: Media3Intermediate
    private lateinit var low: Media3Intermediate
    private lateinit var localVariance: Media3Intermediate

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        clearTargets()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val result = upscale.configure(inputWidth, inputHeight)
        outWidth = result.width
        outHeight = result.height
        if (outWidth <= inputWidth || outHeight <= inputHeight) {
            throw VideoFrameProcessingException("SSimSuperRes requires enlargement on both axes")
        }
        enlarged = newTarget(outWidth, outHeight)
        lowHorizontal = newTarget(outWidth, inputHeight)
        low = newTarget(inputWidth, inputHeight)
        localVariance = newTarget(inputWidth, inputHeight)
        return result
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val finalFbo = currentFramebuffer()
        val (finalWidth, finalHeight) = currentViewport()
        try {
            // The selected Media3 scaler (not a replacement bicubic) renders the baseline.
            focus(enlarged, outWidth, outHeight)
            upscale.drawFrame(inputTexId, presentationTimeUs)

            // LOWRES moments: Mitchell-like B=0.334 / C=0.333, vertical then horizontal.
            resizePass(enlarged.texture, outWidth, outHeight,
                lowHorizontal, outWidth, inputHeight, horizontalAxis = false, varianceMode = 1f)
            resizePass(lowHorizontal.texture, outWidth, inputHeight,
                low, inputWidth, inputHeight, horizontalAxis = true, varianceMode = 2f)

            focus(localVariance, inputWidth, inputHeight)
            draw(variance, listOf("uOriginal" to inputTexId, "uLow" to low.texture),
                listOf("uTextureSize" to dimensions(inputWidth, inputHeight)))

            GlUtil.focusFramebufferUsingCurrentContext(finalFbo, finalWidth, finalHeight)
            draw(correct, listOf(
                "uEnlarged" to enlarged.texture,
                "uOriginal" to inputTexId,
                "uLow" to low.texture,
                "uVariance" to localVariance.texture,
            ), listOf("uSourceSize" to dimensions(inputWidth, inputHeight)))
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } finally {
            GlUtil.focusFramebufferUsingCurrentContext(finalFbo, finalWidth, finalHeight)
        }
    }

    private fun resizePass(
        input: Int, srcWidth: Int, srcHeight: Int,
        dst: Media3Intermediate, width: Int, height: Int,
        horizontalAxis: Boolean, varianceMode: Float,
    ) {
        focus(dst, width, height)
        draw(resize, listOf("uSource" to input), listOf(
            "uInputSize" to dimensions(srcWidth, srcHeight),
            "uOutputSize" to dimensions(width, height),
            "uDirection" to if (horizontalAxis) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f),
            "uBC" to floatArrayOf(0.334f, 0.333f),
            "uSquare" to floatArrayOf(0f),
            "uVariance" to floatArrayOf(varianceMode),
        ))
    }

    override fun release() {
        super.release()
        upscale.release()
        try {
            resize.delete()
            variance.delete()
            correct.delete()
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
}

/** Source/LOWRES variance cross-kernel, as in the reference SSSR var pass. */
private const val SSIM_SUPER_VARIANCE = """
uniform sampler2D uOriginal;
uniform sampler2D uLow;
uniform vec2 uTextureSize;
void main() {
    vec3 meanOriginal = vec3(0.0);
    vec3 meanLow = vec3(0.0);
    // Center and four cross-neighbours: center weight 1, each neighbour weight 0.25.
    for (int y=-1; y<=1; y++) for (int x=-1; x<=1; x++) {
        float w = (x == 0 && y == 0) ? 1.0 : ((x == 0 || y == 0) ? 0.25 : 0.0);
        vec2 uv = vTexSamplingCoord + vec2(float(x), float(y)) / uTextureSize;
        meanOriginal += ssimSample(uOriginal, uv) * w;
        meanLow += ssimSample(uLow, uv) * w;
    }
    meanOriginal *= 0.5;
    meanLow *= 0.5;
    float originalVariance = 0.0;
    float lowVariance = 0.0;
    for (int y=-1; y<=1; y++) for (int x=-1; x<=1; x++) {
        float w = (x == 0 && y == 0) ? 1.0 : ((x == 0 || y == 0) ? 0.25 : 0.0);
        vec2 uv = vTexSamplingCoord + vec2(float(x), float(y)) / uTextureSize;
        vec3 a = ssimSample(uOriginal, uv) - meanOriginal;
        vec3 b = ssimSample(uLow, uv) - meanLow;
        originalVariance += w * ssimLuma(a * a);
        lowVariance += w * ssimLuma(b * b);
    }
    gl_FragColor = vec4(max(originalVariance * 0.5, 1e-6),
        max(lowVariance * 0.5, 1e-6), 0.0, 1.0);
}
"""

/** SSSR final reconstruction using the original frame and downscaled error estimate. */
private const val SSIM_SUPER_CORRECT = """
uniform sampler2D uEnlarged;
uniform sampler2D uOriginal;
uniform sampler2D uLow;
uniform sampler2D uVariance;
uniform vec2 uSourceSize;
void main() {
    vec3 c0 = ssimSample(uEnlarged, vTexSamplingCoord);
    vec2 coord = vTexSamplingCoord * uSourceSize - 0.5;
    vec2 nearest = floor(coord + 0.5);
    vec2 offset = coord - nearest;
    float localMoment = 0.0;
    float localWeight = 0.0;
    for (int y=-1; y<=1; y++) for (int x=-1; x<=1; x++) {
        vec2 delta = vec2(float(x), float(y));
        vec2 w = clamp(vec2(1.5) - abs(delta), 0.0, 1.0);
        vec2 uv = (nearest + delta + 0.5) / uSourceSize;
        localMoment += w.x * w.y * texture2D(uLow, ssimClampUv(uv)).a;
        localWeight += w.x * w.y;
    }
    localMoment /= max(localWeight, 1e-6);

    vec3 correction = vec3(0.0);
    float weightSum = 0.0;
    for (int y=-1; y<=1; y++) for (int x=-1; x<=1; x++) {
        vec2 delta = vec2(float(x), float(y));
        vec2 uv = (nearest + delta + 0.5) / uSourceSize;
        vec3 original = ssimSample(uOriginal, uv);
        vec4 low = texture2D(uLow, ssimClampUv(uv));
        vec2 variances = texture2D(uVariance, ssimClampUv(uv)).rg;
        float ratio = -1.5 * sqrt(variances.r / max(variances.g + localMoment, 1e-6));
        vec2 hann = cos(3.141592653589793 * (delta - offset) / 3.0);
        float error = ssimLuma((c0 - low.rgb) * (c0 - low.rgb));
        float w = (hann.x * hann.y) / max(error + low.a, 1e-6);
        correction += w * (original + low.rgb * ratio + (-1.0 - ratio) * c0);
        weightSum += w;
    }
    vec3 result = c0 + correction / max(weightSum, 1e-6);
    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
"""
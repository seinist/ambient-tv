@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * SSimDownscaler, adapted from the LGPL-3.0-or-later quality_ssim_down_*.glsl shader.
 * Four conceptual stages: Catmull-Rom second moment, Mitchell baseline, local contrast
 * ratio and ratio-weighted reconstruction. Unlike MPV hooks it receives one input frame;
 * both moment and baseline are recreated before any reference texture can be overwritten.
 */
internal class Media3SsimDownEffect(
    private val targetWidth: Int,
    private val targetHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3DownscaleSize(inputWidth, inputHeight, targetWidth, targetHeight) ==
            (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        media3RequireFragmentHighp("SSimDownscaler")
        if (useHdr) throw VideoFrameProcessingException("SSimDownscaler is SDR only")
        return Media3SsimDownShaderProgram(targetWidth, targetHeight)
    }
}

private class Media3SsimDownShaderProgram(
    private val targetWidth: Int,
    private val targetHeight: Int,
) : Media3SsimProgram() {
    private val resize = shader(SSIM_RESIZE_SHADER)
    private val local = shader(SSIM_DOWN_LOCAL)
    private val correct = shader(SSIM_DOWN_CORRECT)
    private var inputWidth = 1
    private var inputHeight = 1
    private var outWidth = 1
    private var outHeight = 1
    private lateinit var horizontal: Media3Intermediate
    private lateinit var squareHorizontal: Media3Intermediate
    private lateinit var baseline: Media3Intermediate
    private lateinit var moment: Media3Intermediate
    private lateinit var localStats: Media3Intermediate

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        clearTargets()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val (width, height) = media3DownscaleSize(inputWidth, inputHeight, targetWidth, targetHeight)
        outWidth = width
        outHeight = height
        horizontal = newTarget(outWidth, inputHeight)
        squareHorizontal = newTarget(outWidth, inputHeight)
        baseline = newTarget(outWidth, outHeight)
        moment = newTarget(outWidth, outHeight)
        localStats = newTarget(outWidth, outHeight)
        return Size(outWidth, outHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val finalFbo = currentFramebuffer()
        val (finalWidth, finalHeight) = currentViewport()
        try {
            // Catmull-Rom second moment of the original source, horizontal then vertical.
            resizePass(inputTexId, inputWidth, inputHeight, squareHorizontal,
                outWidth, inputHeight, horizontalAxis = true, catmull = true, squareInput = true)
            resizePass(squareHorizontal.texture, outWidth, inputHeight, moment,
                outWidth, outHeight, horizontalAxis = false, catmull = true, squareInput = false)

            // MPV SSimDownscaler assumes the baseline dscale=Mitchell; compute it explicitly.
            resizePass(inputTexId, inputWidth, inputHeight, horizontal,
                outWidth, inputHeight, horizontalAxis = true, catmull = false, squareInput = false)
            resizePass(horizontal.texture, outWidth, inputHeight, baseline,
                outWidth, outHeight, horizontalAxis = false, catmull = false, squareInput = false)

            focus(localStats, outWidth, outHeight)
            draw(local, listOf("uBase" to baseline.texture, "uMoment" to moment.texture),
                listOf("uTextureSize" to dimensions(outWidth, outHeight)))

            GlUtil.focusFramebufferUsingCurrentContext(finalFbo, finalWidth, finalHeight)
            draw(correct, listOf("uBase" to baseline.texture, "uLocal" to localStats.texture),
                listOf("uTextureSize" to dimensions(outWidth, outHeight)))
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } finally {
            GlUtil.focusFramebufferUsingCurrentContext(finalFbo, finalWidth, finalHeight)
        }
    }

    private fun resizePass(
        input: Int, sourceWidth: Int, sourceHeight: Int,
        output: Media3Intermediate, width: Int, height: Int,
        horizontalAxis: Boolean, catmull: Boolean, squareInput: Boolean,
    ) {
        focus(output, width, height)
        draw(resize, listOf("uSource" to input), listOf(
            "uInputSize" to dimensions(sourceWidth, sourceHeight),
            "uOutputSize" to dimensions(width, height),
            "uDirection" to if (horizontalAxis) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f),
            "uBC" to if (catmull) floatArrayOf(0f, 0.5f) else floatArrayOf(1f / 3f, 1f / 3f),
            "uSquare" to floatArrayOf(if (squareInput) 1f else 0f),
            "uVariance" to floatArrayOf(0f),
        ))
    }

    override fun release() {
        super.release()
        try {
            resize.delete()
            local.delete()
            correct.delete()
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
}

private const val SSIM_DOWN_LOCAL = """
uniform sampler2D uBase;
uniform sampler2D uMoment;
uniform vec2 uTextureSize;
void main() {
    vec3 mean = vec3(0.0);
    vec3 squared = vec3(0.0);
    vec3 moment = vec3(0.0);
    float norm = 0.0;
    for (int y=-1; y<=1; y++) for (int x=-1; x<=1; x++) {
        float w = (x == 0 ? 1.0 : 0.5) * (y == 0 ? 1.0 : 0.5);
        vec2 uv = vTexSamplingCoord + vec2(float(x), float(y)) / uTextureSize;
        vec3 c = ssimSample(uBase, uv);
        mean += c * w;
        squared += c * c * w;
        moment += ssimSample(uMoment, uv) * w;
        norm += w;
    }
    mean /= norm;
    squared /= norm;
    moment /= norm;
    float sLow = ssimLuma(max(squared - mean * mean, vec3(0.0)));
    float sHigh = ssimLuma(max(moment - mean * mean, vec3(0.0)));
    float ratio = sLow > sHigh ? clamp(sHigh / max(sLow, 1e-6), 0.0, 1.0)
        : sqrt((sHigh + 10.0 / 65025.0) / (sLow + 10.0 / 65025.0));
    gl_FragColor = vec4(mean, clamp(ratio, 0.0, 8.0));
}
"""

private const val SSIM_DOWN_CORRECT = """
uniform sampler2D uBase;
uniform sampler2D uLocal;
uniform vec2 uTextureSize;
void main() {
    vec3 mean = vec3(0.0);
    vec3 ratioMean = vec3(0.0);
    float ratio = 0.0;
    float norm = 0.0;
    for (int y=-1; y<=1; y++) for (int x=-1; x<=1; x++) {
        float w = (x == 0 ? 1.0 : 0.5) * (y == 0 ? 1.0 : 0.5);
        vec2 uv = vTexSamplingCoord + vec2(float(x), float(y)) / uTextureSize;
        vec4 mr = texture2D(uLocal, ssimClampUv(uv));
        mean += mr.rgb * w;
        ratioMean += mr.rgb * mr.a * w;
        ratio += mr.a * w;
        norm += w;
    }
    vec3 original = ssimSample(uBase, vTexSamplingCoord);
    vec3 result = mean / norm + (ratio / norm) * original - ratioMean / norm;
    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
"""
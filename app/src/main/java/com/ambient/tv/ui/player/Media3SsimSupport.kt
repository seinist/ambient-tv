@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram

/**
 * Shared GL plumbing for SSimDownscaler / SSimSuperRes.
 *
 * Adapted from LGPL-3.0-or-later MPV GLSL shader sources bundled with Ambient TV
 * (quality_ssim_down_* and quality_ssim_super_*). MPV's hooks can see PREKERNEL and
 * POSTKERNEL at once; the Media3 equivalents calculate all stages synchronously,
 * keeping the original texture alive for the entire draw call.
 *
 * Media3 supplies linear BT.709 RGB for SDR GlEffects. No extra gamma transform is applied.
 */
internal abstract class Media3SsimProgram : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1,
) {
    private val targets = mutableListOf<Media3Intermediate>()
    protected fun newTarget(width: Int, height: Int): Media3Intermediate =
        media3SsimIntermediate(width, height).also { targets.add(it) }

    protected fun clearTargets() {
        targets.forEach(Media3Intermediate::release)
        targets.clear()
    }

    protected fun focus(target: Media3Intermediate, width: Int, height: Int) =
        GlUtil.focusFramebufferUsingCurrentContext(target.fbo, width, height)

    protected fun currentFramebuffer(): Int =
        IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]

    protected fun currentViewport(): Pair<Int, Int> {
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
        return viewport[2] to viewport[3]
    }

    protected fun shader(body: String): GlProgram = media3GlProgram(SSIM_FRAGMENT_COMMON + body)

    protected fun draw(
        shader: GlProgram,
        textures: List<Pair<String, Int>>,
        uniforms: List<Pair<String, FloatArray>> = emptyList(),
    ) {
        shader.use()
        textures.forEachIndexed { index, (name, texture) ->
            shader.setSamplerTexIdUniform(name, texture, index)
        }
        uniforms.forEach { (name, values) -> shader.setFloatsUniformIfPresent(name, values) }
        media3DrawQuad(shader)
    }

    protected fun dimensions(width: Int, height: Int) = floatArrayOf(width.toFloat(), height.toFloat())

    override fun release() {
        super.release()
        try { clearTargets() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
}

internal const val SSIM_FRAGMENT_COMMON = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTexSamplingCoord;
vec2 ssimClampUv(vec2 p) { return clamp(p, vec2(0.0), vec2(1.0)); }
vec3 ssimSample(sampler2D tex, vec2 uv) { return texture2D(tex, ssimClampUv(uv)).rgb; }
float ssimLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
"""

/**
 * Separable Catmull-Rom (SSimDown squared-color moment), Mitchell (POSTKERNEL baseline),
 * or cubic B/C=0.334/0.333 (SSimSuper LOWRES recovery). Support stretches on reduction,
 * with a 2x maximum as enforced by the planner budget.
 *
 * uSquare=1 squares RGB before filtering (first SSimDown moment pass).
 * uVariance=1 stores first SSimSuper LOWRES variance; 2 adds the earlier contribution.
 */
internal const val SSIM_RESIZE_SHADER = """
uniform sampler2D uSource;
uniform vec2 uInputSize;
uniform vec2 uOutputSize;
uniform vec2 uDirection;
uniform vec2 uBC;
uniform float uSquare;
uniform float uVariance;
float ssimCubic(float distance) {
    float x = abs(distance);
    float B = uBC.x;
    float C = uBC.y;
    if (x < 1.0) return ((12.0 - 9.0*B - 6.0*C)*x*x*x + (-18.0 + 12.0*B + 6.0*C)*x*x + (6.0 - 2.0*B)) / 6.0;
    if (x < 2.0) return ((-B - 6.0*C)*x*x*x + (6.0*B + 30.0*C)*x*x + (-12.0*B - 48.0*C)) / 6.0;
    return 0.0;
}
void main() {
    vec2 src = vTexSamplingCoord * uInputSize - 0.5;
    float along = dot(src, uDirection);
    float factor = min(1.0, dot(uOutputSize, uDirection) / dot(uInputSize, uDirection));
    float base = floor(along);
    vec4 sum = vec4(0.0);
    float weightedSquaredLuma = 0.0;
    float sumWeight = 0.0;
    for (int i = -4; i <= 5; i++) {
        float w = ssimCubic(abs(float(i) - fract(along)) * factor);
        if (w != 0.0) {
            vec2 uv = vTexSamplingCoord;
            if (uDirection.x > 0.5) uv.x = (base + float(i) + 0.5) / uInputSize.x;
            else uv.y = (base + float(i) + 0.5) / uInputSize.y;
            vec4 sampleColor = texture2D(uSource, ssimClampUv(uv));
            if (uSquare > 0.5) sampleColor.rgb *= sampleColor.rgb;
            sum += w * sampleColor;
            weightedSquaredLuma += w * ssimLuma(sampleColor.rgb * sampleColor.rgb);
            sumWeight += w;
        }
    }
    vec4 avg = sum / max(sumWeight, 1e-6);
    if (uVariance > 0.5) {
        float difference = abs(weightedSquaredLuma / max(sumWeight, 1e-6) - ssimLuma(avg.rgb * avg.rgb));
        float accumulated = uVariance > 1.5 ? max(avg.a, 0.0) : 0.0;
        avg.a = max(difference, 5e-7) + accumulated;
    } else avg.a = 1.0;
    gl_FragColor = avg;
}
"""
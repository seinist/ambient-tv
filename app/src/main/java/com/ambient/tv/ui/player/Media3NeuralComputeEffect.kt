@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.ambient.tv.ui.player.backend.MPV_IMAGE_SHADERS
import kotlin.math.ceil

/**
 * ArtCNN/CuNNy are GLSL compute networks, not fragment shaders. This engine preserves each
 * bundled MPV compute pass and its learned coefficients, translating only libplacebo //!HOOK
 * descriptors into GLES 3.1 shader bindings. The fp16 *render targets* preserve signed output;
 * arithmetic uses fp32, avoiding the Vulkan-only float16 arithmetic extension.
 */
internal val MEDIA3_ARTCNN_SCALERS = setOf(
    "artcnn16", "artcnn32", "artcnn16_ds", "artcnn16_dn", "artcnn32_ds", "artcnn32_dn",
)
internal val MEDIA3_CUNNY_SCALERS = setOf(
    "cunny_fast_ds", "cunny_faster_ds", "cunny_fast_soft",
    "cunny_faster_soft", "cunny_veryfast_soft",
)
internal val MEDIA3_CUNNY_QUANTIZED_SCALERS = setOf(
    "cunny_fast_soft_q", "cunny_faster_soft_q", "cunny_veryfast_soft_q",
)
/**
 * Quantized CuNNy 8-bit weights: emulate signed dp4a on GLES 3.1 integer operations;
 * this retains the packed coefficients without requiring Vulkan SPIR-V extensions.
 * It is slower than native dp4a and receives the strictest frame budget.
 */
internal val MEDIA3_NEURAL_COMPUTE_SCALERS =
    MEDIA3_ARTCNN_SCALERS + MEDIA3_CUNNY_SCALERS + MEDIA3_CUNNY_QUANTIZED_SCALERS

internal data class Media3ComputePass(
    val description: String,
    val bindings: List<String>,
    val save: String?,
    val tileWidth: Int,
    val tileHeight: Int,
    val localWidth: Int,
    val localHeight: Int,
    val widthMultiplier: Int,
    val heightMultiplier: Int,
    val source: String,
)

private fun media3ComputeDimension(meta: String?, axis: String): Int? {
    if (meta == null) return 1
    val m = Regex("""LUMA\.""" + axis + """(?:\s+(\d+(?:\.0+)?)\s+\*)?""")
        .matchEntire(meta.trim()) ?: return null
    return m.groupValues[1].takeIf(String::isNotBlank)?.toDoubleOrNull()?.toInt() ?: 1
}

/** Accept known, unquantized MPV compute shaders only. Unknown hooks fail closed. */
internal fun media3ComputePasses(key: String, text: String): List<Media3ComputePass>? {
    if (key !in MEDIA3_NEURAL_COMPUTE_SCALERS) return null
    if (key !in MEDIA3_CUNNY_QUANTIZED_SCALERS &&
        ("GL_EXT_spirv_intrinsics" in text || "dp4a(" in text)) return null
    if (key in MEDIA3_CUNNY_QUANTIZED_SCALERS &&
        ("GL_EXT_spirv_intrinsics" !in text || "spirv_instruction" !in text)) return null
    val expectedCount = if (key in MEDIA3_ARTCNN_SCALERS) 8 else 4
    val marks = Regex("(?m)^//!DESC ").findAll(text).map { it.range.first }.toList()
    if (marks.size != expectedCount) return null
    val known = mutableSetOf("LUMA")
    val parsed = mutableListOf<Media3ComputePass>()
    marks.forEachIndexed { index, at ->
        val lines = text.substring(at, marks.getOrNull(index + 1) ?: text.length).lines()
        val description = lines.firstOrNull()?.removePrefix("//!DESC ")?.trim() ?: return null
        val compute = lines.firstOrNull { it.startsWith("//!COMPUTE ") }
            ?.removePrefix("//!COMPUTE ")?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull(String::toIntOrNull) ?: return null
        if (compute.size != 4 || compute.any { it <= 0 } ||
            compute[2].toLong() * compute[3] > 1024) return null
        val binds = lines.filter { it.startsWith("//!BIND ") }
            .map { it.removePrefix("//!BIND ").trim() }
        val saves = lines.filter { it.startsWith("//!SAVE ") }
            .map { it.removePrefix("//!SAVE ").trim() }
        val out = saves.singleOrNull()
        val last = index == marks.lastIndex
        if (!lines.any { it == "//!HOOK LUMA" } || binds.isEmpty() ||
            binds.distinct().size != binds.size || binds.any { it !in known } ||
            (last && out != null) || (!last && out == null) ||
            (out != null && (out in known || out in binds))) return null
        val w = media3ComputeDimension(
            lines.firstOrNull { it.startsWith("//!WIDTH ") }?.removePrefix("//!WIDTH "),
            "w") ?: return null
        val h = media3ComputeDimension(
            lines.firstOrNull { it.startsWith("//!HEIGHT ") }?.removePrefix("//!HEIGHT "),
            "h") ?: return null
        if (w !in 1..4 || h !in 1..2) return null
        val body = lines.filterNot { it.startsWith("//!") }.joinToString("\n").trim()
        if (!body.contains("void hook()") || "#version" in body) return null
        parsed += Media3ComputePass(description, binds, out,
            compute[0], compute[1], compute[2], compute[3], w, h, body)
        out?.let(known::add)
    }
    if (parsed.first().bindings != listOf("LUMA") ||
        !parsed.last().bindings.any { it != "LUMA" }) return null
    return parsed
}

/** Original source body is preserved, except for the float16 extension's fp32 fallback. */
internal fun media3ComputeShader(pass: Media3ComputePass): String = buildString {
    append("#version 310 es\nprecision highp float;\nprecision highp int;\n")
    append("layout(local_size_x = ").append(pass.localWidth)
        .append(", local_size_y = ").append(pass.localHeight)
        .append(", local_size_z = 1) in;\n")
    append("layout(rgba16f, binding = 0) writeonly uniform highp image2D out_image;\n")
    append("uniform highp vec2 uOutputSize;\n")
    pass.bindings.forEach { name ->
        append("uniform highp sampler2D u").append(name).append(";\n")
        append("uniform highp vec2 uSize_").append(name).append(";\n")
        append("#define ").append(name).append("_raw u").append(name).append("\n")
        append("#define ").append(name).append("_mul 1.0\n")
        append("#define ").append(name).append("_size uSize_").append(name).append("\n")
        append("#define ").append(name).append("_pt (1.0 / uSize_").append(name).append(")\n")
        append("#define ").append(name).append("_pos ((vec2(gl_GlobalInvocationID.xy) + vec2(0.5)) / uOutputSize)\n")
        append("#define ").append(name).append("_tex(uv) texture(u").append(name).append(", (uv))\n")
        append("#define ").append(name).append("_texOff(offset) texture(u")
            .append(name).append(", ").append(name)
            .append("_pos + (offset) / uSize_").append(name).append(")\n")
    }
    // The packed weights are already float literals. Use fp32 arithmetic even if the
    // device exposes float16, so the same source compiles on normal GLES3.1 hardware.
    val fragment = pass.source.replace(
        Regex("""(?s)#extension GL_EXT_shader_explicit_arithmetic_types_float16\s*:\s*enable\s*#ifdef GL_EXT_shader_explicit_arithmetic_types_float16.*?#endif"""),
        "#define V4 vec4\n#define M4 mat4\n#define F float",
    )
    append(if ("GL_EXT_spirv_intrinsics" in fragment) media3CuNnyDotProductFallback(fragment) else fragment)
}

/**
 * SPIR-V's OpSDot packed 4x8-bit signed integer dot-product is exactly the sum of four
 * signed byte multiplications. GLES 3.1 bitwise integer operations can reproduce it,
 * but without hardware dp4a acceleration. Never reinterpret the stored model weights.
 */
internal fun media3CuNnyDotProductFallback(body: String): String {
    val directive = "#extension GL_EXT_spirv_intrinsics : require"
    val instruction = Regex(
        """(?s)spirv_instruction\s*\(extensions = \["SPV_KHR_integer_dot_product"\],\s*capabilities = \[6019, 6018\], id = 4450\)\s*int dp4\(int a, int b, spirv_literal int fmt\);"""
    )
    if (directive !in body || !instruction.containsMatchIn(body))
        throw IllegalArgumentException("Unknown CuNNy packed dot product encoding")
    val fallback = """
int cunnySignedByte(int packed, int shift) {
    int v = (packed >> shift) & 255;
    return v >= 128 ? v - 256 : v;
}
int dp4(int a, int b, int fmt) {
    int result = 0;
    for (int i = 0; i < 4; i++) {
        int shift = i * 8;
        result += cunnySignedByte(a, shift) * cunnySignedByte(b, shift);
    }
    return result;
}
"""
    return body.replace(directive, "").replace(instruction, fallback)
}

/** Heavy compute is disabled by default on unsupported GPUs and above a strict pixel budget. */
internal fun media3NeuralComputeBudgetAllows(
    key: String, width: Int, height: Int, targetWidth: Int, targetHeight: Int, fps: Float,
): Boolean {
    if (key !in MEDIA3_NEURAL_COMPUTE_SCALERS ||
        width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return false
    val (fitW, fitH) = media3FitSize(width, height, targetWidth, targetHeight)
    val enlargement = minOf(fitW.toDouble() / width, fitH.toDouble() / height)
    if (enlargement <= 1.3 || enlargement > 2.0) return false
    val rate = fps.takeIf { it.isFinite() && it > 0f } ?: 30f
    val maxPixels = when {
        key in MEDIA3_CUNNY_QUANTIZED_SCALERS -> 160L * 90L
        key.startsWith("artcnn32") -> 160L * 90L
        key.startsWith("artcnn16") -> 256L * 144L
        key.contains("veryfast") -> 640L * 360L
        key.contains("faster") -> 480L * 270L
        else -> 400L * 225L
    }
    return width.toLong() * height * rate <= maxPixels * 24L
}

internal class Media3NeuralComputeEffect(
    private val key: String,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3FitSize(inputWidth, inputHeight, targetWidth, targetHeight) ==
            (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Neural compute requires SDR input")
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        if (!version.contains("OpenGL ES 3.1") && !version.contains("OpenGL ES 3.2"))
            throw VideoFrameProcessingException("ArtCNN/CuNNy require GLES 3.1 compute")
        media3RequireFragmentHighp("Neural compute")
        val res = MPV_IMAGE_SHADERS[key]?.resourceId
            ?: throw VideoFrameProcessingException("Missing compute shader model " + key)
        val source = context.resources.openRawResource(res)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val passes = media3ComputePasses(key, source)
            ?: throw VideoFrameProcessingException("Unsupported MPV compute model " + key)
        return Media3NeuralComputeProgram(passes, targetWidth, targetHeight)
    }
}

private class Media3NeuralComputeProgram(
    private val passes: List<Media3ComputePass>,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : BaseGlShaderProgram(false, /* texturePoolCapacity= */ 1) {
    private val compute = passes.map { pass -> compileCompute(media3ComputeShader(pass)) }
    private val prepareLuma: GlProgram = media3GlProgram(
        """#version 100
precision highp float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uRgb;
void main() {
    vec3 linear = texture2D(uRgb, vTexSamplingCoord).rgb;
    vec3 encoded = pow(clamp(linear, 0.0, 1.0), vec3(1.0 / 2.2));
    float y = dot(encoded, vec3(0.2126, 0.7152, 0.0722));
    gl_FragColor = vec4(y, 0.0, 0.0, 1.0);
}"""
    )
    private val combineLuma: GlProgram = media3GlProgram(
        """#version 100
precision highp float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uRgb;
uniform sampler2D uPrediction;
void main() {
    vec3 linear = texture2D(uRgb, vTexSamplingCoord).rgb;
    vec3 encoded = pow(clamp(linear, 0.0, 1.0), vec3(1.0 / 2.2));
    float originalY = dot(encoded, vec3(0.2126, 0.7152, 0.0722));
    float enhancedY = texture2D(uPrediction, vTexSamplingCoord).r;
    vec3 corrected = clamp(encoded + vec3(enhancedY - originalY), 0.0, 1.0);
    gl_FragColor = vec4(pow(corrected, vec3(2.2)), 1.0);
}"""
    )
    private val targets = mutableMapOf<String, Media3Intermediate>()
    private val sizes = mutableMapOf<String, Pair<Int, Int>>()
    private var width = 1
    private var height = 1
    private var finalImage: Media3Intermediate? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        clearBuffers()
        width = inputWidth
        height = inputHeight
        val maxSize = media3MaxTextureSize()
        val units = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, it, 0) }[0]
        val invocations = IntArray(1).also {
            GLES20.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, it, 0)
        }[0]
        if (passes.any { it.localWidth * it.localHeight > invocations || it.bindings.size > units } ||
            passes.any { width.toLong() * it.widthMultiplier > maxSize ||
                height.toLong() * it.heightMultiplier > maxSize })
            throw VideoFrameProcessingException("Insufficient neural compute GPU limits")
        try {
            targets["LUMA"] = media3SsimIntermediate(width, height)
            sizes["LUMA"] = width to height
            passes.forEach { pass ->
                val w = width * pass.widthMultiplier
                val h = height * pass.heightMultiplier
                val target = media3SsimIntermediate(w, h)
                pass.save?.let { name ->
                    targets[name] = target
                    sizes[name] = w to h
                } ?: run { finalImage = target }
            }
        } catch (t: Throwable) { clearBuffers(); throw t }
        val (outW, outH) = media3FitSize(width, height, targetWidth, targetHeight)
        return Size(outW, outH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val framebuffer = IntArray(1).also {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0)
        }[0]
        val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
        try {
            val luma = checkNotNull(targets["LUMA"])
            GlUtil.focusFramebufferUsingCurrentContext(luma.fbo, width, height)
            prepareLuma.use()
            prepareLuma.setSamplerTexIdUniform("uRgb", inputTexId, 0)
            media3DrawQuad(prepareLuma)
            passes.forEachIndexed { i, pass ->
                val output = pass.save?.let { checkNotNull(targets[it]) } ?: checkNotNull(finalImage)
                val (outW, outH) = width * pass.widthMultiplier to height * pass.heightMultiplier
                val id = compute[i]
                GLES31.glUseProgram(id)
                pass.bindings.forEachIndexed { unit, name ->
                    val source = checkNotNull(targets[name])
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.texture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(id, "u" + name), unit)
                    val (w, h) = checkNotNull(sizes[name])
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(id, "uSize_" + name),
                        w.toFloat(), h.toFloat())
                }
                GLES20.glUniform2f(GLES20.glGetUniformLocation(id, "uOutputSize"),
                    outW.toFloat(), outH.toFloat())
                GLES31.glBindImageTexture(0, output.texture, 0, false, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
                GLES31.glDispatchCompute(
                    ceil(outW.toDouble() / pass.tileWidth).toInt(),
                    ceil(outH.toDouble() / pass.tileHeight).toInt(), 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
                    GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
                GlUtil.checkGlError()
            }
            GlUtil.focusFramebufferUsingCurrentContext(framebuffer, viewport[2], viewport[3])
            media3LinearFilter(checkNotNull(finalImage).texture)
            combineLuma.use()
            combineLuma.setSamplerTexIdUniform("uRgb", inputTexId, 0)
            combineLuma.setSamplerTexIdUniform("uPrediction", checkNotNull(finalImage).texture, 1)
            media3DrawQuad(combineLuma)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } finally {
            GLES31.glUseProgram(0)
            GlUtil.focusFramebufferUsingCurrentContext(framebuffer, viewport[2], viewport[3])
        }
    }

    private fun clearBuffers() {
        targets.values.forEach(Media3Intermediate::release)
        targets.clear()
        sizes.clear()
        finalImage?.release()
        finalImage = null
    }

    override fun release() {
        super.release()
        try {
            clearBuffers()
            compute.forEach(GLES31::glDeleteProgram)
            prepareLuma.delete()
            combineLuma.delete()
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
}

private fun compileCompute(source: String): Int {
    val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
    if (shader == 0) throw VideoFrameProcessingException("Compute shader allocation failed")
    GLES31.glShaderSource(shader, source)
    GLES31.glCompileShader(shader)
    val ok = IntArray(1)
    GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
        val info = GLES31.glGetShaderInfoLog(shader).take(1200)
        GLES31.glDeleteShader(shader)
        throw VideoFrameProcessingException("Neural compute compile failed: " + info)
    }
    val program = GLES31.glCreateProgram()
    GLES31.glAttachShader(program, shader)
    GLES31.glLinkProgram(program)
    GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, ok, 0)
    GLES31.glDeleteShader(shader)
    if (ok[0] == 0) {
        val info = GLES31.glGetProgramInfoLog(program).take(1200)
        GLES31.glDeleteProgram(program)
        throw VideoFrameProcessingException("Neural compute link failed: " + info)
    }
    return program
}

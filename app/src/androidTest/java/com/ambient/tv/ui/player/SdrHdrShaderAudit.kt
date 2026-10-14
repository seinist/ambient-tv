package com.ambient.tv.ui.player

import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/** Tests actual GLSL colour math on an SDR pbuffer; does NOT certify HDR window/HDMI output. */
internal fun auditSdrHdrShader(log: (String) -> Unit) {
    val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
    check(eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    check(eglChooseConfig(display, intArrayOf(EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
    val context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT,
        intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE), 0)
    val pbuffer = eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
    check(context != EGL_NO_CONTEXT && pbuffer != EGL_NO_SURFACE)
    check(eglMakeCurrent(display, pbuffer, pbuffer, context))
    val programs = mutableListOf<Int>()
    val textures = IntArray(1)
    fun program(fragment: String): Int {
        val result = glCreateProgram()
        programs += result
        for ((type, source) in listOf(GL_VERTEX_SHADER to SdrHdrShader.vertex, GL_FRAGMENT_SHADER to fragment)) {
            val shader = glCreateShader(type)
            glShaderSource(shader, source.trimIndent())
            glCompileShader(shader)
            val status = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { glGetShaderInfoLog(shader) }
            glAttachShader(result, shader)
            glDeleteShader(shader)
        }
        glLinkProgram(result)
        val status = IntArray(1)
        glGetProgramiv(result, GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { glGetProgramInfoLog(result) }
        return result
    }
    try {
        // Compile/link the unchanged production external-texture program first.
        program(SdrHdrShader.fragment)
        // A known 1x1 texture avoids decoder and SurfaceTexture uncertainty in the colour oracle.
        // Only the sampler declaration differs; the complete production colour math is unchanged.
        val tested = program(SdrHdrShader.fragment
            .replace("#extension GL_OES_EGL_image_external : require", "")
            .replace("samplerExternalOES", "sampler2D"))
        glUseProgram(tested)
        glGenTextures(1, textures, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textures[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glUniform1i(glGetUniformLocation(tested, "video"), 0)
        glUniformMatrix4fv(glGetUniformLocation(tested, "textureMatrix"), 1, false,
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), 0)
        val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        val location = glGetAttribLocation(tested, "position")
        glEnableVertexAttribArray(location)
        glVertexAttribPointer(location, 2, GL_FLOAT, false, 0, vertices)
        glViewport(0, 0, 1, 1)
        val primaries = arrayOf(doubleArrayOf(.627404, .329283, .043313),
            doubleArrayOf(.069097, .919540, .011362), doubleArrayOf(.016391, .088013, .895595))
        var samples = 0
        for (transfer in 1..5) for (peak in listOf(300, 600)) {
            glUniform1i(glGetUniformLocation(tested, "transfer"), transfer)
            glUniform1f(glGetUniformLocation(tested, "peakNits"), peak.toFloat())
            for (rgb in listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(128, 128, 128),
                intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(10, 20, 30))) {
                val pixels = ByteBuffer.allocateDirect(4).apply {
                    put(rgb[0].toByte()); put(rgb[1].toByte()); put(rgb[2].toByte()); put(255.toByte()); position(0)
                }
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
                val actual = ByteBuffer.allocateDirect(4)
                glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, actual)
                check(glGetError() == GL_NO_ERROR)
                val linear = rgb.map { channel ->
                    val value = channel / 255.0
                    when (transfer) {
                        1 -> if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
                        2 -> value.pow(2.4) // BT.1886 display EOTF with zero black offset.
                        else -> value.pow(listOf(2.2, 2.6, 2.8)[transfer - 3])
                    }
                }
                val luminance = linear[0] * .2126 + linear[1] * .7152 + linear[2] * .0722
                val x = ((luminance - .5) / .5).coerceIn(0.0, 1.0)
                val highlight = x * x * (3 - 2 * x)
                val scale = 203 + (peak - 203) * highlight * highlight
                for (channel in 0..2) {
                    val nits = (0..2).sumOf { primaries[channel][it] * linear[it] } * scale
                    if (rgb.all { it == 255 }) {
                        val maxFall = checkNotNull(sdrHdrStaticMetadata(peak.toFloat(),
                            setOf("EGL_EXT_surface_CTA861_3_metadata")).toMap()[0x3361]) / 50_000.0
                        check(nits <= maxFall + .001) { "Full-white output exceeds advertised MaxFALL" }
                    }
                    val powered = (nits / 10000).pow(2610.0 / 16384)
                    val expected = ((3424.0 / 4096 + 2413.0 / 128 * powered) /
                        (1 + 2392.0 / 128 * powered)).pow(2523.0 / 32)
                    val observed = (actual.get(channel).toInt() and 255) / 255.0
                    check(abs(expected - observed) < 2.5 / 255) {
                        "PQ shader mismatch: transfer=$transfer peak=$peak rgb=${rgb.toList()} channel=$channel expected=$expected actual=$observed"
                    }
                }
                samples++
            }
        }
        log("PASS production external shader compiles; $samples GPU colour samples match linear-light BT.709/BT.2020/PQ reference (SDR pbuffer, not HDR display proof)")
    } finally {
        programs.forEach(::glDeleteProgram)
        glDeleteTextures(1, textures, 0)
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        eglDestroySurface(display, pbuffer)
        eglDestroyContext(display, context)
        eglTerminate(display)
        eglReleaseThread()
    }
}

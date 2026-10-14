package com.ambient.tv.ui.common

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Animated GPU screensaver scenes, loosely modelled on Kodi's ambient screensavers
 * (Really Slick Screensavers "Solar Winds"/"Plasma", "Starfield"). Each scene is one
 * GLES2 fragment shader rendered at a reduced resolution and upscaled; soft content
 * hides the scaling and keeps weak TV GPUs cool.
 */
internal enum class ShaderScene(val key: String, val renderWidth: Int, val body: String) {
    AURORA("aurora", 960, AURORA_SHADER),
    FLUID("fluid", 480, FLUID_SHADER),
    STARFIELD("starfield", 1280, STARFIELD_SHADER),
    SILK("silk", 800, SILK_SHADER);

    companion object {
        fun forKey(key: String): ShaderScene? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Render budget: resolution relative to the scene's base width, frame rate and noise detail.
 * High renders up to 1080p at 60 fps and suits current GPUs; economy is for old TV sticks.
 */
internal enum class ShaderQuality(val key: String, val scale: Float, val maxWidth: Int, val frameMs: Long, val octaves: Int) {
    ECONOMY("economy", 0.6f, 960, 41L, 3),
    BALANCED("balanced", 1f, 1280, 33L, 4),
    HIGH("high", 2f, 1920, 16L, 5);

    companion object {
        fun forKey(key: String): ShaderQuality = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/** Falls back to [fallback] when the device cannot create a GL context or compile the scene. */
@Composable
internal fun ShaderBackground(scene: ShaderScene, quality: ShaderQuality, brightness: Float, fallback: @Composable () -> Unit) {
    var failed by remember(scene, quality) { mutableStateOf(false) }
    if (failed) {
        fallback()
        return
    }
    // Keyed so a scene or quality change rebuilds the GL program instead of reusing the old view.
    androidx.compose.runtime.key(scene, quality) { AndroidView(
        factory = { context -> ShaderSceneView(context, scene, quality) { failed = true } },
        update = { it.brightness = brightness },
        modifier = Modifier.fillMaxSize(),
    ) }
}

private class ShaderSceneView(
    context: Context,
    private val scene: ShaderScene,
    private val quality: ShaderQuality,
    private val onFailure: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    @Volatile var brightness: Float = 1f
    private var renderer: SceneRenderer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        surfaceTextureListener = this
    }

    private fun bufferSize(width: Int, height: Int): Pair<Int, Int> {
        val target = (scene.renderWidth * quality.scale).coerceAtMost(quality.maxWidth.toFloat())
        val scale = (target / width.coerceAtLeast(1)).coerceAtMost(1f)
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val (w, h) = bufferSize(width, height)
        surface.setDefaultBufferSize(w, h)
        renderer = SceneRenderer(surface, scene, quality, w, h, { brightness }) {
            mainHandler.post(onFailure)
        }.also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val (w, h) = bufferSize(width, height)
        surface.setDefaultBufferSize(w, h)
        renderer?.resize(w, h)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderer?.finish()
        renderer = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}

private class SceneRenderer(
    private val surfaceTexture: SurfaceTexture,
    private val scene: ShaderScene,
    private val quality: ShaderQuality,
    @Volatile private var width: Int,
    @Volatile private var height: Int,
    private val brightness: () -> Float,
    private val onFailure: () -> Unit,
) : Thread("ScreensaverGL") {
    @Volatile private var running = true

    fun resize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun finish() {
        running = false
        interrupt()
        // The SurfaceTexture is released after this returns; the EGL surface must be gone first.
        join(1000)
    }

    override fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
            val config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
            renderLoop(display, surface)
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            Log.w(TAG, "Shader screensaver ${scene.key} unavailable: ${error.message}")
            if (running) onFailure()
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun renderLoop(display: EGLDisplay, surface: EGLSurface) {
        val program = buildProgram(VERTEX_SHADER, "#define OCTAVES ${quality.octaves}\n" + FRAGMENT_HEADER + scene.body)
        GLES20.glUseProgram(program)
        val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).also { it.position(0) }
        val position = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        val resolution = GLES20.glGetUniformLocation(program, "uRes")
        val time = GLES20.glGetUniformLocation(program, "uTime")
        val bright = GLES20.glGetUniformLocation(program, "uBright")
        // Random start so every activation looks different; wrapped to keep float precision.
        val offset = Random.nextDouble(0.0, 600.0)
        val start = System.nanoTime()
        while (running) {
            val frameStart = System.nanoTime()
            val w = width
            val h = height
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUniform2f(resolution, w.toFloat(), h.toFloat())
            GLES20.glUniform1f(time, ((offset + (frameStart - start) / 1e9) % TIME_WRAP_SECONDS).toFloat())
            GLES20.glUniform1f(bright, brightness())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (!EGL14.eglSwapBuffers(display, surface)) {
                check(!running) { "eglSwapBuffers 0x${Integer.toHexString(EGL14.eglGetError())}" }
                break
            }
            // Slow ambient motion: below high quality, 24-30 fps is plenty and saves GPU load.
            val sleepMs = quality.frameMs - (System.nanoTime() - frameStart) / 1_000_000L
            if (sleepMs > 0) sleep(sleepMs)
        }
        GLES20.glDeleteProgram(program)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "compile: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertexSource))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private companion object {
        const val TAG = "ShaderScreensaver"
        const val TIME_WRAP_SECONDS = 7200.0
    }
}

private const val VERTEX_SHADER = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

private const val FRAGMENT_HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;
uniform float uTime;
uniform float uBright;
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    for (int i = 0; i < OCTAVES; i++) { v += a * noise(p); p = m * p; a *= 0.5; }
    return v;
}
vec4 finish(vec3 col) {
    // Dither against banding in the dark gradients, then apply the dim setting.
    col += (hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0;
    return vec4(clamp(col * uBright, 0.0, 1.0), 1.0);
}
"""

/** Northern lights over a starry sky and a dark mountain ridge. */
private const val AURORA_SHADER = """
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = vec2(uv.x * aspect, uv.y);
    float t = uTime;
    vec3 col = mix(vec3(0.005, 0.010, 0.030), vec3(0.015, 0.035, 0.080), uv.y);

    vec2 cell = floor(p * 70.0);
    vec2 f = fract(p * 70.0) - 0.5;
    float h = hash(cell);
    if (h > 0.975) {
        vec2 o = vec2(hash(cell + 1.7), hash(cell + 3.1)) - 0.5;
        float d = length(f - o * 0.6);
        float twinkle = 0.55 + 0.45 * sin(t * (0.6 + h * 2.0) + h * 40.0);
        col += vec3(0.75, 0.82, 1.0) * smoothstep(0.12, 0.0, d) * twinkle * (0.25 + 0.75 * uv.y);
    }

    vec3 aurora = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float x = p.x * (0.8 + 0.2 * fi) + fi * 2.3;
        float c = 0.40 + fi * 0.10 + 0.32 * (fbm(vec2(x * 0.55 + t * 0.012, fi * 4.1 + t * 0.008)) - 0.5);
        float d = uv.y - c;
        float body = d > 0.0 ? exp(-d * (4.5 + fi * 2.0)) : exp(d * 38.0);
        float rays = 0.45 + 0.55 * noise(vec2(x * 22.0 + t * 0.06, t * 0.04 + fi * 7.0));
        float pulse = 0.65 + 0.35 * sin(t * 0.05 + fi * 2.1 + x * 1.3);
        float intensity = body * rays * pulse * (0.60 - fi * 0.15);
        vec3 low = mix(vec3(0.10, 0.95, 0.55), vec3(0.10, 0.70, 0.95), fi * 0.45);
        vec3 high = vec3(0.55, 0.22, 0.85);
        aurora += intensity * mix(low, high, smoothstep(0.0, 0.32, max(d, 0.0)));
    }
    col += aurora * 0.85;

    float ridge = 0.10 + 0.07 * fbm(vec2(p.x * 1.8, 3.0)) + 0.02 * noise(vec2(p.x * 9.0, 1.0));
    float land = smoothstep(ridge + 0.002, ridge - 0.002, uv.y);
    vec3 ground = vec3(0.004, 0.007, 0.014) + aurora * 0.04 * (1.0 - uv.y / max(ridge, 0.01));
    col = mix(col, ground, land);
    gl_FragColor = finish(col);
}
"""

/** Slow domain-warped colour fluid, a calmer take on the classic RSXS "Plasma". */
private const val FLUID_SHADER = """
void main() {
    vec2 p = (gl_FragCoord.xy - 0.5 * uRes) / uRes.y * 1.7;
    float t = uTime * 0.025;
    vec2 q = vec2(fbm(p + vec2(0.0, t)), fbm(p + vec2(5.2, 1.3) - t));
    vec2 r = vec2(fbm(p + 3.0 * q + vec2(1.7, 9.2) + t * 1.3), fbm(p + 3.0 * q + vec2(8.3, 2.8) - t * 0.9));
    float f = fbm(p + 3.0 * r);
    vec3 col = mix(vec3(0.015, 0.025, 0.080), vec3(0.06, 0.22, 0.42), clamp(f * f * 2.2, 0.0, 1.0));
    col = mix(col, vec3(0.38, 0.10, 0.42), clamp(length(q) * 0.75 - 0.25, 0.0, 1.0) * 0.75);
    col = mix(col, vec3(0.95, 0.52, 0.32), clamp(r.x * r.x * 1.6 - 0.35, 0.0, 1.0) * 0.45);
    col *= 0.30 + 0.95 * f;
    vec2 uv = gl_FragCoord.xy / uRes;
    col *= 0.55 + 0.45 * smoothstep(1.05, 0.25, length(uv - 0.5) * 1.4);
    gl_FragColor = finish(col);
}
"""

/** Gentle flight through parallax star layers with a faint nebula, after Kodi's "Starfield". */
private const val STARFIELD_SHADER = """
vec3 starLayer(vec2 p, float seed) {
    vec2 id = floor(p);
    vec2 gv = fract(p) - 0.5;
    float h = hash(id + seed);
    vec2 offset = vec2(h, fract(h * 34.7)) - 0.5;
    float d = length(gv - offset * 0.6);
    float size = fract(h * 97.3);
    float glow = 0.012 * (0.4 + size) / max(d, 0.004);
    glow *= smoothstep(0.45, 0.15, d);
    vec3 tint = mix(vec3(1.0, 0.78, 0.60), vec3(0.65, 0.80, 1.0), fract(h * 13.1));
    return tint * glow * step(0.35, size);
}
void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / uRes.y;
    float t = uTime;
    float n = fbm(uv * 1.4 + vec2(t * 0.004, -t * 0.003));
    float n2 = fbm(uv * 2.6 - vec2(t * 0.003, 4.0));
    vec3 col = vec3(0.006, 0.008, 0.020);
    col += vec3(0.10, 0.04, 0.18) * smoothstep(0.45, 0.85, n) * 0.55;
    col += vec3(0.02, 0.10, 0.18) * smoothstep(0.50, 0.90, n2) * 0.50;
    float rotation = t * 0.004;
    mat2 rot = mat2(cos(rotation), -sin(rotation), sin(rotation), cos(rotation));
    vec2 ruv = rot * uv;
    for (int i = 0; i < 4; i++) {
        float depth = fract(float(i) * 0.25 + t * 0.012);
        float scale = mix(18.0, 0.8, depth);
        float fade = depth * smoothstep(1.0, 0.85, depth);
        col += starLayer(ruv * scale + float(i) * 453.2, float(i)) * fade;
    }
    gl_FragColor = finish(col);
}
"""

/** Flowing luminous silk lines on a deep gradient. */
private const val SILK_SHADER = """
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    float x = uv.x * aspect;
    float t = uTime;
    vec3 col = mix(vec3(0.010, 0.012, 0.035), vec3(0.035, 0.018, 0.060), uv.y + 0.2 * sin(t * 0.03));
    for (int i = 0; i < 9; i++) {
        float fi = float(i);
        float y = 0.5
            + 0.16 * sin(x * (1.1 + fi * 0.06) + t * 0.11 + fi * 0.45)
            + 0.07 * sin(x * 2.4 - t * 0.08 + fi * 1.2)
            + 0.03 * sin(x * 4.3 + t * 0.05 - fi * 0.7)
            + (fi - 4.0) * 0.018;
        float d = abs(uv.y - y);
        vec3 tint = 0.5 + 0.5 * cos(6.2831 * (fi * 0.035 + vec3(0.55, 0.68, 0.88) + t * 0.006));
        float core = exp(-d * d * 9000.0) * 0.45;
        float halo = exp(-d * 16.0) * 0.035;
        col += tint * (core + halo) * (0.6 + 0.4 * sin(x * 0.9 + t * 0.07 + fi));
    }
    col *= 0.70 + 0.30 * smoothstep(1.0, 0.2, abs(uv.x - 0.5) * 2.0);
    gl_FragColor = finish(col);
}
"""

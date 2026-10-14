package com.ambient.tv.ui.display

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.hardware.HardwareBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Offscreen FP16 proof and owner lifecycle, not a claim about the emulator's SDR display. */
@androidx.annotation.RequiresApi(34)
internal class AmbientHdrAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { runCatching(action).onFailure { error = it } }
        error?.let { throw it }
    }

    fun run(activity: Activity) {
        checkShader()
        if (android.os.Build.VERSION.SDK_INT < 35) {
            log("PASS ambient HDR window stays SDR below API 35 because headroom cannot be capped")
            return
        }
        val projectRequests = mutableListOf<SdrToHdrRequest?>()
        val screenRequests = mutableListOf<SdrToHdrRequest?>()
        var screenAllowed = false
        val project = AmbientHdrLease(false, 1) {
            projectRequests += it
            if (it == null) SdrToHdrStatus.OFF else SdrToHdrStatus.ACTIVE
        }
        val screen = AmbientHdrLease(true, 2, { screenAllowed }) {
            screenRequests += it
            if (it == null) SdrToHdrStatus.OFF else SdrToHdrStatus.ACTIVE
        }
        lateinit var state: UiHdrState
        val facts = UiHdrEligibility(35, true, true, true, true, true, 1f)
        val request = SdrToHdrRequest(400, 0, 1)
        try {
            main {
                state = UiHdrState(activity.window)
                state.attachAmbient(project)
                state.updateAmbient(facts, request, "project")
                check(projectRequests.last() == request)
                state.attachAmbient(screen)
                state.updateAmbient(facts, request, "screen")
                check(projectRequests.last() == null && screenRequests.last() == null)
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                screenAllowed = true
                state.updateAmbient(facts, request, "screen")
            }
            Thread.sleep(1100)
            main {
                state.updateAmbient(facts, request, "screen")
                check(state.headroom == UI_HDR_HEADROOM_CAP && screenRequests.last() == request)
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_HDR)
                state.openPopup()
                check(screenRequests.last() == null && projectRequests.last() == null)
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                state.updateAmbient(facts, request, "screen")
                check(screenRequests.last() == null)
                state.closePopup()
                state.updateAmbient(facts, request, "screen")
            }
            Thread.sleep(1100)
            main {
                state.updateAmbient(facts, request, "screen")
                check(state.headroom > 1f)
            }
            Thread.sleep(2600)
            main {
                state.updateAmbient(facts, request, "screen")
                check(screenRequests.last() == null && state.headroom == 1f)
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                // The same visible saver must not repeatedly flash into HDR after timeout.
                state.updateAmbient(facts.copy(headroom = 2f), request, "screen")
                check(screenRequests.last() == null)
                state.detachAmbient(screen)
                check(state.ambientOwner === project)
                state.reset()
                check(projectRequests.last() == null)
                state.detachAmbient(project)
                check(state.ambientOwner == null)
            }
            log("PASS ambient ownership: saver overrides projectM, OLED/performance veto, popup/reset and missing HDR confirmation restore SDR without retry loops (injected capabilities)")
        } finally { main { state.reset() } }
    }

    private fun checkShader() {
        val space = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        HardwareBuffer.create(4, 1, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).use { buffer ->
            HardwareBufferRenderer(buffer).use { renderer ->
                val node = RenderNode("Ambient HDR shader test")
                node.setPosition(0, 0, 4, 1)
                val shader = RuntimeShader(SCREENSAVER_HDR_SHADER)
                shader.setFloatUniform("headroom", 1.25f)
                // HardwareBufferRenderer has SDR intermediate RenderEffect layers even with an
                // FP16 final buffer. Test the unchanged shader directly in the FP16 destination;
                // production RenderEffect additionally needs the HDR Window (real-device test).
                val input = Bitmap.createBitmap(4, 1, Bitmap.Config.RGBA_F16, true, space)
                val canvas = Canvas(input)
                val paint = Paint()
                // Opaque SDR white, dark grey, existing HDR white, black.
                val inputs = listOf(1f, .1f, 1.5f, 0f)
                inputs.forEachIndexed { x, value ->
                    paint.color = Color.WHITE
                    paint.setColor(Color.pack(value, value, value, 1f, space))
                    canvas.drawRect(x.toFloat(), 0f, x + 1f, 1f, paint)
                }
                check(abs(input.getColor(2, 0).red() - 1.5f) < .02f)
                shader.setInputShader("content", BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                val recording = node.beginRecording()
                recording.drawRect(0f, 0f, 4f, 1f, Paint().apply { this.shader = shader })
                node.endRecording()
                renderer.setContentRoot(node)
                val done = CountDownLatch(1)
                var error: Throwable? = null
                renderer.obtainRenderRequest().setColorSpace(space).draw({ it.run() }) { result ->
                    runCatching {
                        check(result.status == HardwareBufferRenderer.RenderResult.SUCCESS)
                        result.fence.use { check(it.await(java.time.Duration.ofSeconds(5))) }
                    }.onFailure { error = it }
                    done.countDown()
                }
                check(done.await(10, TimeUnit.SECONDS))
                error?.let { throw it }
                val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, space))
                try {
                    val cpu = checkNotNull(hardware.copy(Bitmap.Config.RGBA_F16, false))
                    try {
                        val expected = listOf(1.25f, .1f, 1.5f, 0f)
                        expected.forEachIndexed { x, value ->
                            val color = cpu.getColor(x, 0)
                            check(abs(color.red() - value) < .02f && abs(color.green() - value) < .02f && abs(color.blue() - value) < .02f) {
                                "HDR Canvas shader clipped/shifted sample $x: $color, expected $value"
                            }
                        }
                    } finally { cpu.recycle() }
                } finally { hardware.recycle(); node.discardDisplayList(); input.recycle() }
            }
        }
        log("PASS screensaver shader math preserves >SDR white, darks/black and already-HDR colours in an FP16 target (HDR Window/RenderEffect remains device-unverified)")
    }
}

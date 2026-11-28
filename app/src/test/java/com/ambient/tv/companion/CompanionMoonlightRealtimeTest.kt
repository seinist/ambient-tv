package com.ambient.tv.companion

import android.app.Application
import android.os.Looper
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = Application::class)
class CompanionMoonlightRealtimeTest {
    private val inputs = mutableListOf<Pair<Int, Float>>()
    private var lastThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val sink = object : CompanionGamepadSink {
        override fun localUsedPorts() = emptySet<Int>()
        override fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String?) = Unit
        override fun clearRemotePort(port: Int, suppressHeld: Boolean) { inputs += 0 to 0f }
        override fun remoteInputDescriptions(port: Int, labels: Map<String, String>) = emptyMap<String, String>()
        override fun remoteState(port: Int, buttons: Int, leftX: Float, leftY: Float,
                                 rightX: Float, rightY: Float, leftTrigger: Float?, rightTrigger: Float?) {
            lastThread = Thread.currentThread()
            inputs += buttons to leftX
        }
    }

    @Before fun attach() {
        RetroInputSettings.attach(emptyMap()) {}
        CompanionGamepadSession.attachMoonlight(sink, "Moonlight", onMenu = {})
        CompanionGamepadSession.setMoonlightInteraction(sink, CompanionGamepadInteraction.PLAYING)
        assertEquals(0, CompanionGamepadSession.claim("socket", "fingerprint"))
    }

    @After fun detach() {
        CompanionGamepadSession.detachMoonlight(sink)
        worker.shutdownNow()
        RetroInputSettings.attach(emptyMap()) {}
    }

    private fun packet(x: Float = 0f): CompanionGamepadState {
        val info = requireNotNull(CompanionGamepadSession.info("socket"))
        return CompanionGamepadState(leftX = x, sessionId = info.sessionId,
            contextRevision = info.contextRevision)
    }

    private fun fast(packet: CompanionGamepadState, authorized: Boolean = true) =
        worker.submit<CompanionGamepadSession.MoonlightRealtimeReply?> {
            CompanionGamepadSession.tryMoonlightContinuousInput("socket", packet) { authorized }
        }.get(3, TimeUnit.SECONDS)

    @Test fun continuousMotionRunsOnWorkerButEdgesAndNewContextUseControlPlane() {
        val first = packet()
        assertNull(fast(first))
        assertTrue(CompanionGamepadSession.input("socket", first))
        val result = requireNotNull(fast(first.copy(leftX = .5f)))
        assertTrue(result.accepted)
        assertEquals(0, result.port)
        assertEquals(0 to .5f, inputs.last())
        assertNotEquals(Thread.currentThread(), lastThread)
        assertNull(fast(first.copy(buttons = RetroPadButton.A.mask)))
        assertNull(fast(first.copy(contextRevision = first.contextRevision!! + 1)))
        assertNull(fast(first.copy(leftX = Float.NaN)))
        assertNull(fast(first.copy(leftX = .8f), authorized = false))
        assertEquals(0 to .5f, inputs.last())
    }

    @Test fun changedPortPermissionOrMenuRejectsFastInput() {
        val original = packet()
        assertTrue(CompanionGamepadSession.input("socket", original))
        RetroInputSettings.update { it.withPort(companionControllerDescriptor("fingerprint"), RetroPorts.OFF) }
        assertNull(fast(original.copy(leftX = .7f)))
        assertFalse(CompanionGamepadSession.input("socket", original.copy(leftX = .7f)))
        assertEquals(-1, CompanionGamepadSession.port("socket"))
        RetroInputSettings.attach(emptyMap()) {}
        assertEquals(0, CompanionGamepadSession.claim("socket", "fingerprint"))
        val fresh = packet()
        assertTrue(CompanionGamepadSession.input("socket", fresh))
        assertNotNull(fast(fresh.copy(leftX = .7f)))
        CompanionGamepadSession.setMoonlightInteraction(sink, CompanionGamepadInteraction.MENU)
        assertNull(fast(fresh.copy(leftX = 1f)))
        assertEquals(0 to 0f, inputs.last())
    }

    @Test fun workerInputRenewsDeadmanAndOldTicketCannotReplayAfterRelease() {
        val first = packet(.5f)
        assertTrue(CompanionGamepadSession.input("socket", first))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertNotNull(fast(first.copy(leftX = .9f)))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertEquals(0 to .9f, inputs.last())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(901, TimeUnit.MILLISECONDS)
        assertEquals(0 to 0f, inputs.last())
        CompanionGamepadSession.release("socket")
        assertNull(fast(first))
    }
}

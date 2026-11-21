package com.ambient.tv.companion

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The mailbox is a pure coroutine transport: it doesn't need a network or Android device. */
class CompanionLiveInputQueueTest {
    private fun pad(mask: Int, x: Float = 0f) =
        CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(buttons = mask, leftX = x))
    private fun move(x: Float, y: Float = 0f) =
        CompanionFrame(type = "input", input = CompanionInput("move", x = x, y = y))

    @Test fun hundredAnalogFramesBecomeOneFreshSnapshot() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(16)
        repeat(100) { i -> assertTrue(q.offerGamepad(pad(0, i / 100f), critical = false)) }
        assertEquals(0.99f, q.poll(normal)!!.gamepadState!!.leftX, 0.0001f)
        assertNull(q.poll(normal))
        q.close()
    }

    @Test fun criticalPressAndReleaseSurviveContinuousCongestionInOrder() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        normal.trySend(CompanionFrame(type = "status"))
        repeat(200) { i -> q.offerGamepad(pad(0, i / 200f), critical = false) }
        assertTrue(q.offerGamepad(pad(1), critical = true))
        repeat(200) { i -> q.offerGamepad(pad(1, i / 200f), critical = false) }
        assertTrue(q.offerGamepad(pad(0), critical = true))
        assertEquals(1, q.poll(normal)!!.gamepadState!!.buttons)
        assertEquals(0, q.poll(normal)!!.gamepadState!!.buttons)
        // Ordinary Companion work still goes through its existing FIFO channel.
        assertEquals("status", q.poll(normal)?.type)
        assertNull(q.poll(normal))
        q.close()
    }

    @Test fun remoteMovementIsAccumulatedAndFlushedBeforeClick() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        repeat(4) { assertTrue(q.offerRemote(move(0.1f, -0.05f))) }
        val click = CompanionFrame(type = "input", input = CompanionInput("click"))
        assertTrue(q.offerRemote(click))
        val motion = q.poll(normal)!!
        assertEquals("move", motion.input!!.action)
        assertEquals(0.4f, motion.input!!.x, 0.0001f)
        assertEquals(-0.2f, motion.input!!.y, 0.0001f)
        assertEquals(click, q.poll(normal))
        assertNull(q.poll(normal))
        q.close()
    }

    @Test fun moveOverflowKeepsBothSegmentsInsteadOfDroppingTravel() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        q.offerRemote(move(0.8f))
        q.offerRemote(move(0.7f))
        assertEquals(0.8f, q.poll(normal)!!.input!!.x, 0f)
        assertEquals(0.7f, q.poll(normal)!!.input!!.x, 0f)
        q.close()
    }

    @Test fun criticalMouseClickPreservesPendingRelativeMovement() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(4)
        val drag = CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(
            pointer = CompanionPointerState(kind = "mouse", dx = 0.4f, dy = -0.2f)))
        val click = CompanionFrame(type = "gamepad_input", gamepadState = CompanionGamepadState(
            pointer = CompanionPointerState(kind = "mouse", buttons = 1)))
        assertTrue(q.offerGamepad(drag, critical = false))
        assertTrue(q.offerGamepad(click, critical = true))
        assertEquals(drag, q.poll(normal))
        assertEquals(click, q.poll(normal))
        assertNull(q.poll(normal))
        q.close()
    }

    @Test fun boundedCriticalQueueFailsClosedRatherThanSilentlyDroppingRelease() {
        var overflows = 0
        val q = CompanionLiveInputQueue(onOverflow = { overflows++ }, edgeCapacity = 2)
        assertTrue(q.offerGamepad(pad(1), critical = true))
        assertTrue(q.offerGamepad(pad(0), critical = true))
        assertFalse(q.offerGamepad(CompanionFrame(type = "gamepad_release"), critical = true))
        assertEquals(1, overflows)
        assertFalse(q.offerGamepad(pad(1), critical = true))
        assertEquals(1, overflows)
        q.close()
    }

    @Test fun wakeupCanSelectANewInputBeforeOutstandingReplyArrives() = runBlocking {
        val q = CompanionLiveInputQueue()
        val ordinary = Channel<CompanionFrame>(1)
        val replies = Channel<CompanionFrame>(1)
        val incoming = launch {
            val (value, isReply) = q.receiveOrReply(ordinary, replies)
            assertFalse(isReply)
            assertEquals(1, value.gamepadState!!.buttons)
        }
        q.offerGamepad(pad(1), critical = true)
        incoming.join()
        replies.trySend(CompanionFrame(type = "state", sequence = 7))
        val (state, isReply) = q.receiveOrReply(ordinary, replies)
        assertTrue(isReply)
        assertEquals(7L, state.sequence)
        q.close()
    }
}

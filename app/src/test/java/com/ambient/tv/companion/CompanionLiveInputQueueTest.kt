package com.ambient.tv.companion

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        assertEquals(0.4f, motion.input.x, 0.0001f)
        assertEquals(-0.2f, motion.input.y, 0.0001f)
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

    @Test fun claimRunsBeforeFirstPadPacketEvenWhenMediaStatusIsQueued() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(2)
        normal.trySend(CompanionFrame(type = "status"))
        assertTrue(q.offerGamepad(CompanionFrame(type = "gamepad_claim"), critical = true))
        assertTrue(q.offerGamepad(pad(1), critical = true))
        assertEquals("gamepad_claim", q.poll(normal)!!.type)
        assertEquals(1, q.poll(normal)!!.gamepadState!!.buttons)
        assertEquals("status", q.poll(normal)!!.type)
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
        assertFalse(q.offerGamepad(pad(0, 0.8f), critical = false))
        assertFalse(q.offerRemote(move(0.5f)))
        assertEquals(1, overflows)
        q.close()
        assertFalse(q.offerGamepad(pad(0), critical = false))
        assertFalse(q.offerRemote(move(0.1f)))
    }

    @Test fun producerCannotInsertAnEdgeBetweenEmptyCheckAndMotionDequeue() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        val started = CountDownLatch(1)
        val received = AtomicReference<CompanionFrame?>()
        val consumer = thread(start = false, isDaemon = true) {
            started.countDown()
            received.set(q.poll(normal))
        }
        synchronized(q) {
            consumer.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (consumer.state != Thread.State.BLOCKED && System.nanoTime() < deadline)
                Thread.yield()
            assertEquals(Thread.State.BLOCKED, consumer.state)
            assertTrue(q.offerGamepad(pad(1), critical = true))
            assertTrue(q.offerGamepad(pad(1, 0.5f), critical = false))
        }
        consumer.join(2000)
        assertFalse(consumer.isAlive)
        assertEquals(pad(1), received.get())
        assertEquals(pad(1, 0.5f), q.poll(normal))
        q.close()
    }

    @Test fun suspendedReceiverRechecksEdgesBeforeTakingMotion() = runBlocking {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        var first: CompanionFrame? = null
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            first = q.receive(normal, 1000)
        }
        q.offerGamepad(pad(0, 0.25f), critical = false)
        q.offerGamepad(pad(1), critical = true)
        q.offerGamepad(pad(1, 0.75f), critical = false)
        consumer.join()
        assertEquals(pad(1), first)
        assertEquals(pad(1, 0.75f), q.poll(normal))
        q.close()
    }

    @Test fun remoteReleaseFollowsMotionAndDiscreteKeys() {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        val key = CompanionFrame(type = "input", input = CompanionInput("click"))
        val release = CompanionFrame(type = "input_release")
        assertTrue(q.offerRemote(key))
        assertTrue(q.offerRemote(move(0.4f)))
        assertTrue(q.offerRemoteRelease(release))
        assertEquals(key, q.poll(normal))
        assertEquals(move(0.4f), q.poll(normal))
        assertEquals(release, q.poll(normal))
        assertNull(q.poll(normal))
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun normalCommandAtReceiveDeadlineIsReturnedOrRemainsQueuedExactlyOnce() = runTest {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        val command = CompanionFrame(type = "start")
        var received: CompanionFrame? = null
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            received = q.receive(normal, 100)
        }
        advanceTimeBy(100) // The receive and the timer are both eligible at this instant.
        assertTrue(normal.trySend(command).isSuccess)
        runCurrent()
        consumer.join()
        assertEquals(command, received ?: q.poll(normal))
        assertNull(q.poll(normal))
        q.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun expiredReceiveDoesNotConsumeLaterNormalCommand() = runTest {
        val q = CompanionLiveInputQueue()
        val normal = Channel<CompanionFrame>(1)
        val command = CompanionFrame(type = "status")
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            assertNull(q.receive(normal, 100))
        }
        advanceTimeBy(100)
        runCurrent()
        consumer.join()
        assertTrue(normal.trySend(command).isSuccess)
        assertEquals(command, q.poll(normal))
        assertNull(q.poll(normal))
        q.close()
    }

}

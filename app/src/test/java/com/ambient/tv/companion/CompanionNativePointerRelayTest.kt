package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionNativePointerRelayTest {
    private data class NativeCall(val port: Int, val kind: Int, val index: Int,
                                  val x: Float, val y: Float, val flags: Int)

    @Test fun draggingOneStylusOnlyUpdatesOneNativePointer() {
        val calls = mutableListOf<NativeCall>()
        val relay = CompanionNativePointerRelay { p, k, i, x, y, f ->
            calls += NativeCall(p, k, i, x, y, f)
        }
        val initial = CompanionPointerState(contacts = listOf(
            CompanionPointerContact(0, 0.1f, 0.2f), CompanionPointerContact(1, 0.6f, 0.7f)))
        relay.update(0, initial)
        assertEquals(2, calls.size)
        calls.clear()
        relay.update(0, initial)
        assertTrue(calls.isEmpty())
        relay.update(0, initial.copy(contacts = listOf(
            CompanionPointerContact(0, 0.3f, 0.2f), initial.contacts[1])))
        assertEquals(listOf(NativeCall(0, 0, 0, 0.3f, 0.2f, 1)), calls)
    }

    @Test fun liftingFirstFingerDoesNotRenumberSecond() {
        val calls = mutableListOf<NativeCall>()
        val relay = CompanionNativePointerRelay { p, k, i, x, y, f ->
            calls += NativeCall(p, k, i, x, y, f)
        }
        val first = CompanionPointerContact(0, 0.2f, 0.4f)
        val second = CompanionPointerContact(1, 0.6f, 0.8f)
        relay.update(2, CompanionPointerState(contacts = listOf(first, second)))
        calls.clear()
        relay.update(2, CompanionPointerState(contacts = listOf(second)))
        assertEquals(listOf(NativeCall(2, 0, 0, 0.2f, 0.4f, 0)), calls)
        relay.clear()
        assertEquals(NativeCall(2, 0, 1, 0.6f, 0.8f, 0), calls.last())
    }

    @Test fun mouseMovementsAreEventsAndModeSwitchReleasesButtons() {
        val calls = mutableListOf<NativeCall>()
        val relay = CompanionNativePointerRelay { p, k, i, x, y, f ->
            calls += NativeCall(p, k, i, x, y, f)
        }
        val mouse = CompanionPointerState(kind = "mouse", dx = 0.1f, dy = -0.1f, buttons = 1)
        relay.update(0, mouse)
        relay.update(0, mouse)
        assertEquals(2, calls.size) // identical relative moves are distinct
        relay.update(0, CompanionPointerState(contacts = listOf(CompanionPointerContact(0, 0.5f, 0.6f))))
        assertEquals(NativeCall(0, 1, 0, 0f, 0f, 0), calls[2])
        assertEquals(NativeCall(0, 0, 0, 0.5f, 0.6f, 1), calls[3])
    }
}

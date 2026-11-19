package com.ambient.tv.ui.keyboard

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KeyBindingsTest {
    private var saved: Map<String, String> = emptyMap()
    private val performed = mutableListOf<KeyAction>()
    private val replayed = mutableListOf<Int>()
    private val dispatcher = KeyBindingDispatcher()

    @Before fun setUp() {
        KeyBindings.attach(emptyMap()) { saved = it }
        KeyBindings.playerForeground = false
        KeyBindings.capture = null
    }

    @After fun tearDown() {
        KeyBindings.attach(emptyMap()) {}
        KeyBindings.playerForeground = false
    }

    private fun event(action: Int, code: Int, downTime: Long = 0L, eventTime: Long = downTime, repeat: Int = 0) =
        KeyEvent(downTime, eventTime, action, code, repeat)

    private fun send(event: KeyEvent): Boolean? =
        dispatcher.handle(event, { performed += it }) { replayed += it.action }

    @Test fun bindingsRoundTripAndSkipReservedOrUnknownEntries() {
        KeyBindings.bind(KeyAction.REFRESH, KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = false))
        KeyBindings.bind(KeyAction.SUBTITLES, KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = true))
        assertEquals(mapOf("refresh" to "${KeyEvent.KEYCODE_PROG_RED}:0", "subtitles" to "${KeyEvent.KEYCODE_PROG_RED}:1"), saved)
        val decoded = KeyBindings.decode(saved + ("search" to "${KeyEvent.KEYCODE_DPAD_CENTER}:0") + ("gone" to "1:0"))
        assertEquals(KeyBindings.bindings, decoded)
    }

    @Test fun reservedKeysCannotBeBound() {
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP).forEach { assertFalse(isBindableKey(it)) }
        assertTrue(isBindableKey(KeyEvent.KEYCODE_PROG_BLUE))
        assertTrue(isBindableKey(KeyEvent.KEYCODE_F5))
    }

    @Test fun sameKeyInSameScopeReplacesTheOldAction() {
        val red = KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = false)
        assertNull(KeyBindings.bind(KeyAction.REFRESH, red))
        assertEquals(KeyAction.REFRESH, KeyBindings.bind(KeyAction.SEARCH, red))
        assertEquals(mapOf(KeyAction.SEARCH to red), KeyBindings.bindings)
        // Another scope keeps its own use of the key.
        assertNull(KeyBindings.bind(KeyAction.SUBTITLES, red))
        assertEquals(2, KeyBindings.bindings.size)
    }

    @Test fun playerBindingsWinOnlyWhileAPlayerIsInFront() {
        val red = KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = false)
        KeyBindings.bind(KeyAction.REFRESH, red)
        KeyBindings.bind(KeyAction.SUBTITLES, red)
        assertEquals(KeyAction.REFRESH, KeyBindings.actionFor(red.keyCode, long = false, player = false))
        assertEquals(KeyAction.SUBTITLES, KeyBindings.actionFor(red.keyCode, long = false, player = true))
        KeyBindings.clear(KeyAction.SUBTITLES)
        assertEquals(KeyAction.REFRESH, KeyBindings.actionFor(red.keyCode, long = false, player = true))
    }

    @Test fun shortBindingActsOnDownAndConsumesTheWholePress() {
        KeyBindings.bind(KeyAction.REFRESH, KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = false))
        assertEquals(true, send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED)))
        assertEquals(true, send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 0, 700, repeat = 1)))
        assertEquals(true, send(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED, 0, 800)))
        assertEquals(listOf(KeyAction.REFRESH), performed)
        assertNull(send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_GREEN)))
    }

    @Test fun heldKeyTriggersItsLongBindingOnceWhileRepeating() {
        KeyBindings.bind(KeyAction.REFRESH, KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = false))
        KeyBindings.bind(KeyAction.SEARCH, KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = true))
        send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED))
        send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 0, 300, repeat = 1))
        assertTrue(performed.isEmpty())
        send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 0, 650, repeat = 2))
        send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 0, 700, repeat = 3))
        send(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED, 0, 900))
        assertEquals(listOf(KeyAction.SEARCH), performed)
        // A brief press of the same key is the short binding.
        send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, 1_000))
        send(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED, 1_000, 1_100))
        assertEquals(listOf(KeyAction.SEARCH, KeyAction.REFRESH), performed)
    }

    @Test fun remoteWithoutRepeatDecidesByHoldTime() {
        KeyBindings.bind(KeyAction.SEARCH, KeyBinding(KeyEvent.KEYCODE_PROG_BLUE, long = true))
        send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_BLUE))
        send(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_BLUE, 0, 700))
        assertEquals(listOf(KeyAction.SEARCH), performed)
    }

    @Test fun briefPressOfALongOnlyKeyIsReplayedUnchanged() {
        KeyBindings.bind(KeyAction.SEARCH, KeyBinding(KeyEvent.KEYCODE_MENU, long = true))
        assertEquals(true, send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)))
        assertEquals(true, send(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0, 100)))
        assertTrue(performed.isEmpty())
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), replayed)
    }

    @Test fun captureSeesKeysFirstAndCanPassThemOn() {
        KeyBindings.bind(KeyAction.REFRESH, KeyBinding(KeyEvent.KEYCODE_PROG_RED, long = false))
        val seen = mutableListOf<Int>()
        KeyBindings.capture = { seen += it.keyCode; it.keyCode == KeyEvent.KEYCODE_PROG_RED }
        assertEquals(true, send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED)))
        assertNull(send(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
        assertTrue(performed.isEmpty())
        assertEquals(listOf(KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_DPAD_DOWN), seen)
    }

    @Test fun latestRegisteredHandlerWinsThenFallback() {
        val calls = mutableListOf<String>()
        KeyActionHandlers.fallback(KeyAction.SETTINGS) { calls += "fallback"; true }
        val first = KeyActionHandlers.register(KeyAction.SETTINGS) { calls += "first"; true }
        val second = KeyActionHandlers.register(KeyAction.SETTINGS) { calls += "second"; false }
        assertTrue(KeyActionHandlers.perform(KeyAction.SETTINGS))
        KeyActionHandlers.unregister(first)
        KeyActionHandlers.unregister(second)
        assertTrue(KeyActionHandlers.perform(KeyAction.SETTINGS))
        assertEquals(listOf("second", "first", "fallback"), calls)
    }
}

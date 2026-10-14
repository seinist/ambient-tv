package com.ambient.tv.ui.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodiKeyboardShortcutsTest {
    @Test
    fun mapsKodiCorePlaybackKeys() {
        assertEquals(KodiKeyboardAction.TOGGLE_PLAYBACK, kodiKeyboardAction(KeyEvent.KEYCODE_SPACE))
        assertEquals(KodiKeyboardAction.PLAY, kodiKeyboardAction(KeyEvent.KEYCODE_P))
        assertEquals(KodiKeyboardAction.STOP, kodiKeyboardAction(KeyEvent.KEYCODE_X))
        assertEquals(KodiKeyboardAction.SEEK_FORWARD, kodiKeyboardAction(KeyEvent.KEYCODE_F))
        assertEquals(KodiKeyboardAction.SEEK_FORWARD, kodiKeyboardAction(KeyEvent.KEYCODE_PERIOD))
        assertEquals(KodiKeyboardAction.SEEK_BACKWARD, kodiKeyboardAction(KeyEvent.KEYCODE_R))
        assertEquals(KodiKeyboardAction.SEEK_BACKWARD, kodiKeyboardAction(KeyEvent.KEYCODE_COMMA))
    }

    @Test
    fun mapsKodiNavigationAndVolumeKeys() {
        assertEquals(KodiKeyboardAction.BACK, kodiKeyboardAction(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(KodiKeyboardAction.BACK, kodiKeyboardAction(KeyEvent.KEYCODE_DEL))
        assertEquals(KodiKeyboardAction.VOLUME_DOWN, kodiKeyboardAction(KeyEvent.KEYCODE_MINUS))
        assertEquals(KodiKeyboardAction.VOLUME_UP, kodiKeyboardAction(KeyEvent.KEYCODE_EQUALS))
        assertEquals(KodiKeyboardAction.VOLUME_UP, kodiKeyboardAction(KeyEvent.KEYCODE_PLUS, shiftPressed = true))
        assertEquals(KodiKeyboardAction.TOGGLE_MUTE, kodiKeyboardAction(KeyEvent.KEYCODE_F8))
    }

    @Test
    fun leavesModifiedKeysToEditorsAndSystemShortcuts() {
        assertNull(kodiKeyboardAction(KeyEvent.KEYCODE_V, ctrlPressed = true))
        assertNull(kodiKeyboardAction(KeyEvent.KEYCODE_P, shiftPressed = true))
        assertNull(kodiKeyboardAction(KeyEvent.KEYCODE_F, altPressed = true))
    }

    @Test
    fun onlySeekAndVolumeRepeat() {
        assertTrue(KodiKeyboardAction.SEEK_FORWARD.repeatable)
        assertTrue(KodiKeyboardAction.VOLUME_UP.repeatable)
        assertFalse(KodiKeyboardAction.TOGGLE_PLAYBACK.repeatable)
        assertFalse(KodiKeyboardAction.STOP.repeatable)
    }

    @Test
    fun seekTargetClampsToTimeline() {
        assertEquals(40_000L, kodiSeekTarget(30_000L, 120_000L, 10_000L))
        assertEquals(0L, kodiSeekTarget(3_000L, 120_000L, -10_000L))
        assertEquals(120_000L, kodiSeekTarget(115_000L, 120_000L, 10_000L))
    }
}

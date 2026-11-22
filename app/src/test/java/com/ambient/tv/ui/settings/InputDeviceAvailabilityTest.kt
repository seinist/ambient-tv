package com.ambient.tv.ui.settings

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class InputDeviceAvailabilityTest {
    @Test fun unpluggingLastControllerRemovesItsEditorAndChoosesRemote() {
        assertEquals("controller", availableInputSelection("controller", availablePlayerInputKinds(true)))
        val withoutController = availablePlayerInputKinds(false)
        assertFalse("controller" in withoutController)
        assertEquals("remote", availableInputSelection("controller", withoutController))
        assertEquals("keyboard", availableInputSelection("keyboard", withoutController))
        assertEquals("remote", availableInputSelection("remote", availablePlayerInputKinds(true)))
    }

    @Test fun anEmptyGamesDeviceListDoesNotInventAnEditableGamepad() {
        assertNull(availableInputSelection("gamepad", emptyList()))
        assertEquals("companion", availableInputSelection("gamepad", listOf("companion")))
    }

    @Test fun unpluggingSelectedControllerKeepsMappingEditsDeviceScoped() {
        assertEquals("pad-a", availableControllerDescriptor("pad-b", listOf("pad-a")))
        assertEquals("", availableControllerDescriptor("", listOf("pad-a", "pad-b")))
        assertEquals("pad-b", availableControllerDescriptor("pad-b", listOf("pad-a", "pad-b")))
        assertEquals("", availableControllerDescriptor("pad-b", emptyList()))
    }

    @Test fun gamesAndPlayerArtworkUseTheSamePhysicalKeys() {
        listOf("a", "b", "x", "y", "l1", "r1", "l2", "r2", "start", "select", "up", "down", "left", "right")
            .forEach { key -> assertEquals(key, visualKeyCode("controller", key), visualKeyCode("gamepad", key)) }
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, visualKeyCode("gamepad", "a"))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, visualKeyCode("gamepad", "left"))
    }

    @Test fun physicalKeyboardNavigationNeverBecomesSystemHomeAndDeleteKeysStayDistinct() {
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, visualKeyCode("keyboard", "Home"))
        assertEquals(KeyEvent.KEYCODE_MOVE_END, visualKeyCode("keyboard", "End"))
        assertEquals(KeyEvent.KEYCODE_DEL, visualKeyCode("keyboard", "Backspace"))
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, visualKeyCode("keyboard", "Delete"))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, visualKeyCode("keyboard", "up"))
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, visualKeyCode("keyboard", "PageDown"))
        assertEquals(KeyEvent.KEYCODE_0, visualKeyCode("keyboard", "0"))
        assertEquals(KeyEvent.KEYCODE_9, visualKeyCode("keyboard", "9"))
        assertEquals(KeyEvent.KEYCODE_F12, visualKeyCode("keyboard", "F12"))
        assertNull(visualKeyCode("keyboard", "Meta"))
    }
}

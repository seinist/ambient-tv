package com.ambient.tv.ui.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GameDialogInputTest {
    private val owner = Any()
    private val handlers = mutableListOf<GameDialogInputHandler>()

    @After fun reset() {
        handlers.forEach { it.dispose() }
        GameInputRouting.detach(owner)
        GameInputRouting.transitions.removeDevice(7)
        GamepadInput.capture = null
    }

    private fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, time: Long = 10) =
        KeyEvent(time, time, action, code, 0, 0, 7, 0, 0, InputDevice.SOURCE_GAMEPAD)

    private fun input(target: (KeyEvent) -> Boolean = { true }) =
        GameDialogInputHandler(target).also { handlers += it }

    @Test fun guideAndStartStayGameLocalInANativeDialog() {
        var menus = 0
        GameInputRouting.attach(owner, GameInputMode.DIALOG, { menus++ })
        val input = input { fail("Menu key reached the dialog content"); false }
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START)) {
            assertTrue(input.key(event(code)) { fail("Controller key escaped the bridge"); false })
            assertTrue(input.key(event(code, KeyEvent.ACTION_UP)) { false })
        }
        assertEquals(2, menus)
    }

    @Test fun confirmAndBackAreDeliveredToTheDialogRootNotTheUnderlyingPlayer() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        val sent = mutableListOf<Pair<Int, Int>>()
        val input = input { sent += it.keyCode to it.action; true }
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B)) {
            input.key(event(code)) { false }
            input.key(event(code, KeyEvent.ACTION_UP)) { false }
        }
        assertEquals(listOf(
            KeyEvent.KEYCODE_DPAD_CENTER to KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER to KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_BACK to KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK to KeyEvent.ACTION_UP,
        ), sent)
    }

    @Test fun translatedConfirmPreparesNavigationBeforeDialogDispatch() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        val order = mutableListOf<String>()
        val input = GameDialogInputHandler(
            dispatchTranslatedKey = { order += "dispatch:${it.keyCode}"; true },
            prepareUiKeyEvent = { order += "prepare:${it.keyCode}" },
        ).also { handlers += it }
        assertTrue(input.key(event(KeyEvent.KEYCODE_BUTTON_A)) { false })
        assertEquals(listOf("prepare:${KeyEvent.KEYCODE_DPAD_CENTER}",
            "dispatch:${KeyEvent.KEYCODE_DPAD_CENTER}"), order)
    }

    @Test fun physicalDpadPreparesNavigationBeforeNativeDialogDispatch() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        val order = mutableListOf<String>()
        val input = GameDialogInputHandler(
            dispatchTranslatedKey = { fail("A physical D-pad key must not be translated"); false },
            prepareUiKeyEvent = { order += "prepare:${it.keyCode}" },
        ).also { handlers += it }
        assertTrue(input.key(event(KeyEvent.KEYCODE_DPAD_DOWN)) { order += "native"; true })
        assertEquals(listOf("prepare:${KeyEvent.KEYCODE_DPAD_DOWN}", "native"), order)
    }

    @Test fun controllerMenuPreparesNavigationBeforeGameMenuCallback() {
        val order = mutableListOf<String>()
        GameInputRouting.attach(owner, GameInputMode.DIALOG, { order += "menu" })
        val input = GameDialogInputHandler(
            dispatchTranslatedKey = { fail("Menu key reached the dialog content"); false },
            prepareUiKeyEvent = { order += "prepare:${it.keyCode}" },
        ).also { handlers += it }
        assertTrue(input.key(event(KeyEvent.KEYCODE_BUTTON_MODE)) { false })
        assertEquals(listOf("prepare:${KeyEvent.KEYCODE_MENU}", "menu"), order)
    }

    @Test fun capturedOrBlockedInputDoesNotPrepareNavigation() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        var prepared = 0
        val input = GameDialogInputHandler(
            dispatchTranslatedKey = { fail("Captured or blocked input reached the dialog"); false },
            prepareUiKeyEvent = { prepared++ },
        ).also { handlers += it }
        GamepadInput.capture = { true }
        assertTrue(input.key(event(KeyEvent.KEYCODE_BUTTON_A)) { false })
        assertTrue(input.key(event(KeyEvent.KEYCODE_DPAD_DOWN)) { false })
        GamepadInput.capture = null
        GameInputRouting.update(owner, GameInputMode.BLOCKED)
        assertTrue(input.key(event(KeyEvent.KEYCODE_BUTTON_A, time = 100)) { false })
        assertTrue(input.key(event(KeyEvent.KEYCODE_DPAD_DOWN, time = 100)) { false })
        assertEquals(0, prepared)
    }

    @Test fun textAndRealSystemKeysRetainNativeWindowHandling() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        val input = input()
        var native = 0
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertTrue(input.key(event(code)) { native++; true })
        }
        assertEquals(3, native)
    }

    @Test fun dialogAndActivityShareHeldInputUntilRelease() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        val input = input()
        input.key(event(KeyEvent.KEYCODE_BUTTON_A)) { false }
        GameInputRouting.update(owner, GameInputMode.RUNNING)
        // A different root (the activity) still sees the same hold as quarantined.
        assertTrue(GameInputRouting.transitions.key(event(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)))
        assertFalse(GameInputRouting.transitions.key(event(KeyEvent.KEYCODE_BUTTON_A, time = 100)))
    }

    @Test fun aFreshPressWorksWhenAndroidOmittedTheOldReleaseWhileBackgrounded() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        val input = input()
        input.key(event(KeyEvent.KEYCODE_BUTTON_A)) { false }
        GameInputRouting.update(owner, GameInputMode.BLOCKED)
        GameInputRouting.update(owner, GameInputMode.MENU)
        assertFalse(GameInputRouting.transitions.key(event(KeyEvent.KEYCODE_BUTTON_A, time = 100)))
    }

    @Test fun gameLayoutKeyCaptureStillTakesTheUntranslatedControllerButton() {
        GameInputRouting.attach(owner, GameInputMode.DIALOG, {})
        val seen = mutableListOf<Int>()
        GamepadInput.capture = { seen += it.keyCode; true }
        val input = input { fail("Captured button was translated"); false }
        assertTrue(input.key(event(KeyEvent.KEYCODE_BUTTON_Y)) { false })
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_Y), seen)
    }
}

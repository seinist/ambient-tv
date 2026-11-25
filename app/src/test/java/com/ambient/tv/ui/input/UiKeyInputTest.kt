package com.ambient.tv.ui.input

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Real Android focus roots verify the transition that direct controller key dispatch bypasses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UiKeyInputTest {
    private val activities = mutableListOf<ActivityController<Activity>>()

    private class CountingView(context: Context) : View(context) {
        var focusRequests = 0
        override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
            focusRequests++
            return super.requestFocus(direction, previouslyFocusedRect)
        }
    }

    private data class Fixture(val root: FrameLayout, val focused: CountingView)

    @After fun tearDown() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        Instrumentation().setInTouchMode(false)
    }

    private fun mountInTouchMode(): Fixture {
        // Set the Android mode before attachment, so the newly created ViewRoot inherits it.
        Instrumentation().setInTouchMode(true)
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        activities += controller
        val activity = controller.get()
        val root = FrameLayout(activity)
        val children = List(2) { index ->
            CountingView(activity).apply {
                isFocusableInTouchMode = true
                root.addView(this, FrameLayout.LayoutParams(100, 100).apply { leftMargin = index * 120 })
            }
        }
        activity.setContentView(root)
        controller.visible().windowFocusChanged(true)
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 400)
        val focused = children.last()
        assertTrue(root.isInTouchMode)
        assertTrue(focused.requestFocus())
        assertSame(focused, root.findFocus())
        children.forEach { it.focusRequests = 0 }
        return Fixture(root, focused)
    }

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, flags: Int = 0) =
        KeyEvent(10L, 20L, action, code, repeat, 0, -1, 0, flags)

    @Test fun navigationDownLeavesTouchModeWithoutReplacingTheFocusedChild() {
        val fixture = mountInTouchMode()

        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_DOWN))

        assertFalse(fixture.root.isInTouchMode)
        assertSame(fixture.focused, fixture.root.findFocus())
        assertTrue(fixture.focused.focusRequests > 0)
    }

    @Suppress("DEPRECATION") // ACTION_MULTIPLE remains a possible legacy event, never a fresh press.
    @Test fun releasesCanceledKeysAndMultipleEventsKeepTouchMode() {
        val fixture = mountInTouchMode()

        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_DOWN, action = KeyEvent.ACTION_UP))
        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER, flags = KeyEvent.FLAG_CANCELED))
        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT, action = KeyEvent.ACTION_MULTIPLE))

        assertTrue(fixture.root.isInTouchMode)
        assertSame(fixture.focused, fixture.root.findFocus())
        assertEquals(0, fixture.focused.focusRequests)
    }

    @Test fun textSystemMediaAndUntranslatedGameButtonsKeepTouchMode() {
        val fixture = mountInTouchMode()

        listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_BUTTON_A).forEach {
            fixture.root.prepareForUiKeyEvent(key(it))
        }
        // An on-screen keyboard confirmation remains touch input even though hardware Enter
        // and Space are valid controller/keyboard activation keys.
        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_ENTER, flags = KeyEvent.FLAG_SOFT_KEYBOARD))
        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_SPACE, flags = KeyEvent.FLAG_SOFT_KEYBOARD))

        assertTrue(fixture.root.isInTouchMode)
        assertSame(fixture.focused, fixture.root.findFocus())
        assertEquals(0, fixture.focused.focusRequests)
    }

    @Test fun furtherNavigationAndHeldRepeatsDoNotRequestFocusAgain() {
        val fixture = mountInTouchMode()
        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(fixture.root.isInTouchMode)
        fixture.focused.focusRequests = 0

        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT))
        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 3))
        fixture.root.prepareForUiKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT, action = KeyEvent.ACTION_UP))
        fixture.root.requestUiKeyInputMode()

        assertSame(fixture.focused, fixture.root.findFocus())
        assertEquals(0, fixture.focused.focusRequests)
    }

    @Test fun consumedGameMenuShortcutCanPrepareTheSameFocusedRoot() {
        val fixture = mountInTouchMode()

        fixture.root.requestUiKeyInputMode()

        assertFalse(fixture.root.isInTouchMode)
        assertSame(fixture.focused, fixture.root.findFocus())
    }
}

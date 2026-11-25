package com.ambient.tv.companion

import android.app.Application
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import com.ambient.tv.ui.display.UiRenderBackKeyGate
import com.ambient.tv.ui.display.UiRenderHostCallbacks
import com.ambient.tv.ui.display.UiRenderInputRoot
import com.ambient.tv.ui.display.findUiRenderWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Two ordinary Robolectric ViewRoots model the separate native and SCVH focus domains. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = Application::class)
class CompanionRemoteInputTest {
    private val activities = mutableListOf<ActivityController<ComponentActivity>>()
    private val windows = mutableListOf<Window>()
    private val dialogs = mutableListOf<ComponentDialog>()

    @After fun tearDown() {
        windows.asReversed().forEach(CompanionRemoteInput::unregister)
        dialogs.asReversed().forEach(ComponentDialog::dismiss)
        activities.asReversed().forEach { it.pause().stop().destroy() }
    }

    @Test fun embeddedKeysReachTheActivityHookAndContentOnceWhileNativeWindowHasNoFocus() {
        val native = activity()
        val carrier = View(native.get()).apply { isFocusableInTouchMode = true }
        native.get().setContentView(carrier)
        assertTrue(carrier.requestFocus())
        val trace = mutableListOf<String>()
        val back = UiRenderBackKeyGate()
        var backs = 0
        val embedded = embedded(native.get().window, callbacks = UiRenderHostCallbacks(
            dispatchKeyEvent = { event, dispatch ->
                trace += "hook:${event.keyCode}:${event.action}"
                val consumed = dispatch()
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (back.onKeyEvent(event, consumed)) backs++
                    true
                } else consumed
            },
            onFailure = { throw AssertionError(it) },
        ))
        val control = object : View(native.get()) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                trace += "content:${event.keyCode}:${event.action}"
                return event.keyCode != KeyEvent.KEYCODE_BACK
            }
        }.apply { isFocusableInTouchMode = true }
        embedded.root.addView(control, FrameLayout.LayoutParams(-1, -1))
        layout(embedded.root, 3840, 2160)
        assertTrue(control.requestFocus())
        rejectNativeInput(native.get().window)
        register(native.get().window) { embedded.root }

        assertFalse(native.get().window.decorView.hasWindowFocus())
        assertSame(carrier, native.get().window.decorView.findFocus())
        assertTrue(embedded.root.hasWindowFocus())
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "up")))
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "back")))
        assertEquals(listOf(
            "hook:19:0", "content:19:0", "hook:19:1", "content:19:1",
            "hook:4:0", "content:4:0", "hook:4:1", "content:4:1",
        ), trace)
        assertEquals(1, backs)
    }

    @Test fun textDeleteAndEnterUseTheEmbeddedEditorWithoutReadingTextOrTheNativeCarrier() {
        val native = activity()
        val carrier = object : View(native.get()) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection =
                throw AssertionError("The native carrier is not the active text editor")
        }.apply { isFocusableInTouchMode = true }
        native.get().setContentView(carrier)
        assertTrue(carrier.requestFocus())
        val embedded = embedded(native.get().window)
        val edits = mutableListOf<String>()
        val editor = editor(native.get(), edits)
        embedded.root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        layout(embedded.root, 3840, 2160)
        assertTrue(editor.requestFocus())
        register(native.get().window) { embedded.root }

        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("text", text = "Café 🎬")))
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "delete")))
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "enter")))
        assertEquals(listOf("commit:Café 🎬:1", "delete:1:0", "action:${EditorInfo.IME_ACTION_SEARCH}"), edits)
    }

    @Test fun embeddedPointerUsesRenderPixelsExactlyOnceAndKeepsHoverExitInTheSameRoot() {
        val native = activity()
        layout(native.get().window.decorView, 1920, 1080)
        val hooks = mutableListOf<Pointer>()
        val delivered = mutableListOf<Pointer>()
        val embedded = embedded(native.get().window, callbacks = UiRenderHostCallbacks(
            dispatchTouchEvent = { event, dispatch -> hooks += Pointer(event); dispatch() },
            dispatchGenericMotionEvent = { event, dispatch -> hooks += Pointer(event); dispatch() },
            onFailure = { throw AssertionError(it) },
        ))
        val control = pointerControl(native.get(), delivered)
        embedded.root.addView(control, FrameLayout.LayoutParams(-1, -1))
        layout(embedded.root, 3840, 2160)
        rejectNativeInput(native.get().window)
        register(native.get().window) { embedded.root }
        // Move to a deterministic normalized position without resetting production cursor state.
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("move", x = -1f, y = -1f)))
        CompanionRemoteInput.hide()
        hooks.clear()
        delivered.clear()

        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.24f, y = 0.74f)))
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("scroll", x = 0.25f, y = -0.5f)))
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("click")))
        assertEquals(listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP), hooks.map { it.action })
        assertEquals(hooks, delivered)
        hooks.forEach { pointer ->
            assertEquals(960f, pointer.x, 0.001f)
            assertEquals(1620f, pointer.y, 0.001f)
        }
        val scroll = hooks.single { it.action == MotionEvent.ACTION_SCROLL }
        assertEquals(2f, scroll.scrollX, 0f)
        assertEquals(-4f, scroll.scrollY, 0f)
        CompanionRemoteInput.hide()
        assertEquals(1, hooks.count { it.action == MotionEvent.ACTION_HOVER_EXIT })
    }

    @Test fun padGameplayClearsTrackpadHoverAndOnlyTheCurrentGameCanRestoreIt() {
        val native = activity()
        val pointers = mutableListOf<Pointer>()
        val keys = mutableListOf<Int>()
        val embedded = embedded(native.get().window, callbacks = UiRenderHostCallbacks(
            dispatchKeyEvent = { event, dispatch -> keys += event.action; dispatch() },
            onFailure = { throw AssertionError(it) },
        ))
        embedded.root.addView(pointerControl(native.get(), pointers), FrameLayout.LayoutParams(-1, -1))
        layout(embedded.root, 1920, 1080)
        register(native.get().window) { embedded.root }
        val oldGame = Any()
        val currentGame = Any()
        try {
            assertTrue(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.1f)))
            CompanionRemoteInput.setGamePointerAllowed(oldGame, false)
            assertEquals(MotionEvent.ACTION_HOVER_EXIT, pointers.last().action)
            pointers.clear()
            CompanionRemoteInput.setGamePointerAllowed(currentGame, false)
            CompanionRemoteInput.releaseGamePointerPolicy(oldGame)
            for (action in listOf("move", "click", "scroll")) {
                assertTrue(CompanionRemoteInput.dispatch(CompanionInput(action)))
            }
            assertTrue(pointers.isEmpty())
            assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "back")))
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), keys)

            // The current game's menu or explicit pointer mode restores normal trackpad input.
            CompanionRemoteInput.setGamePointerAllowed(currentGame, true)
            assertTrue(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.1f)))
            assertEquals(listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE),
                pointers.map { it.action })
            CompanionRemoteInput.setGamePointerAllowed(currentGame, false)
            pointers.clear()
            CompanionRemoteInput.releaseGamePointerPolicy(currentGame)
            assertTrue(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.1f)))
            assertEquals(listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE),
                pointers.map { it.action })
        } finally {
            CompanionRemoteInput.releaseGamePointerPolicy(oldGame)
            CompanionRemoteInput.releaseGamePointerPolicy(currentGame)
        }
    }

    @Test fun registeredContentCanReturnToNativeFocusTextAndWindowPixelDispatch() {
        val native = activity()
        val embedded = embedded(native.get().window)
        val edits = mutableListOf<String>()
        val editor = editor(native.get(), edits)
        embedded.root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        layout(embedded.root, 3840, 2160)
        assertTrue(editor.requestFocus())
        register(native.get().window) { editor.findCompanionEmbeddedInputRoot() }
        assertSame(embedded.root, editor.findCompanionEmbeddedInputRoot())
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("text", text = "embedded")))

        // UiRenderResolutionHost.takeContentView() reparents this same live view on failure.
        embedded.root.removeView(editor)
        embedded.controller.windowFocusChanged(false)
        native.get().setContentView(editor)
        native.windowFocusChanged(true)
        assertTrue(editor.requestFocus())
        // Robolectric's focus callback traverses using its default display size. Apply the
        // explicit native viewport afterwards so this test retains its 2:1 render/window ratio.
        val nativeDecor = native.get().window.decorView
        layout(nativeDecor, 1920, 1080)
        assertEquals(1920, nativeDecor.width)
        assertEquals(1080, nativeDecor.height)
        assertNull(editor.findCompanionEmbeddedInputRoot())
        val pointers = mutableListOf<Pointer>()
        val keys = mutableListOf<Int>()
        val callback = native.get().window.callback
        native.get().window.callback = object : Window.Callback by callback {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                pointers += Pointer(event)
                return true
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                keys += event.action
                return true
            }
        }

        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("text", text = "native")))
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "right")))
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), keys)
        assertEquals(listOf("commit:embedded:1", "commit:native:1"), edits)
        assertEquals(1920, nativeDecor.width)
        assertEquals(1080, nativeDecor.height)
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("move", x = -1f, y = -1f)))
        pointers.clear()
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.24f, y = 0.74f)))
        val move = pointers.single { it.action == MotionEvent.ACTION_HOVER_MOVE }
        assertEquals(480f, move.x, 0.001f)
        assertEquals(810f, move.y, 0.001f)
    }

    @Test fun nestedDialogSelectsItsOwnEmbeddedRootAndUnfocusedSurfacesReceiveNothing() {
        val native = activity()
        val activityEvents = mutableListOf<Int>()
        val activityRoot = embedded(native.get().window, keyCallbacks(activityEvents))
        register(native.get().window) { activityRoot.root }
        val firstWindow = dialog(native.get()).window!!
        val firstEvents = mutableListOf<Int>()
        val first = embedded(firstWindow, keyCallbacks(firstEvents))
        val firstView = View(native.get())
        first.root.addView(firstView)
        register(firstWindow) { firstView.findCompanionEmbeddedInputRoot() }
        val nestedWindow = dialog(native.get()).window!!
        val nestedEvents = mutableListOf<Int>()
        val nested = embedded(nestedWindow, keyCallbacks(nestedEvents))
        val nestedView = View(native.get())
        nested.root.addView(nestedView)
        register(nestedWindow) { nestedView.findCompanionEmbeddedInputRoot() }
        assertSame(firstWindow, firstView.findUiRenderWindow())
        assertSame(nestedWindow, nestedView.findUiRenderWindow())

        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok")))
        assertEquals(2, nestedEvents.size)
        assertTrue(firstEvents.isEmpty())
        assertTrue(activityEvents.isEmpty())
        CompanionRemoteInput.unregister(nestedWindow)
        nested.controller.windowFocusChanged(false)
        assertTrue(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok")))
        assertEquals(2, firstEvents.size)
        first.controller.windowFocusChanged(false)
        activityRoot.controller.windowFocusChanged(false)
        assertFalse(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok")))
        assertFalse(CompanionRemoteInput.dispatch(CompanionInput("text", text = "no owner")))
        assertFalse(CompanionRemoteInput.dispatch(CompanionInput("move", x = 0.1f)))
        assertTrue(activityEvents.isEmpty())
    }

    private fun activity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also {
            activities += it
            it.windowFocusChanged(false)
        }

    private data class Embedded(val controller: ActivityController<ComponentActivity>, val root: UiRenderInputRoot)

    private fun embedded(window: Window, callbacks: UiRenderHostCallbacks = UiRenderHostCallbacks(
        onFailure = { throw AssertionError(it) },
    )): Embedded {
        val controller = activity()
        val root = UiRenderInputRoot(controller.get(), window, callbacks)
        controller.get().setContentView(root)
        layout(root, 3840, 2160)
        controller.windowFocusChanged(true)
        return Embedded(controller, root)
    }

    private fun register(window: Window, inputRoot: () -> View?) {
        windows += window
        CompanionRemoteInput.register(window, inputRoot = inputRoot)
    }

    private fun dialog(activity: ComponentActivity): ComponentDialog = ComponentDialog(activity).also {
        dialogs += it
        it.setContentView(View(activity))
        it.show()
    }

    private fun keyCallbacks(events: MutableList<Int>) = UiRenderHostCallbacks(
        dispatchKeyEvent = { event, _ -> events += event.keyCode; true },
        onFailure = { throw AssertionError(it) },
    )

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun rejectNativeInput(window: Window) {
        val callback = window.callback
        window.callback = object : Window.Callback by callback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                throw AssertionError("Embedded keys must not re-enter the native Window.Callback")
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                throw AssertionError("Embedded touch must not be mapped twice by the native callback")
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                throw AssertionError("Embedded hover must not be mapped twice by the native callback")
        }
    }

    private fun editor(activity: ComponentActivity, edits: MutableList<String>): View = object : View(activity) {
        init { isFocusableInTouchMode = true }
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    edits += "commit:$text:$newCursorPosition"
                    return true
                }
                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                    edits += "delete:$beforeLength:$afterLength"
                    return true
                }
                override fun performEditorAction(actionCode: Int): Boolean {
                    edits += "action:$actionCode"
                    return true
                }
                override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence =
                    throw AssertionError("Remote input must not read editor text")
                override fun getTextAfterCursor(length: Int, flags: Int): CharSequence =
                    throw AssertionError("Remote input must not read editor text")
                override fun getSelectedText(flags: Int): CharSequence =
                    throw AssertionError("Remote input must not read editor text")
            }
        }
    }

    private data class Pointer(val action: Int, val x: Float, val y: Float, val scrollX: Float, val scrollY: Float) {
        constructor(event: MotionEvent) : this(event.actionMasked, event.x, event.y,
            event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL))
    }

    private fun pointerControl(activity: ComponentActivity, events: MutableList<Pointer>): View = object : View(activity) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { events += Pointer(event); return true }
        override fun dispatchHoverEvent(event: MotionEvent): Boolean { events += Pointer(event); return true }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { events += Pointer(event); return true }
    }
}

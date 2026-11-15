package com.ambient.tv.ui.display

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class UiRenderInputTest {
    @Test fun embeddedKeyRunsTheActivityHookOnceAndContinuationReachesFocusedContentOnce() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val trace = mutableListOf<String>()
        val root = UiRenderInputRoot(activity, activity.window, UiRenderHostCallbacks(
            dispatchKeyEvent = { _, dispatch ->
                trace += "activity-before"
                dispatch().also { trace += "activity-after" }
            },
            onFailure = { throw AssertionError(it) },
        ))
        val control = object : View(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                trace += "content"
                return true
            }
        }.apply { isFocusable = true; isFocusableInTouchMode = true }
        try {
            root.addView(control, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            assertTrue(control.requestFocus())
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(root.dispatchKeyEvent(event))
            assertEquals(listOf("activity-before", "content", "activity-after"), trace)
            trace.clear()
            assertTrue(root.dispatchKeyEventToContent(event))
            assertEquals(listOf("content"), trace)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun consumedWakeKeyDoesNotReachAnUnderlyingFocusedControl() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var clicks = 0
        val root = UiRenderInputRoot(activity, activity.window, UiRenderHostCallbacks(
            dispatchKeyEvent = { _, _ -> true },
            onFailure = { throw AssertionError(it) },
        ))
        val control = object : View(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean { clicks++; return true }
        }.apply { isFocusable = true; isFocusableInTouchMode = true }
        try {
            root.addView(control, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            assertTrue(control.requestFocus())
            assertTrue(root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
            assertEquals(0, clicks)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun embeddedDialogUsesItsNativeWindowOwnersAndCanReturnTheSameContentView() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val dialog = ComponentDialog(activity)
        val content = ComposeView(activity)
        val recomposer = Recomposer(EmptyCoroutineContext)
        val host = UiRenderResolutionHost(
            activity, "1080p", content, UiRenderHostCallbacks(onFailure = { throw AssertionError(it) }),
            window = checkNotNull(dialog.window),
            parentCompositionContext = recomposer,
            ownerView = activity.window.decorView,
        )
        try {
            assertSame(dialog, content.findViewTreeLifecycleOwner())
            assertSame(dialog, content.findViewTreeSavedStateRegistryOwner())
            assertSame(dialog, content.findViewTreeOnBackPressedDispatcherOwner())
            assertSame(activity, content.findViewTreeViewModelStoreOwner())
            assertSame(dialog.window, content.findUiRenderWindow())
            assertSame(dialog, (content.parent as View).findViewTreeOnBackPressedDispatcherOwner())
            assertSame(content, host.takeContentView())
            assertNull(content.parent)
            assertSame(dialog, content.findViewTreeSavedStateRegistryOwner())
        } finally {
            host.close()
            content.disposeComposition()
            recomposer.cancel()
            controller.pause().stop().destroy()
        }
    }
}

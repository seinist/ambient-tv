package com.ambient.tv.ui.input

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PointerIconFrameLayoutTest {
    @Test fun idleHidesANativeChildCursorWithoutLosingTheFirstClickOrChangingItsActiveIcon() {
        val lifecycle = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = lifecycle.get()
        val owner = Any()
        val emptyIcon = PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_NULL)
        val handIcon = PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_HAND)
        val clicks = mutableListOf<Int>()
        val root = PointerIconFrameLayout(activity)
        val nativeControl = object : View(activity) {
            override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon = handIcon
            override fun onTouchEvent(event: MotionEvent): Boolean {
                clicks += event.actionMasked
                return true
            }
        }
        val hover = mouseEvent(MotionEvent.ACTION_HOVER_MOVE)
        try {
            PointerVisibility.start(owner, 2)
            PointerVisibility.onNonPointerInput()
            root.addView(nativeControl, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 300, 200)
            assertTrue(root.isAttachedToWindow)
            assertEquals(emptyIcon, root.onResolvePointerIcon(hover, 0))

            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val click = mouseEvent(action)
                try { assertTrue(root.dispatchTouchEvent(click)) } finally { click.recycle() }
            }
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), clicks)
            assertSame(handIcon, root.onResolvePointerIcon(hover, 0))

            // No new input event: the delayed visibility update must refresh this native root.
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertEquals(emptyIcon, root.pointerIcon)
            assertEquals(emptyIcon, root.onResolvePointerIcon(hover, 0))
        } finally {
            hover.recycle()
            PointerVisibility.stop(owner)
            lifecycle.pause().stop().destroy()
        }
    }

    private fun mouseEvent(action: Int): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val position = MotionEvent.PointerCoords().apply { x = 60f; y = 70f; pressure = 1f; size = 1f }
        return MotionEvent.obtain(now, now, action, 1, arrayOf(pointer), arrayOf(position), 0,
            if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
            1f, 1f, 80_031, 0, InputDevice.SOURCE_MOUSE, 0)
    }
}

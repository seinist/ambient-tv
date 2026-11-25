package com.ambient.tv.ui.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.widget.FrameLayout

/**
 * Resolves the app's idle mouse icon before native children (including Compose and WebView).
 * Setting a plain ViewGroup.pointerIcon is insufficient because children otherwise win.
 */
internal open class PointerIconFrameLayout(context: Context) : FrameLayout(context) {
    private val hiddenPointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    private var hidePointerIcon = PointerVisibility.hideNativePointer
    private var removePointerObserver: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removePointerObserver?.invoke()
        removePointerObserver = PointerVisibility.observeNativeVisibility { hidden ->
            hidePointerIcon = hidden
            // Besides storing the value, this public API requests a ViewRoot/window refresh
            // so an already stationary pointer disappears when the timeout expires.
            pointerIcon = if (hidden) hiddenPointerIcon else null
        }
    }

    override fun onDetachedFromWindow() {
        removePointerObserver?.invoke()
        removePointerObserver = null
        super.onDetachedFromWindow()
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? =
        if (hidePointerIcon && event.isFromSource(InputDevice.SOURCE_MOUSE)) hiddenPointerIcon
        else super.onResolvePointerIcon(event, pointerIndex)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        PointerVisibility.onMotionEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        PointerVisibility.onMotionEvent(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        PointerVisibility.onKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) PointerVisibility.onWindowFocusLost()
    }
}

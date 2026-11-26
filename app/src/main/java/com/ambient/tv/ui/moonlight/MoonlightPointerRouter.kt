package com.ambient.tv.ui.moonlight

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.ambient.tv.moonlight.MoonlightStreamEngine
import kotlin.math.roundToInt

/** Touch contacts and mouse buttons are owned by one Activity and released on every UI handoff. */
internal class MoonlightPointerRouter(private val engine: MoonlightStreamEngine, private val owner: Any) {
    private val contacts = LinkedHashMap<Int, Pair<Float, Float>>()
    private var mouseButtons = 0
    private var touchMouse = false
    private var touchFallback = false
    private var view: View? = null

    fun attach(target: View) { view = target }
    fun release() {
        contacts.forEach { (id, point) -> engine.sendTouchEvent(owner, 4, id, point.first, point.second, 0f) }
        contacts.clear()
        if (touchMouse) engine.sendMouseButton(owner, false, source = 1)
        touchMouse = false
        touchFallback = false
        updateMouseButtons(0)
        view?.let { if (it.hasPointerCapture()) it.releasePointerCapture() }
    }

    private fun updateMouseButtons(next: Int) {
        val androidBits = intArrayOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD)
        for ((index, bit) in androidBits.withIndex()) {
            if ((mouseButtons and bit) != (next and bit))
                engine.sendMouseButton(owner, next and bit != 0, index + 1)
        }
        mouseButtons = next
    }

    fun mouse(target: View, event: MotionEvent, captured: Boolean = false): Boolean {
        if (!captured && !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (captured) {
                    if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                        engine.sendMouseMove(owner, event.x.roundToInt(), event.y.roundToInt())
                } else engine.sendMousePosition(owner, event.x.roundToInt(), event.y.roundToInt(),
                    target.width.coerceAtLeast(1), target.height.coerceAtLeast(1))
                updateMouseButtons(if (event.actionMasked == MotionEvent.ACTION_UP) 0 else event.buttonState)
                if (!captured && event.actionMasked == MotionEvent.ACTION_DOWN && target.hasFocus())
                    target.requestPointerCapture()
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                engine.sendMouseScroll(owner, event.getAxisValue(MotionEvent.AXIS_VSCROLL).roundToInt())
                return true
            }
            MotionEvent.ACTION_CANCEL -> { updateMouseButtons(0); return true }
            else -> return false
        }
    }

    fun touch(target: View, event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) target.requestFocus()
            return mouse(target, event)
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { release(); return true }
        val width = target.width.coerceAtLeast(1)
        val height = target.height.coerceAtLeast(1)
        fun send(index: Int, type: Byte) {
            val id = event.getPointerId(index)
            if (id !in 0..31) return
            val x = (event.getX(index) / width).coerceIn(0f, 1f)
            val y = (event.getY(index) / height).coerceIn(0f, 1f)
            if (type == 2.toByte()) contacts.remove(id)
            else if (type == 1.toByte() || id in contacts) contacts[id] = x to y
            else return
            if (!touchFallback) {
                val result = engine.sendTouchEvent(owner, type, id, x, y, event.getPressure(index))
                if (result == -5501) {
                    contacts.forEach { (other, point) ->
                        if (other != id) engine.sendTouchEvent(owner, 4, other, point.first, point.second, 0f)
                    }
                    touchFallback = true
                }
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(event.actionIndex, 1)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) send(i, 3)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(event.actionIndex, 2)
            else -> return false
        }
        if (touchFallback) {
            contacts.values.firstOrNull()?.let { (x, y) ->
                engine.sendMousePosition(owner, (x * width).roundToInt(), (y * height).roundToInt(), width, height)
            }
            val down = contacts.isNotEmpty()
            if (down != touchMouse) engine.sendMouseButton(owner, down, source = 1)
            touchMouse = down
            if (!down) touchFallback = false
        }
        return true
    }
}

package com.ambient.tv.companion

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class CompanionInput(val action: String, val key: String = "", val text: String = "", val x: Float = 0f, val y: Float = 0f) {
    fun validate() {
        require(action in setOf("key", "text", "move", "click", "scroll"))
        require(x.isFinite() && y.isFinite() && x in -1f..1f && y in -1f..1f)
        require(text.length <= 2048 && text.none { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t') })
        if (action == "key") require(key in setOf("up", "down", "left", "right", "ok", "back", "delete", "enter"))
    }
}

/** Single controller lease, identified by TLS session (not the user-provided device label). */
internal class CompanionInputLease {
    var owner: String? = null; private set
    var name: String = ""; private set
    private var until = 0L
    fun claim(session: String, label: String, now: Long): Boolean {
        expire(now)
        if (owner != null && owner != session) return false
        owner = session; name = label; until = now + 60_000
        return true
    }
    fun owns(session: String, now: Long): Boolean { expire(now); return owner == session }
    fun release(session: String? = null): Boolean {
        if (owner == null || (session != null && owner != session)) return false
        owner = null; name = ""; until = 0; return true
    }
    fun expire(now: Long): Boolean = if (owner != null && now >= until) release() else false
}

/** In-process input only. No AccessibilityService, root, ADB, global injection, or text readback. */
object CompanionRemoteInput {
    private val windows = LinkedHashMap<Window, (() -> Unit)?>()
    private var point = Offset(0.5f, 0.5f)
    private data class Cursor(val window: Window?, val point: Offset)
    private val cursor = MutableStateFlow(Cursor(null, point))
    private data class Hover(val window: Window, val x: Float, val y: Float)
    private var hover: Hover? = null
    fun register(window: Window, interaction: (() -> Unit)? = null) {
        if (!windows.containsKey(window)) hide()
        windows[window] = interaction
    }
    fun unregister(window: Window) {
        if (cursor.value.window === window || hover?.window === window) hide()
        windows.remove(window)
    }
    private fun endHover() {
        val previous = hover ?: return
        hover = null
        val event = mouseEvent(MotionEvent.ACTION_HOVER_EXIT, previous.x, previous.y)
        try { previous.window.callback?.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }
    fun hide() {
        endHover()
        cursor.value = Cursor(null, point)
        com.ambient.tv.ui.common.suppressTvPointerFocus()
    }
    private fun active(): Window? = windows.keys.lastOrNull { it.decorView.isShown && it.decorView.hasWindowFocus() }

    private fun mouseEvent(action: Int, x: Float, y: Float, scrollX: Float = 0f, scrollY: Float = 0f,
        time: Long = SystemClock.uptimeMillis()): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
            if (action == MotionEvent.ACTION_SCROLL) {
                setAxisValue(MotionEvent.AXIS_VSCROLL, scrollY * 8)
                setAxisValue(MotionEvent.AXIS_HSCROLL, scrollX * 8)
            }
        }
        return MotionEvent.obtain(time, time, action, 1, arrayOf(properties), arrayOf(coords), 0,
            if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    fun dispatch(input: CompanionInput): Boolean {
        input.validate()
        if (input.action == "key" || input.action == "text") hide()
        val window = active() ?: return false
        if (hover?.window?.let { it !== window } == true) hide()
        windows.values.lastOrNull { it != null }?.invoke()
        val decor = window.decorView
        if (input.action == "text" || input.action == "key" && input.key in setOf("enter", "delete")) {
            val focused = decor.findFocus() ?: return false
            val info = EditorInfo()
            val connection = focused.onCreateInputConnection(info) ?: return false
            // Never obtain existing text (including passwords) from the receiver.
            return when {
                input.action == "text" -> connection.commitText(input.text, 1)
                input.key == "delete" -> connection.deleteSurroundingTextInCodePoints(1, 0)
                else -> connection.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
            }
        }
        if (input.action == "key") {
            val code = when (input.key) {
                "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK
                else -> return false
            }
            val time = SystemClock.uptimeMillis()
            window.callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            window.callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
            return true
        }
        if (input.action == "move") point = Offset((point.x + input.x).coerceIn(0.01f, 0.99f), (point.y + input.y).coerceIn(0.01f, 0.99f))
        cursor.value = Cursor(window, point)
        val x = point.x * decor.width; val y = point.y * decor.height
        val time = SystemClock.uptimeMillis()
        fun event(action: Int) = mouseEvent(action, x, y, input.x, input.y, time)
        if (input.action == "click") {
            endHover()
            event(MotionEvent.ACTION_DOWN).also { window.callback.dispatchTouchEvent(it); it.recycle() }
            event(MotionEvent.ACTION_UP).also { window.callback.dispatchTouchEvent(it); it.recycle() }
        } else {
            if (hover == null) {
                val enter = event(MotionEvent.ACTION_HOVER_ENTER)
                hover = Hover(window, x, y)
                try { window.callback.dispatchGenericMotionEvent(enter) } finally { enter.recycle() }
            }
            val motion = event(if (input.action == "scroll") MotionEvent.ACTION_SCROLL else MotionEvent.ACTION_HOVER_MOVE)
            hover = Hover(window, x, y)
            try { window.callback.dispatchGenericMotionEvent(motion) } finally { motion.recycle() }
        }
        return true
    }

    @Composable fun Overlay(window: Window?) {
        val state by cursor.collectAsState()
        val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
        if (window != null && state.window == window) Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * state.point.x, size.height * state.point.y)
            drawCircle(Color.Black.copy(alpha = 0.7f), 11f, center)
            drawCircle(Color.White, 8f, center)
            drawCircle(accent, 4f, center)
        }
    }
}

@Composable
fun CompanionDialogInputSurface() {
    val view = LocalView.current
    val window = remember(view) { generateSequence(view as View?) { it.parent as? View }.mapNotNull { (it.parent as? DialogWindowProvider)?.window }.firstOrNull() }
    DisposableEffect(window) {
        window?.let(CompanionRemoteInput::register)
        onDispose { window?.let(CompanionRemoteInput::unregister) }
    }
    CompanionRemoteInput.Overlay(window)
}

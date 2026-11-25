package com.ambient.tv.companion

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.ui.display.UiRenderInputRoot
import com.ambient.tv.ui.display.findUiRenderWindow
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
    private data class Registration(val inputRoot: (() -> View?)?, val interaction: (() -> Unit)?)
    private val windows = LinkedHashMap<Window, Registration>()
    private var point = Offset(0.5f, 0.5f)
    private data class Cursor(val window: Window?, val point: Offset)
    private val cursor = MutableStateFlow(Cursor(null, point))
    private data class GamePointerPolicy(val owner: Any, val allowed: Boolean, val inMenu: Boolean)
    private var gamePointerPolicy: GamePointerPolicy? = null

    /** Gameplay must not keep a menu trackpad cursor above a gamepad-only game. */
    internal fun setGamePointerAllowed(owner: Any, allowed: Boolean, inMenu: Boolean = false) {
        val previous = gamePointerPolicy
        if (previous?.owner === owner && previous.allowed == allowed && previous.inMenu == inMenu) return
        gamePointerPolicy = GamePointerPolicy(owner, allowed, inMenu)
        // DS and other pointer systems allow both menu and game input. End the
        // menu hover on this transition too; a new game movement may start one.
        hide()
    }

    /** An old game's disposal must not clear the replacement game's policy. */
    internal fun releaseGamePointerPolicy(owner: Any) {
        if (gamePointerPolicy?.owner !== owner) return
        gamePointerPolicy = null
        hide()
    }

    /** Native callbacks take window pixels; embedded roots take their own render pixels. */
    private data class Endpoint(val window: Window, val root: View, val embedded: Boolean) {
        fun dispatchKeyEvent(event: KeyEvent): Boolean = if (embedded) root.dispatchKeyEvent(event)
            else window.callback?.dispatchKeyEvent(event) ?: false
        fun dispatchTouchEvent(event: MotionEvent): Boolean = if (embedded) root.dispatchTouchEvent(event)
            else window.callback?.dispatchTouchEvent(event) ?: false
        fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = if (embedded) root.dispatchGenericMotionEvent(event)
            else window.callback?.dispatchGenericMotionEvent(event) ?: false
    }

    private data class Hover(val endpoint: Endpoint, val x: Float, val y: Float)
    private var hover: Hover? = null

    /**
     * SurfaceControlViewHost owns focus independently of the native Window. Resolve its root
     * for each command: the same composition can return to native rendering after a failure.
     * Embedded dispatch goes through its input callbacks once, without a second pixel mapping.
     */
    fun register(window: Window, inputRoot: (() -> View?)? = null, interaction: (() -> Unit)? = null) {
        if (!windows.containsKey(window)) hide()
        windows[window] = Registration(inputRoot, interaction)
    }
    fun unregister(window: Window) {
        if (cursor.value.window === window || hover?.endpoint?.window === window) hide()
        windows.remove(window)
    }
    private fun endHover() {
        val previous = hover ?: return
        hover = null
        val event = mouseEvent(MotionEvent.ACTION_HOVER_EXIT, previous.x, previous.y)
        try { previous.endpoint.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }
    fun hide() {
        endHover()
        cursor.value = Cursor(null, point)
        com.ambient.tv.ui.common.suppressTvPointerFocus()
    }
    private fun active(): Endpoint? {
        for ((window, registration) in windows.entries.toList().asReversed()) {
            val embedded = registration.inputRoot?.invoke()
            if (embedded != null && embedded.isShown && embedded.hasWindowFocus()) {
                return Endpoint(window, embedded, embedded = true)
            }
            val decor = window.decorView
            if (decor.isShown && decor.hasWindowFocus()) return Endpoint(window, decor, embedded = false)
        }
        return null
    }

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
        if (input.action in setOf("move", "click", "scroll") && gamePointerPolicy?.allowed == false) {
            hide()
            return true
        }
        if (input.action == "key" || input.action == "text") hide()
        val endpoint = active() ?: run { hide(); return false }
        if (hover?.endpoint?.let { it != endpoint } == true) hide()
        windows.values.lastOrNull { it.interaction != null }?.interaction?.invoke()
        val root = endpoint.root
        if (input.action == "text" || input.action == "key" && input.key in setOf("enter", "delete")) {
            val focused = root.findFocus() ?: return false
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
            endpoint.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            endpoint.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
            return true
        }
        if (input.action == "move") point = Offset((point.x + input.x).coerceIn(0.01f, 0.99f), (point.y + input.y).coerceIn(0.01f, 0.99f))
        cursor.value = Cursor(endpoint.window, point)
        val x = point.x * root.width; val y = point.y * root.height
        val time = SystemClock.uptimeMillis()
        fun event(action: Int) = mouseEvent(action, x, y, input.x, input.y, time)
        if (input.action == "click") {
            endHover()
            event(MotionEvent.ACTION_DOWN).also { try { endpoint.dispatchTouchEvent(it) } finally { it.recycle() } }
            event(MotionEvent.ACTION_UP).also { try { endpoint.dispatchTouchEvent(it) } finally { it.recycle() } }
        } else {
            if (hover == null) {
                val enter = event(MotionEvent.ACTION_HOVER_ENTER)
                hover = Hover(endpoint, x, y)
                try { endpoint.dispatchGenericMotionEvent(enter) } finally { enter.recycle() }
            }
            val motion = event(if (input.action == "scroll") MotionEvent.ACTION_SCROLL else MotionEvent.ACTION_HOVER_MOVE)
            hover = Hover(endpoint, x, y)
            try { endpoint.dispatchGenericMotionEvent(motion) } finally { motion.recycle() }
        }
        return true
    }

    /**
     * A game-local OK hold, pinned to the original window. The owner must release it (cancelled
     * on context/lifecycle loss); the game bridge additionally bounds the hold with a watchdog.
     * This avoids turning a held rewind button into repeated OK taps or an UP in a new dialog.
     */
    internal fun beginGameConfirm(deviceId: Int): ((Boolean) -> Unit)? {
        hide()
        val endpoint = active() ?: return null
        windows.values.lastOrNull { it.interaction != null }?.interaction?.invoke()
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, cancelled: Boolean = false) = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0,
            deviceId, 0, if (cancelled) KeyEvent.FLAG_CANCELED else 0, InputDevice.SOURCE_DPAD)
        endpoint.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN))
        var released = false
        return { cancelled ->
            if (!released) {
                released = true
                val current = active()
                val lost = current == null || current.window !== endpoint.window || current.root !== endpoint.root
                endpoint.dispatchKeyEvent(event(KeyEvent.ACTION_UP, cancelled || lost))
            }
        }
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
    val window = remember(view) { view.findUiRenderWindow() }
    DisposableEffect(window, view) {
        window?.let { CompanionRemoteInput.register(it, inputRoot = { view.findCompanionEmbeddedInputRoot() }) }
        onDispose { window?.let(CompanionRemoteInput::unregister) }
    }
    CompanionRemoteInput.Overlay(window)
}

/** Re-evaluate ancestry after a hosted dialog's content is returned to its native window. */
internal fun View.findCompanionEmbeddedInputRoot(): View? =
    generateSequence(this) { it.parent as? View }.filterIsInstance<UiRenderInputRoot>()
        .firstOrNull()?.takeIf { it.isAttachedToWindow && it.isShown }

package com.ambient.tv.companion

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.ambient.tv.ui.gamepad.GameInputMode
import com.ambient.tv.ui.gamepad.GamepadConfig
import com.ambient.tv.ui.gamepad.GamepadController
import com.ambient.tv.ui.gamepad.GamepadHost
import com.ambient.tv.ui.gamepad.GamepadInput
import com.ambient.tv.ui.gamepad.GamepadVolumeCommand
import com.ambient.tv.ui.gamepad.retro.isPhysicalGamepadEvent
import com.ambient.tv.ui.keyboard.KeyAction

/**
 * One translator observes physical input in both gameplay and OSD. Keeping it alive across
 * modes lets the controller layer suppress held buttons/sticks until released after a switch.
 * Its host sends only game-local commands; phone app shortcuts never run from this page.
 */
internal class CompanionGamepadMenuInput(
    gameInput: (InputEvent) -> Boolean,
    private val navigate: (String, String, Long) -> Unit,
    private val resume: (String, Long) -> Unit,
    private val confirm: (Int, Boolean, Boolean, String, Long) -> Unit,
    scheduler: (Long, () -> Unit) -> (() -> Unit),
    clock: () -> Long,
    config: () -> GamepadConfig = { GamepadInput.config },
    private val physicalEvent: (InputEvent) -> Boolean = ::isPhysicalGamepadEvent,
) {
    private var mode = GameInputMode.BLOCKED
    private var contextRevision = -1L
    private var gameSession = ""
    private var generation = 0L
    private val host = object : GamepadHost {
        override val playerForeground = false
        override val recordingKey = false
        override val gameInputMode: GameInputMode get() = mode
        override val gameInputGeneration: Long get() = generation
        override fun now() = clock()
        override fun schedule(delayMs: Long, task: () -> Unit) = scheduler(delayMs, task)
        override fun volume(command: GamepadVolumeCommand) = Unit
        override fun perform(action: KeyAction) = Unit
        override fun emitKey(event: KeyEvent) = emitNavigation(event)
    }
    private val controller = GamepadController(host, config, onButton = {},
        rawSink = { gameInput }, capture = { null })

    fun update(next: GameInputMode, revision: Long, session: String = "") {
        if (mode == next && contextRevision == revision && gameSession == session) return
        mode = next
        contextRevision = revision
        gameSession = session
        generation++
        controller.notifyGameInputChanged()
    }

    fun onEvent(event: InputEvent): Boolean {
        if (!physicalEvent(event)) return false
        when (event) {
            is KeyEvent -> if (!controller.handleKey(event)) emitNavigation(event)
            is MotionEvent -> controller.handleMotion(event)
        }
        // Unmapped physical buttons stay on this controller page, including while busy.
        return true
    }

    fun releaseAll() = controller.releaseAll()
    fun deviceRemoved(id: Int) = controller.deviceRemoved(id)

    private fun emitNavigation(event: KeyEvent) {
        val confirmKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirmKey && event.action == KeyEvent.ACTION_UP) {
            confirm(event.deviceId, false, event.isCanceled, gameSession, contextRevision)
            return
        }
        if (mode != GameInputMode.MENU && mode != GameInputMode.DIALOG ||
            event.action != KeyEvent.ACTION_DOWN || event.isCanceled) return
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ok"
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> "back"
            KeyEvent.KEYCODE_MENU -> "resume"
            else -> return
        }
        // Holding Confirm/Back/Resume cannot close several dialogs or reload several states.
        if (event.repeatCount != 0 && action !in setOf("up", "down", "left", "right")) return
        when (action) {
            "resume" -> resume(gameSession, contextRevision)
            "ok" -> confirm(event.deviceId, true, false, gameSession, contextRevision)
            else -> navigate(action, gameSession, contextRevision)
        }
    }
}

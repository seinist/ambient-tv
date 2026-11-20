package com.ambient.tv.ui.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import com.ambient.tv.ui.keyboard.KEY_BINDING_LONG_PRESS_MS
import com.ambient.tv.ui.keyboard.KeyAction

/** What the controller layer needs from the activity; a fake in tests. */
interface GamepadHost {
    /** Sends a key through the app's normal key path (screensaver, bindings, focused control). */
    fun emitKey(event: KeyEvent)
    fun perform(action: KeyAction)
    fun volume(command: GamepadVolumeCommand)
    val playerForeground: Boolean
    /** While Settings records a remote key only keys are sent, no actions. */
    val recordingKey: Boolean
    /** Same clock as [KeyEvent.getEventTime] (uptime milliseconds). */
    fun now(): Long
    /** Runs [task] on the main thread after [delayMs]; returns a cancel function. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** Like a held remote key: the first repeat after this delay, then every [KEY_REPEAT_INTERVAL_MS]. */
internal const val KEY_REPEAT_TIMEOUT_MS = 500L
internal const val KEY_REPEAT_INTERVAL_MS = 50L

/**
 * Turns controller buttons into the commands of [GamepadConfig]. Key commands are held while the
 * button is held and repeat on their own (controllers do not all send key repeats). A button
 * with a long-press command decides on release or after [KEY_BINDING_LONG_PRESS_MS].
 */
class GamepadButtonDispatcher(
    private val host: GamepadHost,
    private val config: () -> GamepadConfig,
) {
    private class Press(
        val deviceId: Int,
        val short: GamepadCommand,
        val long: GamepadCommand,
    ) {
        var emittedCode: Int? = null
        var emittedDownTime = 0L
        var repeatCount = 0
        var longFired = false
        var cancel: (() -> Unit)? = null
    }

    private val presses = mutableMapOf<Long, Press>()

    /** Handles a controller button key event; repeats from the system are ignored. */
    fun handle(event: KeyEvent): Boolean {
        val button = GamepadButton.fromKeyCode(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) press(button, event.deviceId, event.eventTime)
            KeyEvent.ACTION_UP -> release(button, event.deviceId, canceled = event.isCanceled)
        }
        return true
    }

    fun press(button: GamepadButton, deviceId: Int, time: Long) {
        val identity = identity(deviceId, button)
        if (identity in presses) return
        val player = host.playerForeground
        val cfg = config()
        fun allowed(command: GamepadCommand) =
            if (host.recordingKey && command !is GamepadCommand.Key) GamepadCommand.None else command
        val press = Press(
            deviceId = deviceId,
            short = allowed(cfg.resolve(button, long = false, player = player)),
            long = allowed(cfg.resolve(button, long = true, player = player)),
        )
        presses[identity] = press
        if (press.long == GamepadCommand.None) {
            start(press, press.short, time)
        } else {
            press.cancel = host.schedule(KEY_BINDING_LONG_PRESS_MS) {
                if (presses[identity] !== press) return@schedule
                press.longFired = true
                start(press, press.long, host.now())
            }
        }
    }

    fun release(button: GamepadButton, deviceId: Int, canceled: Boolean = false) {
        val press = presses.remove(identity(deviceId, button)) ?: return
        press.cancel?.invoke()
        press.cancel = null
        if (press.emittedCode == null && !press.longFired && !canceled && press.long != GamepadCommand.None) {
            // Released before the long press: the short command, as one tap.
            start(press, press.short, host.now(), repeat = false)
        }
        endKey(press, canceled)
    }

    /** Lets go of everything (focus lost); held keys end as canceled. */
    fun releaseAll() = releaseWhere { true }

    /** Lets go of what [deviceId] holds (controller disconnected). */
    fun releaseDevice(deviceId: Int) = releaseWhere { it.deviceId == deviceId }

    private fun releaseWhere(predicate: (Press) -> Boolean) {
        val matching = presses.filterValues(predicate)
        matching.keys.forEach(presses::remove)
        matching.values.forEach { press ->
            press.cancel?.invoke()
            press.cancel = null
            endKey(press, canceled = true)
        }
    }

    private fun start(press: Press, command: GamepadCommand, time: Long, repeat: Boolean = true) {
        when (command) {
            is GamepadCommand.Key -> {
                val code = command.command.keyCode
                press.emittedCode = code
                press.emittedDownTime = time
                press.repeatCount = 0
                host.emitKey(gamepadKeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, press.deviceId))
                if (repeat) scheduleRepeat(press, KEY_REPEAT_TIMEOUT_MS)
            }
            is GamepadCommand.Action -> host.perform(command.action)
            is GamepadCommand.Volume -> host.volume(command.command)
            GamepadCommand.None, GamepadCommand.Inherit -> Unit
        }
    }

    private fun scheduleRepeat(press: Press, delayMs: Long) {
        press.cancel = host.schedule(delayMs) {
            val code = press.emittedCode ?: return@schedule
            if (press !in presses.values) return@schedule
            press.repeatCount++
            host.emitKey(gamepadKeyEvent(press.emittedDownTime, host.now(), KeyEvent.ACTION_DOWN, code,
                press.repeatCount, press.deviceId))
            scheduleRepeat(press, KEY_REPEAT_INTERVAL_MS)
        }
    }

    private fun endKey(press: Press, canceled: Boolean) {
        val code = press.emittedCode ?: return
        press.emittedCode = null
        host.emitKey(gamepadKeyEvent(press.emittedDownTime, host.now(), KeyEvent.ACTION_UP, code, 0,
            press.deviceId, canceled))
    }

    private fun identity(deviceId: Int, button: GamepadButton): Long =
        (deviceId.toLong() shl 32) xor (button.keyCode.toLong() and 0xffffffffL)
}

/** A key the controller layer sends in place of a button or stick; keeps the controller's device id. */
internal fun gamepadKeyEvent(
    downTime: Long,
    eventTime: Long,
    action: Int,
    keyCode: Int,
    repeat: Int,
    deviceId: Int,
    canceled: Boolean = false,
): KeyEvent {
    var flags = 0
    if (canceled) flags = flags or KeyEvent.FLAG_CANCELED
    if (action == KeyEvent.ACTION_DOWN && repeat == 1) flags = flags or KeyEvent.FLAG_LONG_PRESS
    val source = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> InputDevice.SOURCE_DPAD
        else -> InputDevice.SOURCE_KEYBOARD
    }
    return KeyEvent(downTime, eventTime, action, keyCode, repeat, 0, deviceId, 0, flags, source)
}

package com.ambient.tv.ui.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Entry point for controller input, called by the activity before anything else sees a key or
 * motion: a game in front gets raw input ([GamepadInput.rawSink]); otherwise buttons, triggers,
 * sticks and the hat become the app's keys and actions. Disabled, every event takes its normal
 * way (Android's own controller fallbacks).
 */
class GamepadController(
    private val host: GamepadHost,
    private val config: () -> GamepadConfig = { GamepadInput.config },
    private val onButton: (GamepadButton) -> Unit = { GamepadInput.lastButton.value = it },
    private val rawSink: () -> ((android.view.InputEvent) -> Boolean)? = { GamepadInput.rawSink },
    private val capture: () -> ((KeyEvent) -> Boolean)? = { GamepadInput.capture },
    private val axisLayout: (deviceId: Int) -> GamepadAxisLayout = { GamepadAxisLayout.of(InputDevice.getDevice(it)) },
) {
    private val layouts = mutableMapOf<Int, GamepadAxisLayout>()

    private val buttons = GamepadButtonDispatcher(host, config)
    private val sticks = GamepadStickNavigator(host, config)

    /** Controllers that send L2/R2 as keys: their trigger axes are then ignored. */
    private val triggerKeyDevices = mutableSetOf<Int>()
    private val axisTriggers = mutableSetOf<Pair<Int, GamepadButton>>()
    private val swallowedTriggerKeys = mutableSetOf<Pair<Int, Int>>()

    /**
     * True when the key has been handled: recorded by Settings, taken by a game in front (any key:
     * controllers, keyboards and remotes all play), or a translated controller button.
     */
    fun handleKey(event: KeyEvent): Boolean {
        capture()?.let { sink -> if (sink(event)) return true }
        rawSink()?.let { sink -> if (sink(event)) return true }
        val code = event.keyCode
        if (!isGamepadButtonKeyCode(code)) return false
        if (!config().enabled) return false
        val button = GamepadButton.fromKeyCode(code) ?: return false
        if (button == GamepadButton.L2 || button == GamepadButton.R2) {
            val identity = event.deviceId to code
            triggerKeyDevices += event.deviceId
            // The same pull already arrived as an axis: finish it there, drop the duplicate key.
            if (event.action == KeyEvent.ACTION_DOWN && (event.deviceId to button) in axisTriggers) {
                swallowedTriggerKeys += identity
                return true
            }
            if (identity in swallowedTriggerKeys) {
                if (event.action == KeyEvent.ACTION_UP) swallowedTriggerKeys -= identity
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onButton(button)
        return buttons.handle(event)
    }

    /** True when the motion was controller stick/hat/trigger movement and has been handled. */
    fun handleMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        rawSink()?.let { sink -> if (sink(event)) return true }
        if (!config().enabled) return false
        val deviceId = event.deviceId
        val time = event.eventTime
        val layout = layouts.getOrPut(deviceId) { axisLayout(deviceId) }
        // Once a controller sends trigger keys, its axes only finish a pull they started.
        if (deviceId !in triggerKeyDevices || (deviceId to GamepadButton.L2) in axisTriggers) trigger(deviceId, GamepadButton.L2,
            layout.leftTriggerValue(event), time)
        if (deviceId !in triggerKeyDevices || (deviceId to GamepadButton.R2) in axisTriggers) trigger(deviceId, GamepadButton.R2,
            layout.rightTriggerValue(event), time)
        sticks.update(deviceId, GamepadStickNavigator.Axes(
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            leftX = event.getAxisValue(MotionEvent.AXIS_X),
            leftY = event.getAxisValue(MotionEvent.AXIS_Y),
            rightX = layout.rightStickX(event),
            rightY = layout.rightStickY(event),
        ), time)
        // Consumed, so Android does not turn the same movement into arrow keys a second time.
        return true
    }

    internal fun trigger(deviceId: Int, button: GamepadButton, value: Float, time: Long) {
        val identity = deviceId to button
        val pressed = identity in axisTriggers
        if (!pressed && value >= TRIGGER_PRESS) {
            axisTriggers += identity
            onButton(button)
            buttons.press(button, deviceId, time)
        } else if (pressed && value <= TRIGGER_RELEASE) {
            axisTriggers -= identity
            buttons.release(button, deviceId)
        }
    }

    /** Ends every held press, e.g. when the window loses focus. */
    fun releaseAll() {
        buttons.releaseAll()
        sticks.releaseAll()
        axisTriggers.clear()
        swallowedTriggerKeys.clear()
    }

    /** A controller went away: forget what it held and how it reports triggers. */
    fun deviceRemoved(deviceId: Int) {
        sticks.release(deviceId)
        buttons.releaseDevice(deviceId)
        layouts -= deviceId
        triggerKeyDevices -= deviceId
        axisTriggers.removeAll { it.first == deviceId }
        swallowedTriggerKeys.removeAll { it.first == deviceId }
    }

    private companion object {
        const val TRIGGER_PRESS = 0.5f
        const val TRIGGER_RELEASE = 0.3f
    }
}

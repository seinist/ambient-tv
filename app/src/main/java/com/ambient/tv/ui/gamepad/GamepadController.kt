package com.ambient.tv.ui.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Entry point for controller input, called by the activity before anything else sees a key or
 * motion: a game in front gets raw input ([GamepadInput.rawSink]); otherwise buttons, triggers,
 * sticks and the hat become the app's keys and actions. Game menus use a safe navigation layer
 * even when the app-wide controller mapper is disabled. Outside games, disabling it retains
 * Android's normal controller fallbacks.
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
    private var gameGeneration = host.gameInputGeneration
    private var gameMode = host.gameInputMode
    private val transitions = host.gameInputTransitions ?: GameInputTransitions()

    /** Called by the active-game context observer, also checked before every input event. */
    fun notifyGameInputChanged() {
        val mode = host.gameInputMode
        val generation = host.gameInputGeneration
        if (gameMode == mode && gameGeneration == generation) return
        transitions.advance(generation, mode != null)
        gameMode = mode
        gameGeneration = generation
        releaseAll()
    }

    /**
     * True when the key has been handled: recorded by Settings, taken by a game in front (any key:
     * controllers, keyboards and remotes all play), or a translated controller button.
     */
    fun handleKey(event: KeyEvent, observePointer: Boolean = true): Boolean {
        if (observePointer) com.ambient.tv.ui.input.PointerVisibility.onKeyEvent(event)
        notifyGameInputChanged()
        val blocked = transitions.key(event)
        capture()?.let { sink -> if (sink(event)) return true }
        if (blocked) return true
        if (gameMode == null || gameMode == GameInputMode.RUNNING) {
            rawSink()?.let { sink -> if (sink(event)) return true }
        }
        val code = event.keyCode
        if (gameMode == GameInputMode.BLOCKED && gameTracksKey(code) &&
            code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_ESCAPE) return true
        if (!isGamepadButtonKeyCode(code)) return false
        if (!config().enabled && gameMode == null) return false
        val button = GamepadButton.fromKeyCode(code) ?: return gameMode != null
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
        notifyGameInputChanged()
        val deviceId = event.deviceId
        val time = event.eventTime
        val layout = layouts.getOrPut(deviceId) { axisLayout(deviceId) }
        val cfg = config()
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val leftX = event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        val rightX = layout.rightStickX(event)
        val rightY = layout.rightStickY(event)
        val leftTrigger = layout.leftTriggerValue(event)
        val rightTrigger = layout.rightTriggerValue(event)
        val moving = kotlin.math.abs(hatX) >= 0.5f || kotlin.math.abs(hatY) >= 0.5f ||
            kotlin.math.abs(leftX) > cfg.deadzone.release || kotlin.math.abs(leftY) > cfg.deadzone.release ||
            kotlin.math.abs(rightX) > cfg.deadzone.release || kotlin.math.abs(rightY) > cfg.deadzone.release ||
            leftTrigger >= TRIGGER_RELEASE || rightTrigger >= TRIGGER_RELEASE
        if (moving) com.ambient.tv.ui.input.PointerVisibility.onNonPointerInput()
        if (transitions.motion(deviceId, moving)) return true
        if (gameMode == null || gameMode == GameInputMode.RUNNING) {
            rawSink()?.let { sink -> if (sink(event)) return true }
        }
        if (gameMode == GameInputMode.BLOCKED || gameMode == GameInputMode.RUNNING) return true
        if (!cfg.enabled && gameMode == null) return false
        // Once a controller sends trigger keys, its axes only finish a pull they started.
        if (deviceId !in triggerKeyDevices || (deviceId to GamepadButton.L2) in axisTriggers) trigger(deviceId, GamepadButton.L2,
            leftTrigger, time)
        if (deviceId !in triggerKeyDevices || (deviceId to GamepadButton.R2) in axisTriggers) trigger(deviceId, GamepadButton.R2,
            rightTrigger, time)
        sticks.update(deviceId, GamepadStickNavigator.Axes(
            hatX = hatX, hatY = hatY, leftX = leftX, leftY = leftY, rightX = rightX, rightY = rightY,
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
        transitions.removeDevice(deviceId)
    }

    private companion object {
        const val TRIGGER_PRESS = 0.5f
        const val TRIGGER_RELEASE = 0.3f
    }
}

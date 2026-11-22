package com.ambient.tv.ui.gamepad

import android.view.KeyEvent
import kotlin.math.abs

/**
 * Sticks and the D-pad hat as keys: the hat and the left stick move the focus like the remote's
 * arrows, the right stick pages (up/down) and winds in the player (left/right). A leaned stick
 * repeats with [GamepadRepeatSpeed] and speeds up while it stays leaned.
 */
class GamepadStickNavigator(
    private val host: GamepadHost,
    private val config: () -> GamepadConfig,
) {
    enum class Channel { HAT, LEFT, RIGHT }

    /** Current axis values of one controller, already read from the event. */
    data class Axes(
        val hatX: Float = 0f,
        val hatY: Float = 0f,
        val leftX: Float = 0f,
        val leftY: Float = 0f,
        val rightX: Float = 0f,
        val rightY: Float = 0f,
    )

    private class Held(val keyCode: Int, val deviceId: Int, val downTime: Long,
                       val generation: Long, val mode: GameInputMode?) {
        var repeatCount = 0
        var cancel: (() -> Unit)? = null
    }

    private val held = mutableMapOf<Pair<Int, Channel>, Held>()

    fun update(deviceId: Int, axes: Axes, time: Long) {
        val cfg = config()
        val hat = direction(axes.hatX, axes.hatY, HAT_THRESHOLD)
        set(deviceId, Channel.HAT, hat?.let(::dpadKey), time)

        val left = if (cfg.leftStickNavigates) {
            val threshold = if (held.containsKey(deviceId to Channel.LEFT)) cfg.deadzone.release else cfg.deadzone.activation
            direction(axes.leftX, axes.leftY, threshold)
        } else null
        set(deviceId, Channel.LEFT, left?.let(::dpadKey), time)

        val right = if (cfg.rightStickScrolls) {
            val threshold = if (held.containsKey(deviceId to Channel.RIGHT)) cfg.deadzone.release else cfg.deadzone.activation
            direction(axes.rightX, axes.rightY, threshold)
        } else null
        set(deviceId, Channel.RIGHT, right?.let(::rightStickKey), time)
    }

    fun releaseAll() {
        val all = held.values.toList()
        held.clear()
        all.forEach { end(it, canceled = true) }
    }

    /** Releases what [deviceId] holds, e.g. when it disconnects. */
    fun release(deviceId: Int) {
        held.keys.filter { it.first == deviceId }.forEach { key -> held.remove(key)?.let { end(it, canceled = true) } }
    }

    private fun set(deviceId: Int, channel: Channel, keyCode: Int?, time: Long) {
        val key = deviceId to channel
        val current = held[key]
        if (current?.keyCode == keyCode) return
        if (current != null) {
            held.remove(key)
            end(current, canceled = false)
        }
        if (keyCode == null) return
        val press = Held(keyCode, deviceId, time, host.gameInputGeneration, host.gameInputMode)
        held[key] = press
        host.emitKey(gamepadKeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, deviceId))
        if (held[key] === press) scheduleRepeat(key, press, config().repeat.initialDelayMs)
    }

    private fun scheduleRepeat(key: Pair<Int, Channel>, press: Held, delayMs: Long) {
        press.cancel = host.schedule(delayMs) {
            if (held[key] !== press) return@schedule
            if (press.generation != host.gameInputGeneration || press.mode != host.gameInputMode) {
                held.remove(key)
                end(press, canceled = true)
                return@schedule
            }
            press.repeatCount++
            val now = host.now()
            host.emitKey(gamepadKeyEvent(press.downTime, now, KeyEvent.ACTION_DOWN, press.keyCode, press.repeatCount, press.deviceId))
            val speed = config().repeat
            val next = if (now - press.downTime >= GamepadRepeatSpeed.ACCELERATE_AFTER_MS) speed.fastIntervalMs else speed.intervalMs
            scheduleRepeat(key, press, next)
        }
    }

    private fun end(press: Held, canceled: Boolean) {
        press.cancel?.invoke()
        press.cancel = null
        host.emitKey(gamepadKeyEvent(press.downTime, host.now(), KeyEvent.ACTION_UP, press.keyCode, 0, press.deviceId, canceled))
    }

    private enum class Direction { UP, DOWN, LEFT, RIGHT }

    /** The dominant axis once it leans past [threshold]; diagonals pick the stronger axis. */
    private fun direction(x: Float, y: Float, threshold: Float): Direction? {
        val ax = abs(x)
        val ay = abs(y)
        if (maxOf(ax, ay) < threshold) return null
        return if (ay >= ax) {
            if (y < 0) Direction.UP else Direction.DOWN
        } else {
            if (x < 0) Direction.LEFT else Direction.RIGHT
        }
    }

    private fun dpadKey(direction: Direction) = when (direction) {
        Direction.UP -> KeyEvent.KEYCODE_DPAD_UP
        Direction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        Direction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        Direction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
    }

    private fun rightStickKey(direction: Direction): Int? = when (direction) {
        Direction.UP -> KeyEvent.KEYCODE_PAGE_UP
        Direction.DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        // Winding only means something in the player; in menus left/right stay unused.
        Direction.LEFT -> KeyEvent.KEYCODE_MEDIA_REWIND.takeIf { host.playerForeground && host.gameInputMode == null }
        Direction.RIGHT -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD.takeIf { host.playerForeground && host.gameInputMode == null }
    }

    private companion object {
        const val HAT_THRESHOLD = 0.5f
    }
}

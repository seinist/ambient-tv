package com.ambient.tv.ui.common

internal enum class ScreensaverInputResult { PASS, CONSUME, DISMISS }
internal enum class ScreensaverInputPhase { DOWN, UP, CANCEL, OTHER }

/** The activating gesture must finish before a *new* gesture can wake the app.
 * Keep swallowing the wake gesture through KeyUp/TouchUp, even after the fade is gone. */
internal class ScreensaverInputGate {
    private val pressedKeys = mutableSetOf<Long>()
    private val swallowedKeys = mutableSetOf<Long>()
    private var touchPressed = false
    private var swallowTouch = false

    fun key(
        key: Long, phase: ScreensaverInputPhase, repeatCount: Int,
        active: Boolean, overlayPresent: Boolean,
    ): ScreensaverInputResult {
        val wasPressed = key in pressedKeys
        if (phase == ScreensaverInputPhase.DOWN) pressedKeys.add(key)
        if (phase == ScreensaverInputPhase.UP || phase == ScreensaverInputPhase.CANCEL) pressedKeys.remove(key)
        if (key in swallowedKeys) {
            if (phase == ScreensaverInputPhase.UP || phase == ScreensaverInputPhase.CANCEL) swallowedKeys.remove(key)
            return ScreensaverInputResult.CONSUME
        }
        if (!active && !overlayPresent) return ScreensaverInputResult.PASS
        swallowedKeys.addAll(pressedKeys)
        return if (active && phase == ScreensaverInputPhase.DOWN && !wasPressed && repeatCount == 0) {
            ScreensaverInputResult.DISMISS
        } else ScreensaverInputResult.CONSUME
    }

    fun touch(phase: ScreensaverInputPhase, active: Boolean, overlayPresent: Boolean): ScreensaverInputResult {
        val wasPressed = touchPressed
        if (phase == ScreensaverInputPhase.DOWN) touchPressed = true
        val finished = phase == ScreensaverInputPhase.UP || phase == ScreensaverInputPhase.CANCEL
        if (finished) touchPressed = false
        if (swallowTouch) {
            if (finished) swallowTouch = false
            return ScreensaverInputResult.CONSUME
        }
        if (!active && !overlayPresent) return ScreensaverInputResult.PASS
        swallowTouch = !finished
        return if (active && phase == ScreensaverInputPhase.DOWN && !wasPressed) ScreensaverInputResult.DISMISS
        else ScreensaverInputResult.CONSUME
    }

    fun reset() { pressedKeys.clear(); swallowedKeys.clear(); touchPressed = false; swallowTouch = false }
}

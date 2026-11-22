package com.ambient.tv.ui.gamepad

import android.view.KeyEvent

/**
 * Physical input shared by a game's activity and dialog windows. A key/stick held in a dialog
 * must not turn into gameplay when that dialog resumes the game. Main thread only.
 */
class GameInputTransitions {
    private var generation = 0L
    private var active = false
    private val heldKeys = mutableMapOf<Pair<Int, Int>, Long>()
    private val blockedKeys = mutableMapOf<Pair<Int, Int>, Long>()
    private val movingDevices = mutableSetOf<Int>()
    private val blockedMotionDevices = mutableSetOf<Int>()

    internal fun advance(nextGeneration: Long, nextActive: Boolean) {
        if (generation == nextGeneration && active == nextActive) return
        if (active || nextActive) {
            blockedKeys.putAll(heldKeys)
            blockedMotionDevices += movingDevices
        }
        generation = nextGeneration
        active = nextActive
    }

    internal fun key(event: KeyEvent): Boolean {
        if (!gameTracksKey(event.keyCode)) return false
        val identity = event.deviceId to event.keyCode
        // Some devices omit key-up while backgrounded. A genuinely new press must still work.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            blockedKeys[identity]?.let { it != event.downTime } == true) blockedKeys.remove(identity)
        val blocked = identity in blockedKeys
        when (event.action) {
            KeyEvent.ACTION_DOWN -> heldKeys[identity] = event.downTime
            KeyEvent.ACTION_UP -> { heldKeys.remove(identity); blockedKeys.remove(identity) }
        }
        return blocked
    }

    internal fun motion(deviceId: Int, moving: Boolean): Boolean {
        if (moving) movingDevices += deviceId else movingDevices -= deviceId
        val blocked = deviceId in blockedMotionDevices
        if (!moving) blockedMotionDevices -= deviceId
        return blocked
    }

    internal fun removeDevice(deviceId: Int) {
        heldKeys.keys.removeAll { it.first == deviceId }
        blockedKeys.keys.removeAll { it.first == deviceId }
        movingDevices -= deviceId
        blockedMotionDevices -= deviceId
    }
}

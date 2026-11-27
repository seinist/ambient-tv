package com.ambient.tv.ui.common.projectm

/**
 * Render-thread-only controller for AUTO resolution. Frame-delivery cadence, not CPU wall
 * time alone, also reflects swap/backpressure. Change only after sustained pressure;
 * never change manual quality or FPS and never catch up after a pause.
 */
internal class ProjectMAdaptiveQuality {
    var level: Int = 0
        private set
    private var last = 0L
    private var windowStart = 0L
    private var frames = 0
    private var slow = 0
    private var good = 0
    private var overloaded = 0
    private var stable = 0
    private var lastTarget = 0

    fun resetTiming() {
        last = 0
        windowStart = 0
        frames = 0
        slow = 0
        good = 0
        overloaded = 0
        stable = 0
    }

    fun observe(now: Long, fps: Int, enabled: Boolean): Int? {
        if (!enabled) {
            val changed = level != 0
            level = 0
            resetTiming()
            return if (changed) 0 else null
        }
        if (fps != lastTarget) {
            lastTarget = fps
            resetTiming()
        }
        val period = 1_000_000_000L / fps.coerceAtLeast(1)
        if (last == 0L || now <= last || now - last > 250_000_000L) {
            resetTiming()
            last = now
            windowStart = now
            return null
        }
        val elapsed = now - last
        last = now
        frames++
        if (elapsed > period * 14 / 10) slow++
        if (elapsed <= period * 14 / 10) good++
        if (now - windowStart < 2_000_000_000L || frames < 12) return null

        if (slow * 4 > frames) {
            overloaded++
            stable = 0
        } else if (good * 10 >= frames * 9) {
            stable++
            overloaded = 0
        } else {
            stable = 0
            overloaded = 0
        }
        windowStart = now
        frames = 0
        slow = 0
        good = 0
        val next = when {
            overloaded >= 2 && level < 2 -> level + 1
            stable >= 5 && level > 0 -> level - 1
            else -> level
        }
        if (next == level) return null
        level = next
        overloaded = 0
        stable = 0
        return level
    }

    companion object {
        fun capLines(level: Int): Int = when (level) {
            1 -> 900
            2 -> 720
            else -> 1080
        }
    }
}

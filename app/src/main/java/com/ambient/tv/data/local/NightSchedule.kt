package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

/**
 * Night mode by the clock: inside the window every player turns night mode (dynamic range
 * compression) on and lifts dialogue to at least [dialogue]. Outside it the profile applies.
 */
@Serializable
data class NightSchedule(
    val enabled: Boolean = false,
    /** Minutes after midnight; the window may wrap past midnight (22:00 → 07:00). */
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val dialogue: Int = 2,
) {
    fun normalized() = copy(
        startMinute = startMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        endMinute = endMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        dialogue = dialogue.coerceIn(0, 6),
    )

    /** True inside [startMinute, endMinute); an empty window (start == end) is never active. */
    fun activeAt(minuteOfDay: Int): Boolean {
        if (!enabled || startMinute == endMinute) return false
        val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return if (startMinute < endMinute) m in startMinute until endMinute else m >= startMinute || m < endMinute
    }

    /** [options] with night mode on and dialogue lifted, as the window asks. */
    fun applyTo(options: AudioOptions): AudioOptions =
        options.copy(night = true, dialogue = maxOf(options.dialogue, dialogue))

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

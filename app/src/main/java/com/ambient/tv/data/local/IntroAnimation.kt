package com.ambient.tv.data.local

/** Cold-start intro drawn in the theme accents; [OFF] hands over straight from the system splash. */
object IntroAnimation {
    const val OFF = "off"
    const val POWER_ON = "power_on"
    const val BLOOM = "bloom"
    const val SIGNAL = "signal"
    const val SHIMMER = "shimmer"
    const val RIPPLE = "ripple"
    const val DEFAULT = BLOOM

    val keys = listOf(OFF, POWER_ON, BLOOM, SIGNAL, SHIMMER, RIPPLE)

    fun normalize(key: String?): String = key?.takeIf { it in keys } ?: DEFAULT

    fun durationMs(key: String): Int = when (normalize(key)) {
        POWER_ON -> 1000
        SIGNAL -> 700
        SHIMMER -> 1000
        RIPPLE -> 1100
        OFF -> 0
        else -> 1200
    }
}

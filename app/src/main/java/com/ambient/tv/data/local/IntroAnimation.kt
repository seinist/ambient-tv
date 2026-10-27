package com.ambient.tv.data.local

/**
 * Cold-start intro drawn in the theme accents around the brand wordmark. [OFF] hands over straight
 * from the system splash. [durationMs] is the one-shot opening. The light then breathes until the
 * intro is dismissed. Sparsam draws the settled frame without motion.
 */
object IntroAnimation {
    const val OFF = "off"
    /** Light field; the key predates the rename and stays so saved choices keep working. */
    const val BLOOM = "bloom"
    const val HORIZON = "horizon"
    const val DEFAULT = BLOOM

    val keys = listOf(OFF, BLOOM, HORIZON)

    /** Retired styles (power_on, signal, shimmer, ripple, word) fall back to the default. */
    fun normalize(key: String?): String = key?.takeIf { it in keys } ?: DEFAULT

    fun durationMs(key: String): Int = when (normalize(key)) {
        HORIZON -> 5000
        OFF -> 0
        else -> 5200
    }
}

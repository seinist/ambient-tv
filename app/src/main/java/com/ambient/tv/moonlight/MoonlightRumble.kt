package com.ambient.tv.moonlight

/** Sunshine motor values are unsigned 16-bit, port is 0..3. */
data class MoonlightRumble(val port: Int, val low: Int, val high: Int, val generation: Long = 0L)

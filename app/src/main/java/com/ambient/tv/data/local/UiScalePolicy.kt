package com.ambient.tv.data.local

/** Shared bounds for persistence, settings preview and rendering. */
object UiScalePolicy {
    val percentRange = 50..150

    fun normalize(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0.5f, 1.5f) else 1f

    fun initialScale(isTelevision: Boolean, width: Int, height: Int): Float {
        if (!isTelevision || width <= 0 || height <= 0) return 1f
        val shortSide = minOf(width, height)
        val longSide = maxOf(width, height)
        return when {
            longSide >= 3840 && shortSide >= 2160 -> 0.7f
            longSide >= 2560 && shortSide >= 1440 -> 0.9f
            else -> 1f
        }
    }
}

/** Bounds for the overscan inset; 0 = edge to edge. */
object OverscanPolicy {
    val percentRange = 0..10

    fun normalize(value: Int): Int = value.coerceIn(percentRange.first, percentRange.last)
}

package com.ambient.tv.ui.common

/**
 * Horizontal scroll of the handheld player action bar. The bar leaves composition whenever the
 * overlay hides, so its scroll position lives here for as long as one playback session lasts;
 * a different session starts at the beginning again.
 */
internal object PlayerControlBarScroll {
    private var session: Any? = null
    private val offsets = HashMap<String, Int>()

    @Synchronized fun offset(session: Any?, bar: String): Int {
        if (session != this.session) {
            this.session = session
            offsets.clear()
        }
        return offsets[bar] ?: 0
    }

    @Synchronized fun save(session: Any?, bar: String, offset: Int) {
        if (session != this.session) {
            this.session = session
            offsets.clear()
        }
        offsets[bar] = offset.coerceAtLeast(0)
    }
}

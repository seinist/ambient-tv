package com.ambient.tv.ui.player

import android.app.Instrumentation

/** Checks moving fixture pixels in the video region; an advancing audio clock is insufficient. */
internal fun assertPlaybackVideoMoves(instrumentation: Instrumentation, label: String) {
    repeat(12) {
        val first = instrumentation.uiAutomation.takeScreenshot() ?: return@repeat
        try {
            Thread.sleep(700)
            val second = instrumentation.uiAutomation.takeScreenshot() ?: return@repeat
            try {
                if (first.width != second.width || first.height != second.height) return@repeat
                var changed = 0
                val colors = mutableSetOf<Int>()
                // Exclude the OSD's top title and bottom timeline so their clocks cannot pass.
                for (y in 0..23) for (x in 0..31) {
                    val px = first.width * (20 + x * 60 / 31) / 100
                    val py = first.height * (20 + y * 40 / 23) / 100
                    val a = first.getPixel(px, py) and 0xffffff
                    val b = second.getPixel(px, py) and 0xffffff
                    colors += a
                    if (a != b) changed++
                }
                if (colors.size > 16 && changed > 8) return
            } finally { second.recycle() }
        } finally { first.recycle() }
    }
    error("$label: fixture video is frozen or absent despite an advancing playback clock")
}

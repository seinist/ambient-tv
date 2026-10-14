package com.ambient.tv.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisplayVersionSelector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    fun selectIndex(versions: List<MediaVersion>, requestedIndex: Int): Int {
        if (versions.isEmpty()) return requestedIndex
        if (!preferences.autoSelectDisplayVersion) return requestedIndex.coerceIn(0, versions.lastIndex)

        val target = currentDisplaySize() ?: return requestedIndex.coerceIn(0, versions.lastIndex)
        return MediaVersionSelection.selectIndex(
            versions = versions,
            requestedIndex = requestedIndex,
            targetWidth = target.first,
            targetHeight = target.second,
            dimensionTolerance = DIMENSION_TOLERANCE,
        )
    }

    fun displayLabel(): String? = currentDisplaySize()?.let { (w, h) -> "${w}x${h}" }

    private fun currentDisplaySize(): Pair<Int, Int>? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode
        if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
            return normalize(mode.physicalWidth, mode.physicalHeight)
        }
        return null
    }

    private fun normalize(width: Int, height: Int): Pair<Int, Int> =
        if (width >= height) width to height else height to width

    private companion object {
        const val DIMENSION_TOLERANCE = 32
    }
}

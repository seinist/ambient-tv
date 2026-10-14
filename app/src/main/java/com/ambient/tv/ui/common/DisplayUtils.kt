package com.ambient.tv.ui.common

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.Composable

/** The display's native resolution in pixels, or [Long.MAX_VALUE] if it can't be determined
 * (callers then treat "no constraint" as "pick the highest available"). */
fun maxDisplayPixels(context: Context): Long {
    val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    display?.getRealMetrics(metrics)
    val pixels = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
    return if (pixels > 0) pixels else Long.MAX_VALUE
}

/** Legacy compatibility helper. Global UI scaling now happens once at the theme/density level,
 * so individual components must not apply an additional UHD multiplier. */
@Composable
fun rememberUhdScale(): Float = 1f

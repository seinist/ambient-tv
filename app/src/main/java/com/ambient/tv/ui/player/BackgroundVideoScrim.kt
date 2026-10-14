package com.ambient.tv.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Cheap overlays above the existing HDR surface. No blur, readback or decoder recreation. */
@Composable
fun BackgroundVideoScrim(visible: Boolean, dimPercent: Int) {
    val opacity by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "background video scrim")
    val dim by animateFloatAsState(dimPercent.coerceIn(60, 90) / 100f, tween(220), label = "background video dim")
    Canvas(Modifier.fillMaxSize()) {
        if (opacity > 0f) {
            drawRect(Brush.horizontalGradient(listOf(
                Color.Black.copy(alpha = maxOf(.90f, dim) * opacity),
                Color.Black.copy(alpha = dim * opacity),
                Color.Black.copy(alpha = dim * opacity),
            )))
            drawRect(Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = .15f * opacity), Color.Transparent,
                Color.Black.copy(alpha = .35f * opacity),
            )))
        }
    }
}

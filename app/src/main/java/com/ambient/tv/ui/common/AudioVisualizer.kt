package com.ambient.tv.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.backend.AudioSpectrumAnalyzer
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Audio-reactive spectrum for PCM-backed players. The bars are driven by real decoded samples
 * supplied by [AudioSpectrumAnalyzer]; no synthetic beat/sine animation is used here.
 *
 * VLC and MPV use their native spectrum renderers because those backends don't expose decoded
 * PCM through their Kotlin player APIs. This Compose visualizer is therefore primarily used by
 * Media3, where a TeeAudioProcessor feeds the analyser directly from the playback pipeline.
 */
@Composable
fun AudioPlaybackVisualizer(
    modifier: Modifier = Modifier,
    analyzer: AudioSpectrumAnalyzer? = null,
) {
    val performance = LocalPerformanceProfile.current
    val pollMs = if (performance.throttleSpectrumVisualizer) 100L else 42L
    var bands by remember(analyzer) { mutableStateOf(FloatArray(AudioSpectrumAnalyzer.BAND_COUNT)) }
    DisposableEffect(analyzer) {
        analyzer?.addSpectrumConsumer()
        onDispose { analyzer?.removeSpectrumConsumer() }
    }
    LaunchedEffect(analyzer, pollMs) {
        while (true) {
            bands = analyzer?.snapshot() ?: FloatArray(AudioSpectrumAnalyzer.BAND_COUNT)
            delay(pollMs)
        }
    }

    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF090B10), Color.Black)))
        val count = bands.size.coerceAtLeast(1)
        val gap = size.width * 0.0055f
        val usableWidth = size.width * 0.66f
        val barW = ((usableWidth - gap * (count - 1)) / count).coerceAtLeast(1f)
        val startX = (size.width - usableWidth) / 2f
        val baseline = size.height * 0.68f
        val maxH = size.height * 0.34f

        repeat(count) { i ->
            // Keep a tiny floor so silence still has a deliberate visual baseline, but all
            // meaningful movement above it comes directly from analysed playback samples.
            val level = max(0.025f, bands[i].coerceIn(0f, 1f))
            val h = maxH * level
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f + level * 0.58f),
                topLeft = Offset(startX + i * (barW + gap), baseline - h),
                size = Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.28f, barW * 0.28f),
            )
        }
    }
}

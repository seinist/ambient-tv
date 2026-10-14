package com.ambient.tv.ui.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.ambient.tv.data.local.HomeHeroStyle
import kotlin.math.PI
import kotlin.math.sin

/** Draw-only motion in the hero's own layer: no bitmap reloads, blur surfaces or row recomposition. */
internal fun Modifier.fancyHeroAmbient(
    style: String,
    artworkAccent: () -> Color,
    secondary: Color,
    phase: () -> Float,
    reveal: () -> Float,
    motionStrength: Float,
    intensityScale: Float,
    radiusScale: Float,
): Modifier = drawWithCache {
    val color = artworkAccent()
    val strength = intensityScale.coerceIn(0f, 100f / 60f) * motionStrength
    val width = size.width
    val height = size.height
    val spread = radiusScale.coerceIn(0.72f, 1.32f)
    val cool = lerp(color, Color(0xFF79AFFF), 0.55f)
    val warm = lerp(color, Color(0xFFFFD394), 0.65f)
    val ribbons = List(3) { index ->
        val tint = when (index) { 0 -> color; 1 -> cool; else -> lerp(color, secondary, 0.5f) }
        Brush.verticalGradient(listOf(Color.Transparent, tint.copy(alpha = 0.30f * strength), Color.Transparent),
            startY = -height * 0.30f * spread, endY = height * 0.38f * spread)
    }
    val ribbon = Path()
    val beamWidth = width * 0.14f * spread
    val beam = Brush.horizontalGradient(
        listOf(Color.Transparent, warm.copy(alpha = 0.22f * strength), Color.Transparent),
        startX = -beamWidth, endX = beamWidth,
    )
    val prismWidth = width * 0.13f * spread
    val prism = Brush.horizontalGradient(
        listOf(Color.Transparent, cool.copy(alpha = 0.16f * strength),
            Color.White.copy(alpha = 0.24f * strength), color.copy(alpha = 0.24f * strength), Color.Transparent),
        startX = -prismWidth, endX = prismWidth,
    )
    val wash = Brush.radialGradient(
        listOf(color.copy(alpha = 0.12f * strength), Color.Transparent),
        center = Offset(width * 0.72f, height * 0.15f), radius = width * 0.65f * spread,
    )
    onDrawBehind {
        if (strength > 0f && width > 0f && height > 0f) clipRect {
            drawRect(wash)
            val t = phase()
            when (style) {
                HomeHeroStyle.AURORA -> repeat(3) { index ->
                    val wave = sin((t * 2f + index * 0.65f) * PI).toFloat()
                    val bend = height * (0.12f + wave * 0.20f * motionStrength)
                    val center = height * (0.12f + index * 0.18f)
                    val thickness = height * 0.23f * spread
                    ribbon.reset()
                    ribbon.moveTo(-width * 0.1f, -thickness)
                    ribbon.cubicTo(width * 0.28f, bend - thickness, width * 0.65f, -bend - thickness, width * 1.1f, -thickness)
                    ribbon.lineTo(width * 1.1f, thickness)
                    ribbon.cubicTo(width * 0.65f, -bend + thickness, width * 0.28f, bend + thickness, -width * 0.1f, thickness)
                    ribbon.close()
                    withTransform({ translate(0f, center); rotate(-7f + index * 5f, Offset.Zero) }) {
                        drawPath(ribbon, ribbons[index])
                    }
                }
                HomeHeroStyle.CINEMA -> {
                    repeat(3) { index ->
                        val x = width * (0.40f + index * 0.25f + (t - 0.5f) * 0.14f * motionStrength)
                        withTransform({ translate(x, -height * 0.3f); rotate(24f + index * 7f, Offset.Zero) }) {
                            drawRect(beam, Offset(-beamWidth, 0f), Size(beamWidth * 2f, height * 1.8f))
                        }
                    }
                    // Fixed seeds keep particles stable across focus/metadata updates.
                    repeat(if (motionStrength < 0.5f) 9 else 18) { index ->
                        val seed = index * 0.618034f
                        val x = width * ((seed % 1f) + sin((t * 2f + index) * PI).toFloat() * 0.025f * motionStrength)
                        val y = height * (((index * 0.381966f) % 1f) * 0.8f + (t - 0.5f) * 0.10f * motionStrength)
                        val twinkle = 0.5f + 0.5f * sin((t * 2f + seed) * PI).toFloat()
                        drawCircle(warm.copy(alpha = (0.10f + twinkle * 0.18f) * strength),
                            radius = (0.8f + index % 3 * 0.45f) * density, center = Offset(x, y))
                    }
                }
                HomeHeroStyle.PRISM -> {
                    fun sweep(progress: Float, opacity: Float) {
                        withTransform({ translate(width * (-0.25f + progress * 1.6f), 0f); rotate(18f, Offset.Zero) }) {
                            drawRect(prism, Offset(-prismWidth, -height), Size(prismWidth * 2f, height * 3f), alpha = opacity)
                        }
                    }
                    sweep(t, 0.7f)
                    val opening = reveal()
                    if (opening < 1f) sweep(opening, sin(opening * PI).toFloat().coerceIn(0f, 1f))
                }
            }
        }
    }
}

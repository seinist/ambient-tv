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
import kotlin.math.cos
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
    focusTravel: () -> Float = { 0.42f },
): Modifier = drawWithCache {
    val color = artworkAccent()
    val strength = intensityScale.coerceIn(0f, 100f / 60f) * motionStrength
    val width = size.width
    val height = size.height
    val spread = radiusScale.coerceIn(0.72f, 1.32f)
    val cool = lerp(color, Color(0xFF79AFFF), 0.55f)
    val warm = lerp(color, Color(0xFFFFD394), 0.65f)
    val ribbons = List(4) { index ->
        val tint = when (index) {
            0 -> color
            1 -> cool
            2 -> lerp(color, secondary, 0.5f)
            else -> lerp(cool, warm, 0.35f)
        }
        Brush.verticalGradient(
            listOf(
                Color.Transparent,
                tint.copy(alpha = 0.04f * strength),
                tint.copy(alpha = 0.14f * strength),
                tint.copy(alpha = 0.05f * strength),
                Color.Transparent,
            ),
            startY = -height * 0.28f * spread,
            endY = height * 0.42f * spread,
        )
    }
    val ribbon = Path()
    val beamWidth = width * 0.14f * spread
    val beam = Brush.horizontalGradient(
        listOf(Color.Transparent, warm.copy(alpha = 0.12f * strength), Color.Transparent),
        startX = -beamWidth, endX = beamWidth,
    )
    val prismWidth = width * 0.13f * spread
    val prismWide = Brush.horizontalGradient(
        listOf(
            Color.Transparent,
            cool.copy(alpha = 0.05f * strength),
            color.copy(alpha = 0.07f * strength),
            Color.Transparent,
        ),
        startX = -prismWidth * 2.1f, endX = prismWidth * 2.1f,
    )
    val prismCore = Brush.horizontalGradient(
        listOf(
            Color.Transparent,
            Color.White.copy(alpha = 0.10f * strength),
            color.copy(alpha = 0.16f * strength),
            Color.Transparent,
        ),
        startX = -prismWidth * 0.42f, endX = prismWidth * 0.42f,
    )
    val wash = Brush.radialGradient(
        listOf(color.copy(alpha = 0.08f * strength), Color.Transparent),
        center = Offset(width * 0.72f, height * 0.15f), radius = width * 0.65f * spread,
    )
    val duskRadius = width * 0.72f * spread
    val duskWarm = Brush.radialGradient(
        listOf(warm.copy(alpha = 0.15f * strength), warm.copy(alpha = 0.05f * strength), Color.Transparent),
        center = Offset.Zero, radius = duskRadius,
    )
    val duskCool = Brush.radialGradient(
        listOf(cool.copy(alpha = 0.12f * strength), cool.copy(alpha = 0.04f * strength), Color.Transparent),
        center = Offset.Zero, radius = duskRadius * 0.92f,
    )
    val tideHalf = height * 0.22f * spread
    val tide = Brush.verticalGradient(
        listOf(
            Color.Transparent,
            color.copy(alpha = 0.04f * strength),
            color.copy(alpha = 0.11f * strength),
            lerp(color, secondary, 0.35f).copy(alpha = 0.05f * strength),
            Color.Transparent,
        ),
        startY = -tideHalf, endY = tideHalf,
    )
    val focusRadius = width * 0.46f * spread
    val focusBrush = Brush.radialGradient(
        listOf(color.copy(alpha = 0.15f * strength), color.copy(alpha = 0.05f * strength), Color.Transparent),
        center = Offset.Zero, radius = focusRadius,
    )
    onDrawBehind {
        if (strength > 0f && width > 0f && height > 0f) clipRect {
            val classic = style == HomeHeroStyle.AURORA || style == HomeHeroStyle.CINEMA || style == HomeHeroStyle.PRISM
            if (classic) drawRect(wash)
            val t = phase()
            when (style) {
                HomeHeroStyle.AURORA -> repeat(4) { index ->
                    val dir = if (index == 3) -1f else 1f
                    val wave = sin((t * 2f + index * 0.55f) * PI).toFloat()
                    val bend = dir * height * (0.12f + wave * 0.10f * motionStrength)
                    val centerY = height * (0.10f + index * 0.16f)
                    val thickness = height * 0.24f * spread
                    ribbon.reset()
                    ribbon.moveTo(-width * 0.1f, -thickness)
                    ribbon.cubicTo(width * 0.28f, bend - thickness, width * 0.65f, -bend - thickness, width * 1.1f, -thickness)
                    ribbon.lineTo(width * 1.1f, thickness)
                    ribbon.cubicTo(width * 0.65f, -bend + thickness, width * 0.28f, bend + thickness, -width * 0.1f, thickness)
                    ribbon.close()
                    withTransform({ translate(0f, centerY); rotate(-8f + index * 4.5f, Offset.Zero) }) {
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
                    val dots = if (motionStrength < 0.5f) 6 else 10
                    repeat(dots) { index ->
                        val seed = index * 0.618034f
                        val x = width * ((seed % 1f) + sin((t * 2f + index) * PI).toFloat() * 0.02f * motionStrength)
                        val y = height * (((index * 0.381966f) % 1f) * 0.8f + (t - 0.5f) * 0.10f * motionStrength)
                        val twinkle = 0.5f + 0.5f * sin((t * 2f + seed) * PI).toFloat()
                        val radius = (14f + index % 4 * 6f) * density * spread.coerceAtMost(1.15f)
                        drawCircle(warm.copy(alpha = 0.03f * strength * twinkle), radius * 2.2f, Offset(x, y))
                        drawCircle(warm.copy(alpha = (0.05f + twinkle * 0.06f) * strength), radius, Offset(x, y))
                    }
                }
                HomeHeroStyle.PRISM -> {
                    fun sweep(progress: Float, opacity: Float, halfWidth: Float, brush: Brush) {
                        withTransform({ translate(width * (-0.25f + progress * 1.6f), 0f); rotate(18f, Offset.Zero) }) {
                            drawRect(brush, Offset(-halfWidth, -height), Size(halfWidth * 2f, height * 3f), alpha = opacity)
                        }
                    }
                    sweep(t, 0.45f, prismWidth * 2.1f, prismWide)
                    sweep((t + 0.04f).coerceAtMost(1f), 0.7f, prismWidth * 0.42f, prismCore)
                    val opening = reveal()
                    if (opening < 1f) sweep(opening, sin(opening * PI).toFloat().coerceIn(0f, 1f), prismWidth * 0.42f, prismCore)
                }
                HomeHeroStyle.DUSK -> {
                    val angle = (t * 2f * PI).toFloat()
                    val travel = 0.14f * motionStrength
                    val warmAt = Offset(
                        width * (0.28f + travel * sin(angle)),
                        height * (0.74f + 0.10f * motionStrength * sin(angle * 2f)),
                    )
                    val coolAt = Offset(
                        width * (0.74f + travel * sin(angle + PI.toFloat())),
                        height * (0.22f + 0.08f * motionStrength * cos(angle)),
                    )
                    withTransform({ translate(warmAt.x, warmAt.y) }) {
                        drawCircle(duskWarm, duskRadius, Offset.Zero)
                    }
                    withTransform({ translate(coolAt.x, coolAt.y) }) {
                        drawCircle(duskCool, duskRadius * 0.92f, Offset.Zero)
                    }
                }
                HomeHeroStyle.EMBER -> {
                    val dots = if (motionStrength < 0.5f) 6 else 10
                    repeat(dots) { index ->
                        val seed = index * 0.618034f
                        val rise = ((seed * 0.37f) + t) % 1f
                        val x = width * (0.08f + (seed % 1f) * 0.84f)
                        val y = height * (0.96f - rise * 0.82f)
                        val life = sin(rise * PI).toFloat()
                        val twinkle = 0.65f + 0.35f * sin((t * 2f + seed) * PI).toFloat()
                        val radius = (16f + index % 5 * 5f) * density * spread.coerceAtMost(1.15f)
                        val alpha = life * twinkle * strength
                        drawCircle(color.copy(alpha = 0.03f * alpha), radius * 2.3f, Offset(x, y))
                        drawCircle(warm.copy(alpha = 0.10f * alpha), radius, Offset(x, y))
                    }
                }
                HomeHeroStyle.TIDE -> {
                    val centerY = height * (0.62f + (t - 0.5f) * 0.36f * motionStrength)
                    withTransform({ translate(0f, centerY) }) {
                        drawRect(tide, Offset(0f, -tideHalf), Size(width, tideHalf * 2f))
                    }
                }
                HomeHeroStyle.FOCUS -> {
                    val y = height * focusTravel().coerceIn(0.08f, 0.92f)
                    val x = width * (0.62f + sin((t * 2f) * PI).toFloat() * 0.12f * motionStrength)
                    withTransform({ translate(x, y) }) {
                        drawCircle(focusBrush, focusRadius, Offset.Zero)
                    }
                }
            }
        }
    }
}

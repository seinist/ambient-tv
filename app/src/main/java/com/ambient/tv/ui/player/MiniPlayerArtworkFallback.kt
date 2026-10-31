package com.ambient.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.SharedSkeletonPulse
import com.ambient.tv.ui.common.rememberSharedPulse
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.LocalPlaybackAccent
import java.util.Locale

/** Two readable characters inside the 46 dp artwork circle, not a new text overlay beside it. */
internal fun miniPlayerMonogram(title: String): String? {
    val name = title.trim()
    val normalized = name.lowercase(Locale.ROOT)
    if (name.isEmpty() || normalized.startsWith("http://") || normalized.startsWith("https://") ||
        normalized in setOf("unknown", "untitled", "unbekannt", "n/a", "radio", "radio browser")
    ) return null
    val words = Regex("""[\p{L}\p{N}]+""").findAll(name).map { it.value }.take(2).toList()
    val letters = when (words.size) {
        0 -> return null
        1 -> words.first().take(2)
        else -> words.joinToString("") { it.take(1) }
    }
    return letters.uppercase(Locale.ROOT).take(2).takeIf(String::isNotBlank)
}

/** Unknown live duration is an accented LIVE ring, never invented playback progress. */
internal fun miniPlayerRingSweep(progress: Float?, isLive: Boolean): Float? =
    progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.times(360f)
        ?: if (isLive) 360f else null

/** Used only when all artwork candidates are absent or failed. Never changes the circle's size. */
@Composable
internal fun MiniPlayerArtworkFallback(
    title: String,
    source: String,
    playing: Boolean,
    audioOnly: Boolean,
) {
    val accent = LocalPlaybackAccent.current.indicator
    val monogram = miniPlayerMonogram(title)
    val system = MediaSystem.fromKey(source)
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .background(accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        val symbol = Modifier.align(Alignment.Center).offset(y = if (audioOnly) (-4).dp else 0.dp)
        when {
            monogram != null -> Text(
                text = monogram,
                modifier = symbol,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = Color.White.copy(alpha = 0.95f),
            )
            system != null -> SystemLogo(
                system = system,
                modifier = symbol,
                size = 23.dp,
                tint = accent,
            )
            else -> Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = symbol,
                tint = accent,
            )
        }
        if (audioOnly) MiniPlayerAudioBars(
            playing = playing,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
        )
    }
}

/** One shared lifecycle-bound pulse; frame changes only redraw these 14x8 dp, not Compose. */
@Composable
private fun MiniPlayerAudioBars(playing: Boolean, modifier: Modifier = Modifier) {
    val pulse = if (playing && !LocalPerformanceProfile.current.reducedEffects)
        rememberSharedPulse(SharedSkeletonPulse) else null
    val color = LocalPlaybackAccent.current.indicator.copy(alpha = if (playing) 0.95f else 0.45f)
    Box(
        modifier
            .width(14.dp)
            .height(8.dp)
            .drawBehind {
                // This state is read at draw time: it never recomposes the mini-player per frame.
                val wave = ((pulse?.value ?: 0.45f) - 0.28f).div(0.34f).coerceIn(0f, 1f)
                val barWidth = 3.dp.toPx()
                val gap = 2.dp.toPx()
                val radius = CornerRadius(1.dp.toPx())
                for (index in 0..2) {
                    val level = when (index) {
                        0 -> 0.25f + 0.75f * wave
                        1 -> 0.90f - 0.55f * wave
                        else -> 0.30f + 0.65f * (1f - kotlin.math.abs(wave - 0.5f) * 2f)
                    }
                    val height = size.height * level
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(index * (barWidth + gap), size.height - height),
                        size = Size(barWidth, height),
                        cornerRadius = radius,
                    )
                }
            },
    )
}

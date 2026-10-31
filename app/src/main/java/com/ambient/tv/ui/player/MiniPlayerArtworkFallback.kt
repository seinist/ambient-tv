package com.ambient.tv.ui.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.ambient.tv.ui.theme.LocalPlaybackAccent
import java.util.Locale

/** Two readable characters inside the 46 dp artwork circle, not a new text overlay beside it. */
internal fun miniPlayerMonogram(title: String): String? {
    val name = title.trim()
    if (name.isEmpty() || name.lowercase(Locale.ROOT) in setOf(
            "unknown", "untitled", "unbekannt", "n/a", "radio", "radio browser",
        )
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
            MediaSystem.fromKey(source) != null -> SystemLogo(
                system = requireNotNull(MediaSystem.fromKey(source)),
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

/** Three tiny equalizer bars; no motion or drawing work if artwork is successfully loaded. */
@Composable
private fun MiniPlayerAudioBars(playing: Boolean, modifier: Modifier = Modifier) {
    val levels: List<Float> = if (playing) {
        val motion = rememberInfiniteTransition(label = "miniAudio")
        val first = motion.animateFloat(0.30f, 1f,
            infiniteRepeatable(tween(540), RepeatMode.Reverse), label = "left").value
        val middle = motion.animateFloat(0.95f, 0.28f,
            infiniteRepeatable(tween(690), RepeatMode.Reverse), label = "middle").value
        val last = motion.animateFloat(0.50f, 1f,
            infiniteRepeatable(tween(460), RepeatMode.Reverse), label = "right").value
        listOf(first, middle, last)
    } else listOf(0.30f, 0.55f, 0.30f)
    val foreground = LocalPlaybackAccent.current.indicator
    Row(
        modifier.height(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        levels.forEach { level ->
            Box(
                Modifier.width(3.dp).height((8f * level).dp)
                    .background(foreground.copy(alpha = if (playing) 0.96f else 0.45f)),
            )
        }
    }
}

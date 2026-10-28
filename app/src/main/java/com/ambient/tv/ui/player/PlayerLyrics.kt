package com.ambient.tv.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ambient.tv.data.lyrics.Lyrics
import com.ambient.tv.data.lyrics.LyricsRepository
import com.ambient.tv.data.lyrics.LyricsRequest
import com.ambient.tv.data.lyrics.activeLyricIndex
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LyricsEntryPoint {
    fun lyrics(): LyricsRepository
}

/** Bumped when the lyrics switches in the player's tools change, so the overlay re-reads them. */
internal object LyricsSettings {
    val revision = mutableIntStateOf(0)
}

/** The song the player shows; null for anything that is not music from a library. */
internal fun PlayerUiState.Ready.lyricsRequest(): LyricsRequest? =
    if (mediaType.equals("track", ignoreCase = true) || mediaType.equals("audio", ignoreCase = true))
        LyricsRequest(sourceRatingKey, title, grandparentTitle, parentTitle, durationMs)
    else null

/**
 * The song's lyrics over the visualizer: timed lyrics follow playback with the sung line
 * highlighted, untimed lyrics scroll along with the song. Nothing shows when there are none.
 */
@Composable
internal fun PlayerLyricsOverlay(request: LyricsRequest?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision = LyricsSettings.revision.intValue
    val preferences = remember { videoImagePreferences(context) }
    val visible = remember(revision) { preferences.lyricsVisible }
    val online = remember(revision) { preferences.lyricsOnlineEnabled }
    if (request == null || !visible) return
    val lyrics by produceState<Lyrics?>(null, request, online) {
        value = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, LyricsEntryPoint::class.java).lyrics().lyricsFor(request)
        }.getOrNull()
    }
    val current = lyrics ?: return
    var positionMs by remember(current) { mutableLongStateOf(0L) }
    LaunchedEffect(current) {
        while (true) {
            positionMs = BackgroundPlaybackSession.activeHandle()?.status()?.positionMs ?: positionMs
            delay(POSITION_POLL_MS)
        }
    }
    Box(
        modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Transparent)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (current.synced) SyncedLyrics(current, positionMs) else PlainLyrics(current, positionMs, request.durationMs)
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: Long) {
    val index = activeLyricIndex(lyrics.lines, positionMs)
    val first = (index - LINES_BEFORE).coerceAtLeast(0)
    val last = (maxOf(index, 0) + LINES_AFTER).coerceAtMost(lyrics.lines.lastIndex)
    Column(
        Modifier.widthIn(max = 900.dp).padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (i in first..last) {
            val line = lyrics.lines[i]
            val active = i == index
            val color by animateColorAsState(if (active) Color.White else Color.White.copy(alpha = 0.45f), label = "lyricLine")
            Text(
                text = line.text.ifBlank { "♪" },
                color = color,
                fontSize = if (active) 34.sp else 24.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = if (active) 42.sp else 30.sp,
            )
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, positionMs: Long, durationMs: Long?) {
    val scroll = rememberScrollState()
    // Untimed lyrics move along with the song so the sung part stays roughly in view.
    LaunchedEffect(positionMs, scroll.maxValue, durationMs) {
        val total = durationMs?.takeIf { it > 0 } ?: return@LaunchedEffect
        scroll.animateScrollTo((scroll.maxValue * (positionMs.toFloat() / total).coerceIn(0f, 1f)).toInt())
    }
    Column(
        Modifier.fillMaxHeight().widthIn(max = 900.dp).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 48.dp, vertical = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        lyrics.lines.forEach { line ->
            Text(line.text, color = Color.White.copy(alpha = 0.85f), fontSize = 24.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
        }
    }
}

private const val POSITION_POLL_MS = 250L
private const val LINES_BEFORE = 2
private const val LINES_AFTER = 3

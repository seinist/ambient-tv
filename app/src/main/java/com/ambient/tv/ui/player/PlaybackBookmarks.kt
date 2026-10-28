package com.ambient.tv.ui.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class TitleBookmarks(val key: String, val positionsMs: List<Long>)

/**
 * Bookmarks (jump marks) inside a film or episode, kept per title on this device. The most
 * recently changed [MAX_TITLES] titles keep theirs; a title holds at most [MAX_PER_TITLE].
 */
internal object PlaybackBookmarks {
    private val json = Json { ignoreUnknownKeys = true }
    /** Bumped on every change so open panels re-read. */
    val revision = mutableIntStateOf(0)

    fun keyFor(metadata: PlaybackMetadata?): String? =
        metadata?.takeIf { !it.isLive }?.let(TitleVideoSettingsStore::key)

    fun list(context: Context, key: String): List<Long> = load(context).firstOrNull { it.key == key }?.positionsMs.orEmpty()

    fun add(context: Context, key: String, positionMs: Long) =
        save(context, addBookmark(load(context), key, positionMs))

    fun remove(context: Context, key: String, positionMs: Long) =
        save(context, removeBookmark(load(context), key, positionMs))

    fun clear(context: Context, key: String) = save(context, load(context).filterNot { it.key == key })

    private fun load(context: Context): List<TitleBookmarks> =
        videoImagePreferences(context).playbackBookmarksJson
            ?.let { raw -> runCatching { json.decodeFromString(ListSerializer(TitleBookmarks.serializer()), raw) }.getOrNull() }
            .orEmpty()

    private fun save(context: Context, all: List<TitleBookmarks>) {
        videoImagePreferences(context).playbackBookmarksJson =
            all.takeIf { it.isNotEmpty() }?.let { json.encodeToString(ListSerializer(TitleBookmarks.serializer()), it) }
        revision.intValue++
    }

    const val MAX_TITLES = 200
    const val MAX_PER_TITLE = 30
    /** A new mark this close to an existing one replaces nothing and is not added twice. */
    const val MIN_GAP_MS = 2_000L
}

/** Adds a mark, sorted, without near-duplicates; the title moves to the front of the list. */
internal fun addBookmark(all: List<TitleBookmarks>, key: String, positionMs: Long): List<TitleBookmarks> {
    val position = positionMs.coerceAtLeast(0L)
    val current = all.firstOrNull { it.key == key }?.positionsMs.orEmpty()
    if (current.any { kotlin.math.abs(it - position) < PlaybackBookmarks.MIN_GAP_MS }) return all
    val marks = (current + position).sorted().takeLast(PlaybackBookmarks.MAX_PER_TITLE)
    return (listOf(TitleBookmarks(key, marks)) + all.filterNot { it.key == key }).take(PlaybackBookmarks.MAX_TITLES)
}

internal fun removeBookmark(all: List<TitleBookmarks>, key: String, positionMs: Long): List<TitleBookmarks> =
    all.mapNotNull { entry ->
        if (entry.key != key) entry
        else entry.copy(positionsMs = entry.positionsMs - positionMs).takeIf { it.positionsMs.isNotEmpty() }
    }

/** Marks of the title now playing, for the seek bar; empty for live and titles without a key. */
@Composable
internal fun rememberPlayingBookmarks(): List<Long> {
    val context = LocalContext.current
    val metadataFlow = remember { BackgroundPlaybackSession.snapshot.map { it.metadata }.distinctUntilChanged() }
    val metadata by metadataFlow.collectAsState(BackgroundPlaybackSession.snapshot.value.metadata)
    val key = PlaybackBookmarks.keyFor(metadata)
    val revision = PlaybackBookmarks.revision.intValue
    return remember(key, revision) { key?.let { PlaybackBookmarks.list(context, it) }.orEmpty() }
}

/** Slim light ticks a little taller than the track, so they read apart from chapter gaps. */
internal fun DrawScope.drawBookmarkTicks(marks: List<Long>, durationMs: Long, top: Float, bottom: Float) {
    if (durationMs <= 0L) return
    val overhang = 3.dp.toPx()
    marks.forEach { mark ->
        val x = (mark.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) * size.width
        drawLine(Color.White.copy(alpha = 0.9f), Offset(x, top - overhang), Offset(x, bottom + overhang), 2.dp.toPx(), StrokeCap.Round)
    }
}

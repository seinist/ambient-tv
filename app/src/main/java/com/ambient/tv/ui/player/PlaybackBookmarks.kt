package com.ambient.tv.ui.player

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
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

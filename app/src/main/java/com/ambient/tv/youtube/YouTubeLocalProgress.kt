package com.ambient.tv.youtube

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import org.json.JSONArray

/**
 * One watch state for cards and playback. Local playback keeps its exact time while YouTube
 * applies history updates; fresh card responses can replace it when another device changes
 * the position. Rendering an older cached card must never look like a fresh server update.
 */
object YouTubeLocalProgress {
    private data class Entry(
        val percent: Int,
        val remoteAtSave: Int?,
        val savedAt: Long,
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        val pendingPercents: Set<Int> = emptySet(),
    )

    private const val PREFS = "youtube_local_progress"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 400

    private val entries = mutableStateMapOf<String, Entry>()
    // Observable too: all cached cards must reflect a newer server value, not only the card
    // whose network request happened to return it.
    private val lastRemote = mutableStateMapOf<String, Int>()
    @Volatile private var context: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }.getOrNull() ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { id ->
                val o = json.getJSONObject(id)
                entries[id] = Entry(
                    o.getInt("p").coerceIn(0, 100),
                    if (o.has("r")) o.getInt("r").coerceIn(0, 100) else null,
                    o.optLong("t"),
                    if (o.has("position")) o.getLong("position").coerceAtLeast(0L) else null,
                    if (o.has("duration")) o.getLong("duration").takeIf { it > 0L } else null,
                    o.optJSONArray("pending")?.let { values ->
                        (0 until values.length()).mapNotNull { index ->
                            values.optInt(index, -1).takeIf { it in 0..100 }
                        }.toSet()
                    }.orEmpty(),
                )
            }
        }
    }

    /** Only fresh card responses are authoritative; watch-next often has no watch position. */
    @Synchronized
    fun observeRemote(videoId: String, remotePercent: Int) {
        if (videoId.isBlank() || remotePercent < 0) return
        val remote = remotePercent.coerceIn(0, 100)
        lastRemote[videoId] = remote
        val entry = entries[videoId] ?: return
        when {
            // Acknowledgement must retain our exact milliseconds. Converting a server's
            // whole percentage back into time would lose up to 1% of a long video's length.
            entry.remoteAtSave == null || remote == entry.percent -> {
                if (entry.remoteAtSave == remote) return
                entries[videoId] = entry.copy(remoteAtSave = remote,
                    pendingPercents = entry.pendingPercents + listOfNotNull(entry.remoteAtSave))
            }
            youtubePendingProgressEcho(remote, entry.pendingPercents, entry.savedAt, System.currentTimeMillis()) -> return
            entry.remoteAtSave != remote -> entries.remove(videoId)
            else -> return
        }
        persist()
    }

    /** A disk/in-memory card is a fallback, never evidence of a newer YouTube watch state. */
    @Synchronized
    fun observeCached(videoId: String, remotePercent: Int) {
        if (videoId.isBlank() || remotePercent < 0 || videoId in lastRemote) return
        lastRemote[videoId] = remotePercent.coerceIn(0, 100)
    }

    /** Older versions could store a moving live-window clock as VOD watch progress. */
    @Synchronized
    fun discardLive(videoId: String) {
        lastRemote.remove(videoId)
        if (entries.remove(videoId) != null) persist()
    }

    /** Position reached in this app's player. */
    @Synchronized
    fun record(videoId: String, positionMs: Long, durationMs: Long, completed: Boolean = false) {
        if (videoId.isBlank() || (durationMs <= 0L && !completed)) return
        val duration = durationMs.takeIf { it > 0L }
        val position = if (completed && duration != null) duration else
            positionMs.coerceAtLeast(0L).let { if (duration != null) it.coerceAtMost(duration) else it }
        val percent = if (completed) 100 else
            (position.toDouble() / durationMs * 100).toInt().coerceIn(0, 100)
        val previous = entries[videoId]
        val remote = previous?.remoteAtSave ?: lastRemote[videoId]
        if (previous?.percent == percent && previous.remoteAtSave == remote &&
            previous.positionMs == position && previous.durationMs == duration) return
        val now = System.currentTimeMillis()
        val pending = if (previous != null && now - previous.savedAt in 0..YOUTUBE_PROGRESS_SYNC_WINDOW_MS)
            previous.pendingPercents + previous.percent + listOfNotNull(remote)
        else listOfNotNull(remote).toSet()
        entries[videoId] = Entry(percent, remote, now, position, duration, pending)
        if (entries.size > MAX_ENTRIES) {
            entries.entries.sortedBy { it.value.savedAt }.take(entries.size - MAX_ENTRIES)
                .map { it.key }.forEach(entries::remove)
        }
        persist()
    }

    /** Progress to show, with a cached card's value used only when no newer state is known. */
    @Synchronized
    fun percent(videoId: String, remotePercent: Int): Int {
        return entries[videoId]?.percent ?: lastRemote[videoId] ?: remotePercent.coerceIn(0, 100)
    }

    @Synchronized
    fun durationMs(videoId: String): Long? = entries[videoId]?.durationMs

    /**
     * Uses the cards' watch state, with exact local time when available. Watch-next is only
     * a fallback. null means unknown; zero is a known unwatched/finished card and wins too.
     */
    @Synchronized
    fun resumePositionMs(videoId: String, durationMs: Long, metadataPercent: Int? = null): Long? {
        val entry = entries[videoId]
        val percent = entry?.percent ?: lastRemote[videoId] ?: metadataPercent?.coerceIn(0, 100)
            ?: return null
        if (percent >= YOUTUBE_FINISHED_PERCENT) return 0L
        val duration = durationMs.takeIf { it > 0L } ?: entry?.durationMs
        entry?.positionMs?.let { position ->
            return if (duration != null) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
        }
        if (percent == 0) return 0L
        return duration?.let { youtubeResumeMs(it, percent, isLive = false) }
    }

    private fun persist() {
        val ctx = context ?: return
        val json = JSONObject()
        entries.forEach { (id, e) ->
            json.put(id, JSONObject().put("p", e.percent).put("t", e.savedAt).apply {
                e.remoteAtSave?.let { put("r", it) }
                e.positionMs?.let { put("position", it) }
                e.durationMs?.let { put("duration", it) }
                if (e.pendingPercents.isNotEmpty()) put("pending", JSONArray(e.pendingPercents.toList()))
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}

private const val YOUTUBE_PROGRESS_SYNC_WINDOW_MS = 120_000L

/** Our own delayed intermediate reports must not undo a newer local finish or replay. */
internal fun youtubePendingProgressEcho(remotePercent: Int, pendingPercents: Set<Int>, savedAt: Long, nowMs: Long): Boolean =
    nowMs - savedAt in 0..YOUTUBE_PROGRESS_SYNC_WINDOW_MS && remotePercent in pendingPercents

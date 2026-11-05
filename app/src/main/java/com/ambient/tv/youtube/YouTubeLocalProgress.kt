package com.ambient.tv.youtube

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/**
 * Watch progress saved by this app, alongside YouTube's own percentWatched.
 *
 * YouTube applies a history update with a delay, so right after playback its value is stale.
 * The local value wins while YouTube still reports what it reported when the local value was
 * saved; once YouTube reports anything else (our update arrived, or another device played the
 * video), YouTube is authoritative again and the local entry is dropped.
 */
object YouTubeLocalProgress {
    private data class Entry(val percent: Int, val remoteAtSave: Int?, val savedAt: Long)

    private const val PREFS = "youtube_local_progress"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 400

    private val entries = mutableStateMapOf<String, Entry>()
    private val lastRemote = HashMap<String, Int>()
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
                    o.getInt("p"),
                    if (o.has("r")) o.getInt("r") else null,
                    o.optLong("t"),
                )
            }
        }
    }

    /** Called with every value YouTube delivers (cards, watch-next). */
    @Synchronized
    fun observeRemote(videoId: String, remotePercent: Int) {
        lastRemote[videoId] = remotePercent
        val entry = entries[videoId] ?: return
        when {
            entry.remoteAtSave == null -> entries[videoId] = entry.copy(remoteAtSave = remotePercent)
            entry.remoteAtSave != remotePercent -> entries.remove(videoId)
            else -> return
        }
        persist()
    }

    /** Position reached in this app's player. */
    @Synchronized
    fun record(videoId: String, positionMs: Long, durationMs: Long) {
        if (videoId.isBlank() || durationMs <= 0L) return
        val percent = (positionMs.coerceAtLeast(0L) * 100 / durationMs).toInt().coerceIn(0, 100)
        val previous = entries[videoId]
        val remote = previous?.remoteAtSave ?: lastRemote[videoId]
        if (previous?.percent == percent && previous.remoteAtSave == remote) return
        entries[videoId] = Entry(percent, remote, System.currentTimeMillis())
        if (entries.size > MAX_ENTRIES) {
            entries.entries.sortedBy { it.value.savedAt }.take(entries.size - MAX_ENTRIES)
                .map { it.key }.forEach(entries::remove)
        }
        persist()
    }

    /** Progress to show and resume from, given YouTube's [remotePercent] for [videoId]. */
    fun percent(videoId: String, remotePercent: Int): Int {
        val entry = entries[videoId] ?: return remotePercent
        return if (entry.remoteAtSave == null || entry.remoteAtSave == remotePercent) entry.percent else remotePercent
    }

    private fun persist() {
        val ctx = context ?: return
        val json = JSONObject()
        entries.forEach { (id, e) ->
            json.put(id, JSONObject().put("p", e.percent).put("t", e.savedAt).apply { e.remoteAtSave?.let { put("r", it) } })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}

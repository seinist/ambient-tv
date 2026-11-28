package com.ambient.tv.youtube

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.ambient.tv.data.local.LocalPlaybackDatabase
import com.ambient.tv.data.local.LocalPlaybackRow
import com.ambient.tv.data.local.UnreadablePlaybackLegacy
import kotlinx.coroutines.CompletableDeferred
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
    private val progressRevision = mutableIntStateOf(0)
    /** Observable invalidation for filtered browse snapshots; exact-time-only writes do not bump it. */
    @get:Synchronized
    val revision: Int get() = progressRevision.intValue
    @Volatile private var context: Context? = null
    private var database: LocalPlaybackDatabase? = null
    private var ready = CompletableDeferred<Unit>()
    private var loaded = false
    private var loading = false
    private val beforeReady = mutableSetOf<String>()
    private val remoteBeforeReady = mutableMapOf<String, Int>()

    @Synchronized
    fun init(context: Context, database: LocalPlaybackDatabase = LocalPlaybackDatabase(context)) {
        if (this.context != null) return
        this.context = context.applicationContext
        this.database = database
        beforeReady += entries.keys
        startLoad()
    }

    /** Local SQL priming only; never initializes SmartTube or performs a YouTube request. */
    suspend fun awaitReady() {
        val completion = synchronized(this) {
            if (database == null) return
            startLoad()
            ready
        }
        completion.await()
    }

    suspend fun flush(): Boolean = synchronized(this) { database }?.flush() ?: true

    private fun startLoad() {
        if (loaded || loading) return
        val storage = database ?: return
        val prefs = requireNotNull(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loading = true
        ready = CompletableDeferred()
        val completion = ready
        storage.load("youtube_progress", MAX_ENTRIES, legacy = {
            val raw = prefs.getString(KEY, null) ?: "{}"
            try {
                val stored = JSONObject(raw)
                stored.keys().asSequence().map { id ->
                    val entry = decodeEntry(stored.getJSONObject(id))
                    LocalPlaybackRow(id, "", "device", entry.savedAt, encodeEntry(entry))
                }.toList()
            } catch (error: Exception) { throw UnreadablePlaybackLegacy(raw, "device", error) }
        }, cleanup = { if (prefs.contains(KEY)) prefs.edit().remove(KEY).apply() }, success = { rows ->
            synchronized(this) {
                rows.forEach { row ->
                    if (row.key !in beforeReady) runCatching { decodeEntry(JSONObject(row.payload)) }.getOrNull()?.let { entries[row.key] = it }
                }
                loaded = true; loading = false
                beforeReady.toList().forEach { persist(it) }
                beforeReady.clear()
                val fresh = remoteBeforeReady.toMap()
                remoteBeforeReady.clear()
                fresh.forEach { (id, percent) -> observeRemote(id, percent) }
                trimEntries()
                if (rows.isNotEmpty()) progressRevision.intValue++
            }
            completion.complete(Unit)
        }, failure = { error ->
            synchronized(this) { loading = false }
            timber.log.Timber.w(error, "YouTube resume state could not be primed")
            completion.completeExceptionally(error)
        })
    }

    /** Only fresh card responses are authoritative; watch-next often has no watch position. */
    @Synchronized
    fun observeRemote(videoId: String, remotePercent: Int) {
        if (videoId.isBlank() || remotePercent < 0) return
        val remote = remotePercent.coerceIn(0, 100)
        val before = knownPercent(videoId)
        lastRemote[videoId] = remote
        if (database != null && !loaded) remoteBeforeReady[videoId] = remote
        try {
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
            persist(videoId)
        } finally {
            publishPercentChange(videoId, before)
        }
    }

    /** A disk/in-memory card is a fallback, never evidence of a newer YouTube watch state. */
    @Synchronized
    fun observeCached(videoId: String, remotePercent: Int) {
        if (videoId.isBlank() || remotePercent < 0 || videoId in lastRemote) return
        val before = knownPercent(videoId)
        lastRemote[videoId] = remotePercent.coerceIn(0, 100)
        publishPercentChange(videoId, before)
    }

    /** Older versions could store a moving live-window clock as VOD watch progress. */
    @Synchronized
    fun discardLive(videoId: String) {
        val before = knownPercent(videoId)
        lastRemote.remove(videoId)
        remoteBeforeReady.remove(videoId)
        val removed = entries.remove(videoId) != null
        // A live stream identified before cold loading must not resurrect an old VOD clock.
        if (removed || (database != null && !loaded)) persist(videoId, immediate = true)
        publishPercentChange(videoId, before)
    }

    /** Position reached in this app's player. */
    @Synchronized
    fun record(videoId: String, positionMs: Long, durationMs: Long, completed: Boolean = false) {
        if (videoId.isBlank() || (durationMs <= 0L && !completed)) return
        val before = knownPercent(videoId)
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
        trimEntries()
        publishPercentChange(videoId, before)
        persist(videoId, immediate = completed)
    }

    private fun knownPercent(videoId: String): Int? = entries[videoId]?.percent ?: lastRemote[videoId]

    private fun publishPercentChange(videoId: String, before: Int?) {
        if (knownPercent(videoId) != before) progressRevision.intValue++
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

    private fun persist(videoId: String, immediate: Boolean = false) {
        val storage = database ?: return
        if (!loaded) { beforeReady += videoId; return }
        val entry = entries[videoId]
        if (entry == null) storage.delete("youtube_progress", videoId, "device", immediate)
        else storage.put("youtube_progress", LocalPlaybackRow(videoId, "", "device", entry.savedAt, encodeEntry(entry)), immediate)
    }

    private fun trimEntries() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries.sortedBy { it.value.savedAt }.take(entries.size - MAX_ENTRIES).map { it.key }.forEach {
            entries.remove(it); persist(it)
        }
        // Eviction can change another cached card's effective watch state too.
        progressRevision.intValue++
    }

    private fun encodeEntry(e: Entry): String = JSONObject().put("p", e.percent).put("t", e.savedAt).apply {
        e.remoteAtSave?.let { put("r", it) }
        e.positionMs?.let { put("position", it) }
        e.durationMs?.let { put("duration", it) }
        if (e.pendingPercents.isNotEmpty()) put("pending", JSONArray(e.pendingPercents.toList()))
    }.toString()

    private fun decodeEntry(o: JSONObject) = Entry(
        o.getInt("p").coerceIn(0, 100),
        if (o.has("r")) o.getInt("r").coerceIn(0, 100) else null,
        o.optLong("t"),
        if (o.has("position")) o.getLong("position").coerceAtLeast(0L) else null,
        if (o.has("duration")) o.getLong("duration").takeIf { it > 0L } else null,
        o.optJSONArray("pending")?.let { values -> (0 until values.length()).mapNotNull { index ->
            values.optInt(index, -1).takeIf { it in 0..100 }
        }.toSet() }.orEmpty(),
    )
}

private const val YOUTUBE_PROGRESS_SYNC_WINDOW_MS = 120_000L

/** Our own delayed intermediate reports must not undo a newer local finish or replay. */
internal fun youtubePendingProgressEcho(remotePercent: Int, pendingPercents: Set<Int>, savedAt: Long, nowMs: Long): Boolean =
    nowMs - savedAt in 0..YOUTUBE_PROGRESS_SYNC_WINDOW_MS && remotePercent in pendingPercents

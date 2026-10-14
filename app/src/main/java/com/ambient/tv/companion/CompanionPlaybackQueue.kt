package com.ambient.tv.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/** Pending entries have stable tokens: a delayed edit cannot address another row by index. */
internal class CompanionPlaybackQueue {
    private val pending = mutableListOf<CompanionQueueItem>()
    private val played = ArrayDeque<CompanionMedia>()
    var revision = 0L; private set
    val entries: List<CompanionQueueItem> get() = pending.toList()
    val hasPrevious get() = played.isNotEmpty()
    val previousMedia get() = played.lastOrNull()
    val hasNext get() = pending.isNotEmpty()
    fun add(item: CompanionQueueItem) { require(pending.size < 64 && item.token.isNotBlank() && item.media != null && pending.none { it.token == item.token }); pending.add(item); revision++ }
    fun remove(token: String, expected: Long) {
        require(expected == revision); require(pending.removeAll { it.token == token }); revision++
    }
    fun move(token: String, delta: Int, expected: Long) {
        require(expected == revision && delta in setOf(-1, 1))
        val from = pending.indexOfFirst { it.token == token }; require(from >= 0)
        val to = (from + delta).coerceIn(0, pending.lastIndex)
        if (to != from) { pending.add(to, pending.removeAt(from)); revision++ }
    }
    fun clear(expected: Long? = null) { require(expected == null || expected == revision); pending.clear(); revision++ }
    fun takeNext(token: String? = null): CompanionQueueItem? {
        val index = if (token == null) 0 else pending.indexOfFirst { it.token == token }
        if (index !in pending.indices) return null
        return pending.removeAt(index).also { revision++ }
    }
    fun remember(media: CompanionMedia) {
        if (played.lastOrNull()?.let { samePlaybackMedia(it, media) } == true) return
        played.addLast(media); revision++
        while (played.size > 24) played.removeFirst()
    }
    fun previous(): CompanionMedia? = played.removeLastOrNull()?.also { revision++ }
    fun restore(item: CompanionQueueItem, index: Int = 0) {
        require(pending.size < 64 && pending.none { it.token == item.token } && item.media != null)
        pending.add(index.coerceIn(0, pending.size), item); revision++
    }

    fun checkpoint() = CompanionQueueCheckpoint(revision = revision,
        entries = pending.filter { it.media?.let(::persistableQueueMedia) == true },
        history = played.filter(::persistableQueueMedia))

    fun restoreCheckpoint(value: CompanionQueueCheckpoint) {
        validateQueueCheckpoint(value)
        pending.clear(); pending.addAll(value.entries)
        played.clear(); played.addAll(value.history)
        // Invalidate phone rows which were shown before the receiver process restarted.
        revision = value.revision + 1
    }
}

/** The queued choice takes priority over source autoplay/EOF Stop, including background video. */
internal object CompanionQueuePlayback {
    var pending by mutableStateOf(false)
}

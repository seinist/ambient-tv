package com.ambient.tv.data.telegram

/**
 * Global descending chronology across channels. Each channel's newest message date
 * bounds its still-unseen media, so old subscriptions are fetched only when needed.
 * The server retains this cursor privately; no list of accounts or offsets is exposed.
 */
internal class MonogramRecentTimeline(
    channels: List<Channel>,
    /**
     * Newest possible message of the channels not loaded yet, or null when [channels] is the
     * whole catalog. Telegram returns dialogs newest first, so every unloaded channel's latest
     * message is at most the last loaded dialog's: more of the catalog is fetched only while
     * that bound is newer than the next item to show, never the whole list up front.
     */
    unloadedBoundMs: Long? = null,
    private val moreChannels: (() -> CatalogPage)? = null,
    private val fetch: (channel: Long, offsetId: Int, count: Int) -> HistoryBatch,
) {
    data class Channel(val id: Long, val latestMessageMs: Long)
    /** Next part of the catalog; [unloadedBoundMs] null when the catalog is complete. */
    data class CatalogPage(val channels: List<Channel>, val unloadedBoundMs: Long?)
    data class HistoryBatch(
        val media: List<TelegramMedia>,
        val nextOffsetId: Int?,
        val oldestMessageMs: Long,
    )
    data class Page(val media: List<TelegramMedia>, val hasMore: Boolean)

    private class Source(val channel: Channel) {
        val buffer = ArrayDeque<TelegramMedia>()
        var offsetId = 0
        var upperBound = channel.latestMessageMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        var done = false
    }

    private val sources = channels.distinctBy(Channel::id).map(::Source).toMutableList()
    private var catalogBound: Long? = unloadedBoundMs.takeIf { moreChannels != null }
    private val emitted = HashSet<String>()
    private fun hasMore(): Boolean = catalogBound != null || sources.any { it.buffer.isNotEmpty() || !it.done }

    fun nextPage(maxItems: Int = 60, requestBudget: Int = 96): Page {
        require(maxItems in 1..60 && requestBudget in 1..1024)
        val result = ArrayList<TelegramMedia>(maxItems)
        var requests = 0
        while (result.size < maxItems) {
            val ready = sources.filter { it.buffer.isNotEmpty() }.maxWithOrNull(
                compareBy<Source> { it.buffer.first().publishedAtMs }
                    .thenBy { it.buffer.first().chatId }
                    .thenBy { it.buffer.first().messageId },
            )
            val unseen = sources.filter { !it.done && it.buffer.isEmpty() }
                .maxByOrNull(Source::upperBound)
            val readyTime = ready?.buffer?.firstOrNull()?.publishedAtMs ?: Long.MIN_VALUE
            val bound = catalogBound
            if (bound != null && bound >= maxOf(readyTime, unseen?.upperBound ?: Long.MIN_VALUE)) {
                // An unloaded channel may hold the next item: page the catalog first.
                if (requests++ >= requestBudget) break
                val page = moreChannels!!.invoke()
                val known = sources.mapTo(HashSet()) { it.channel.id }
                val added = page.channels.filter { known.add(it.id) }
                sources += added.map(::Source)
                // A page of only groups/users moves the bound without adding a channel; only a
                // page that changes nothing ends paging (no endless loop on a stuck catalog).
                catalogBound = if (added.isEmpty() && page.unloadedBoundMs == bound) null else page.unloadedBoundMs
                continue
            }
            if (unseen != null && (ready == null || unseen.upperBound >= readyTime)) {
                if (requests++ >= requestBudget) break
                val before = unseen.offsetId
                val batch = fetch(unseen.channel.id, before, 16)
                unseen.buffer.addAll(batch.media.sortedWith(
                    compareByDescending<TelegramMedia> { it.publishedAtMs }
                        .thenByDescending { it.chatId }.thenByDescending { it.messageId },
                ))
                val after = batch.nextOffsetId
                unseen.done = after == null || after == before
                if (!unseen.done) {
                    unseen.offsetId = after!!
                    unseen.upperBound = batch.oldestMessageMs.takeIf { it > 0 }
                        ?: Long.MAX_VALUE
                }
                continue
            }
            if (ready == null) break
            val item = ready.buffer.removeFirst()
            if (emitted.add(item.mediaKey)) result.add(item)
        }
        return Page(result, hasMore())
    }
}

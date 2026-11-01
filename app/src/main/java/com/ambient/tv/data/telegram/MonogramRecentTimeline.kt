package com.ambient.tv.data.telegram

/**
 * Global descending chronology across channels. Each channel's newest message date
 * bounds its still-unseen media, so old subscriptions are fetched only when needed.
 * The server retains this cursor privately; no list of accounts or offsets is exposed.
 */
internal class MonogramRecentTimeline(
    channels: List<Channel>,
    private val fetch: (channel: Long, offsetId: Int, count: Int) -> HistoryBatch,
) {
    data class Channel(val id: Long, val latestMessageMs: Long)
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

    private val sources = channels.distinctBy(Channel::id).map(::Source)
    private val emitted = HashSet<String>()
    private fun hasMore(): Boolean = sources.any { it.buffer.isNotEmpty() || !it.done }

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

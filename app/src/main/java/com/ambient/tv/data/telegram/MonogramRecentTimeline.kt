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
    /**
     * Channels fetched at once when several may hold the next item: one by one, a first page
     * over sixty channels took 16 s. The native client overlaps RPCs.
     */
    private val parallelism: Int = 1,
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
                // Every source that could hold the next item is needed anyway: fetch them together.
                val wave = sources.filter { !it.done && it.buffer.isEmpty() && (ready == null || it.upperBound >= readyTime) }
                    .sortedByDescending(Source::upperBound)
                    .take(parallelism.coerceAtLeast(1).coerceAtMost(requestBudget - requests).coerceAtLeast(1))
                if (requests >= requestBudget) break
                requests += wave.size
                val batches = if (wave.size == 1) listOf(fetch(wave[0].channel.id, wave[0].offsetId, 16))
                else {
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(wave.size)
                    try {
                        wave.map { source -> pool.submit<HistoryBatch> { fetch(source.channel.id, source.offsetId, 16) } }
                            .map { it.get() }
                    } catch (failure: java.util.concurrent.ExecutionException) {
                        throw failure.cause ?: failure
                    } finally { pool.shutdownNow() }
                }
                wave.zip(batches).forEach { (source, batch) ->
                    val before = source.offsetId
                    source.buffer.addAll(batch.media.sortedWith(
                        compareByDescending<TelegramMedia> { it.publishedAtMs }
                            .thenByDescending { it.chatId }.thenByDescending { it.messageId },
                    ))
                    val after = batch.nextOffsetId
                    source.done = after == null || after == before
                    if (!source.done) {
                        source.offsetId = after!!
                        source.upperBound = batch.oldestMessageMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                    }
                }
                continue
            }
            if (ready == null) break
            val item = ready.buffer.removeFirst()
            // Reposts of one video/link in several channels appear once (the newest).
            if (emitted.add(item.contentKey)) result.add(item)
        }
        return Page(result, hasMore())
    }
}

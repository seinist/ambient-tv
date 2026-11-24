package com.ambient.tv.games.metadata

/**
 * Keyset paging remains the guaranteed-progress lane. Visible cards can jump ahead through an
 * indexed lookup, with a focused card first in [prioritizedFileIds]. Only the next request is
 * reordered; an in-flight request is never cancelled/restarted by a focus change.
 *
 * The pending page, demand snapshot and ahead-attempt ledger are all bounded independently of
 * catalog size. After three promoted games, one ordinary game runs. When the 128-entry ahead
 * ledger fills, ordinary paging drains it before accepting more promotions. Attempts (including
 * invalid responses) are returned exactly once, so progress and partial failures cannot double.
 */
internal class GameMetadataBatchQueue<T>(
    private val fileId: (T) -> String,
    private val prioritizedFileIds: () -> List<String>,
    private val page: (afterFileId: String?, limit: Int) -> List<T>,
    private val find: (fileId: String, afterFileId: String?) -> T?,
    private val pageSize: Int = 64,
    private val maxAhead: Int = 128,
    private val priorityBurst: Int = 3,
) {
    private val pending = ArrayDeque<T>()
    private val attemptedAhead = hashSetOf<String>()
    private var after: String? = null
    private var promotedInRow = 0
    private var lastPriorities = emptyList<String>()
    private val unavailable = hashSetOf<String>()

    init { require(pageSize > 0 && maxAhead > 0 && priorityBurst > 0) }

    fun next(): T? {
        val priorities = prioritizedFileIds().asSequence().filter(String::isNotBlank)
            .distinct().take(maxAhead).toList()
        if (priorities != lastPriorities) {
            lastPriorities = priorities
            unavailable.clear()
        }
        if (promotedInRow < priorityBurst && attemptedAhead.size < maxAhead) {
            for (id in priorities) {
                // Catalog file IDs use the same stable ASCII order as the keyset query.
                if (after?.let { id <= it } == true || id in attemptedAhead || id in unavailable) continue
                val game = find(id, after)
                if (game == null) {
                    // A Home/RomM card or another folder is not retried on every batch item.
                    unavailable += id
                    continue
                }
                attemptedAhead += id
                promotedInRow++
                return game
            }
        }
        promotedInRow = 0
        while (true) {
            if (pending.isEmpty()) {
                pending.addAll(page(after, pageSize))
                if (pending.isEmpty()) return null
            }
            val game = pending.removeFirst()
            val id = fileId(game)
            after = id
            val alreadyAttempted = attemptedAhead.remove(id)
            // A promoted ROM may have been removed since it was processed. It must not occupy
            // the bounded ledger forever merely because no sequential row now exists for it.
            attemptedAhead.removeAll { it <= id }
            if (!alreadyAttempted) return game
        }
    }
}

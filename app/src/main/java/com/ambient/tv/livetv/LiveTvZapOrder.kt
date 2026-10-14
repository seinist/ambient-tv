package com.ambient.tv.livetv

/**
 * Channel order of the Live TV / Guide view a channel was started from, with that view's filters
 * and sort. The player zaps through exactly these channels. Started from anywhere else (Home,
 * search, launcher, companion) nothing is offered and the player uses the full channel list.
 */
object LiveTvZapOrder {
    private class Entry(val sourceId: String, val channelUuids: List<String>, val atMs: Long)

    @Volatile private var pending: Entry? = null
    /** The order the running live playback zaps through; survives leaving to the mini-player. */
    @Volatile private var current: Entry? = null

    /** Offered right before the view starts a channel. */
    fun offer(sourceId: String, channelUuids: List<String>, nowMs: Long = clock()) {
        pending = Entry(sourceId, channelUuids.distinct(), nowMs)
    }

    fun clear() { pending = null }

    /**
     * Order for a player that starts [channelUuid]: a fresh offer, or - when the same live
     * playback is reopened from the mini-player - the order it already used. A new playback
     * started without an offer (Home, search, launcher) zaps through all channels.
     */
    fun forPlayer(sourceId: String, channelUuid: String, reopened: Boolean, nowMs: Long = clock()): List<String>? {
        val fresh = take(sourceId, channelUuid, nowMs)
        if (fresh != null) { current = Entry(sourceId, fresh, nowMs); return fresh }
        val running = current?.takeIf { reopened && it.sourceId == sourceId && channelUuid in it.channelUuids }
        if (running == null) current = null
        return running?.channelUuids
    }

    /**
     * The order offered just before a player started [channelUuid] of [sourceId], or null.
     * Taken once; a stale offer (another source, a channel outside it, too old) is dropped.
     */
    fun take(sourceId: String, channelUuid: String, nowMs: Long = clock()): List<String>? {
        val entry = pending ?: return null
        pending = null
        return entry.channelUuids.takeIf {
            entry.sourceId == sourceId && channelUuid in it && nowMs - entry.atMs in 0..MAX_AGE_MS
        }
    }

    private const val MAX_AGE_MS = 30_000L
    private fun clock() = System.nanoTime() / 1_000_000
}

package com.ambient.tv.ui.player

/** These native reports do not distinguish a dead upstream from an unsupported stream. */
internal fun ambiguousNativePlaybackFailure(message: String, vlcFailureMessage: String): Boolean =
    Regex("^MPV: -[0-9]+$").matches(message.trim()) ||
        (vlcFailureMessage.isNotBlank() && message == vlcFailureMessage)

/** The exact session observed before an asynchronous recovery. The renderer is compared by identity. */
internal data class PlaybackRecoveryOwner(
    val generation: Long,
    val route: String?,
    val sourceIdentity: String?,
    val handle: Any?,
    val replacingSource: Boolean = false,
) {
    fun matches(other: PlaybackRecoveryOwner): Boolean = generation == other.generation &&
        route == other.route && sourceIdentity == other.sourceIdentity && handle === other.handle &&
        replacingSource == other.replacingSource

    fun owns(route: String, sourceIdentity: String): Boolean =
        this.route == route && this.sourceIdentity == sourceIdentity
}

/** A retry can replace only the request/session it began with, even if a numeric title id is reused. */
internal class PlaybackRecoveryRequests {
    data class Target(val route: String, val sourceIdentity: String)
    data class Ticket internal constructor(val version: Long, val target: Target, internal var owner: PlaybackRecoveryOwner)
    private var version = 0L
    private var pending: Ticket? = null

    fun begin(target: Target, owner: PlaybackRecoveryOwner): Ticket = Ticket(++version, target, owner).also { pending = it }

    fun current(ticket: Ticket, target: Target, owner: PlaybackRecoveryOwner): Boolean =
        pending === ticket && ticket.target == target && ticket.owner.matches(owner)

    /** Only the recovery's own explicit source handoff may advance its observed generation. */
    fun transfer(ticket: Ticket, previous: PlaybackRecoveryOwner, replacement: PlaybackRecoveryOwner): Boolean {
        if (pending !== ticket || !ticket.owner.matches(previous)) return false
        ticket.owner = replacement
        return true
    }

    fun cancel() { pending = null; version++ }
    fun finish(ticket: Ticket) { if (pending === ticket) pending = null }
}

/**
 * Recovery uses the last local clock, including when a failed renderer suddenly reports zero.
 * A checkpoint belongs to the endpoint/login and title, survives an internal Stop, and is retired
 * only after a replacement generation actually plays. Live elapsed clocks are never resume points.
 */
internal class PlaybackRecoveryPositions(private val capacity: Int = 16) {
    private data class Key(val route: String, val sourceIdentity: String)
    private data class Position(val generation: Long, val value: Long)
    private val observed = LinkedHashMap<Key, Position>()
    private val failed = LinkedHashMap<Key, Position>()

    init { require(capacity > 0) }

    fun observe(
        route: String, sourceIdentity: String, generation: Long, live: Boolean,
        rendererPositionMs: Long, timelinePositionMs: Long, fallbackPositionMs: Long,
        playing: Boolean, failure: Boolean, requestedSeek: Boolean = false,
    ) {
        if (live) return
        val key = Key(route, sourceIdentity)
        val previous = observed[key]?.takeIf { it.generation == generation }
        val known = rendererPositionMs > 0L || requestedSeek || (!failure && playing)
        val position = (if (known) timelinePositionMs else previous?.value ?: fallbackPositionMs).coerceAtLeast(0L)
        observed.putBounded(key, Position(generation, position))
        if (failure) failed.putBounded(key, Position(generation, position))
        else if (playing && failed[key]?.generation != generation) failed.remove(key)
    }

    fun resume(route: String, sourceIdentity: String): Long? = failed[Key(route, sourceIdentity)]?.value
    fun clear(route: String, sourceIdentity: String) { failed.remove(Key(route, sourceIdentity)) }

    private fun <V> LinkedHashMap<Key, V>.putBounded(key: Key, value: V) {
        remove(key)
        put(key, value)
        while (size > capacity) remove(keys.first())
    }
}

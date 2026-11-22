package com.ambient.tv.ui.navigation

/** The same numeric title ID on another login is a new start, never an existing-session reopen. */
internal fun mayReusePlaybackSession(
    requestedRoute: String,
    currentRoute: String?,
    active: Boolean,
    sessionIdentity: String?,
    currentIdentity: String,
): Boolean = active && requestedRoute == currentRoute &&
    (!(requestedRoute.startsWith("player/") || requestedRoute.startsWith("tvh_player/")) ||
        sessionIdentity == currentIdentity)

/** No delayed check may outlive the originating page, source account or chosen title. */
internal class PlaybackStartRequests {
    data class Ticket(val serial: Long, val key: String, val origin: String?, val source: String)
    private var serial = 0L
    private var active: Ticket? = null

    /** A repeated Play press joins the existing intent, including its open retry panel. */
    fun begin(key: String, origin: String?, source: String): Ticket? {
        if (active?.let { it.key == key && it.origin == origin && it.source == source } == true) return null
        return Ticket(++serial, key, origin, source).also { active = it }
    }

    fun current(ticket: Ticket, origin: String?, source: String): Boolean =
        active == ticket && ticket.origin == origin && ticket.source == source

    fun finish(ticket: Ticket) { if (active == ticket) active = null }
    fun cancel() { active = null; serial++ }
}

package com.ambient.tv.ui.navigation

/** One browse choice owns delayed radio/target callbacks, even while its page stays open. */
internal class BrowseSelectionIntents(
    private val cancelPlayback: () -> Unit,
    private val clearPending: () -> Unit,
) {
    data class Ticket internal constructor(val serial: Long)

    private var serial = 0L

    /**
     * The video resolver already replaces/coalesces its own PlaybackStartRequests ticket.
     * Other choices must retire that ticket even when they only open an Ask dialog.
     */
    fun begin(keepPlaybackRequest: Boolean = false): Ticket {
        val ticket = Ticket(++serial)
        if (!keepPlaybackRequest) cancelPlayback()
        clearPending()
        return ticket
    }

    fun capture(): Ticket = Ticket(serial)
    fun current(ticket: Ticket): Boolean = ticket.serial == serial
}

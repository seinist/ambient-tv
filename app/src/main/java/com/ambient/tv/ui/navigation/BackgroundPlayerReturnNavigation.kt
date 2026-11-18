package com.ambient.tv.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Player destinations that can reattach to a running background playback session. */
internal val PLAYBACK_DESTINATIONS = setOf(
    Destinations.PLAYER, Destinations.TVH_PLAYER, Destinations.YOUTUBE_PLAYER,
    Destinations.TELEGRAM_PLAYER, Destinations.SPOTIFY_PLAYER, Destinations.RADIO_PLAYER,
)

/**
 * The TV sidebar's second Back press opens the active fullscreen player. The browse page
 * beneath it is the one the user was actually viewing, NOT the media's launch origin.
 *
 * A reopened player does not send "focus the playing item" commands when returning to that page.
 */
internal class BackgroundPlayerReturnNavigation {
    var armedBySidebarBack by mutableStateOf(false)
        private set

    private var reopenedPlayerEntryId: String? = null
    private var inheritReentryForNextPlayer = false

    fun enterPlayer(entryId: String, previousIsPlayer: Boolean) {
        if (reopenedPlayerEntryId != entryId) {
            // In-player Next or a player-to-player transition retains the current browse page.
            // A newly selected item from a browse page begins a normal playback return instead.
            reopenedPlayerEntryId = entryId.takeIf {
                reopenedPlayerEntryId != null && (previousIsPlayer || inheritReentryForNextPlayer)
            }
        }
        inheritReentryForNextPlayer = false
        armedBySidebarBack = false
    }

    fun markMiniPlayerReentry(entryId: String?) {
        reopenedPlayerEntryId = entryId
        inheritReentryForNextPlayer = false
        armedBySidebarBack = false
    }

    fun isMiniPlayerReentry(entryId: String?): Boolean =
        entryId != null && reopenedPlayerEntryId == entryId

    /** The existing browse entry, its selected category and its focus are left untouched. */
    fun enterBrowse() {
        armedBySidebarBack = false
        reopenedPlayerEntryId = null
        inheritReentryForNextPlayer = false
    }

    /** A Next/Previous shortcut may replace the current player entry before navigating again. */
    fun markNextPlayerContinuation() {
        inheritReentryForNextPlayer = reopenedPlayerEntryId != null
        armedBySidebarBack = false
    }

    fun armAfterSidebarBack(hasMiniPlayer: Boolean) {
        armedBySidebarBack = hasMiniPlayer
    }

    fun canResumeAfterSidebarBack(hasActivePlayback: Boolean): Boolean =
        armedBySidebarBack && hasActivePlayback

    fun disarm() { armedBySidebarBack = false }

    fun clearStoppedPlayback() {
        armedBySidebarBack = false
        reopenedPlayerEntryId = null
        inheritReentryForNextPlayer = false
    }
}

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
 * A one-shot Back from browsing resumes active background playback after either collapsing
 * the TV sidebar or minimizing with the fullscreen-exit OSD button.
 * The browse page and its focused item are never replaced by the media's launch origin.
 */
internal class BackgroundPlayerReturnNavigation {
    var armedBySidebarBack by mutableStateOf(false)
        private set

    private var reopenedPlayerEntryId: String? = null
    private var inheritReentryForNextPlayer = false
    /** Set before an OSD exit callback pops its player destination; consumed on browse entry. */
    private var fullscreenExitEntryId: String? = null

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
        fullscreenExitEntryId = null
        armedBySidebarBack = false
    }

    /** Called by the shared fullscreen-exit OSD control, before the player's onBack callback. */
    fun beginFullscreenExit(entryId: String?) {
        if (entryId == null) return
        fullscreenExitEntryId = entryId
        armedBySidebarBack = true
    }

    /** Do not focus a played item/episode when this exit only minimized a running session. */
    fun preservesBrowseContextOnExit(entryId: String?): Boolean =
        entryId != null && (entryId == reopenedPlayerEntryId || entryId == fullscreenExitEntryId)

    fun isMiniPlayerReentry(entryId: String?): Boolean =
        entryId != null && reopenedPlayerEntryId == entryId

    /** Only a fullscreen-exit transition arms Back on the newly revealed browse destination. */
    fun enterBrowse() {
        armedBySidebarBack = fullscreenExitEntryId != null
        fullscreenExitEntryId = null
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
        fullscreenExitEntryId = null
    }
}

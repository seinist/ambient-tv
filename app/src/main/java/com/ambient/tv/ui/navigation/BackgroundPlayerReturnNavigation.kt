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
    /** Root Back opened the menu while a session plays on: Back in the menu returns to it. */
    private var menuBackReturnsToPlayer = false

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
        menuBackReturnsToPlayer = false
    }

    /** Fullscreen → Back → page → Back opens the menu; the next Back belongs to the player chain. */
    fun menuOpenedByBack(hasActivePlayback: Boolean) {
        menuBackReturnsToPlayer = hasActivePlayback
    }

    /** One-shot: true when Back closed a menu that Back had opened during active playback. */
    fun consumeMenuBackToPlayer(hasActivePlayback: Boolean): Boolean =
        (menuBackReturnsToPlayer && hasActivePlayback).also { menuBackReturnsToPlayer = false }

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
        menuBackReturnsToPlayer = false
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

    /** Detail pages have no sidebar; a stale sidebar-focus flag must not swallow their Back. */
    fun canInterceptBackToPlayer(
        route: String?,
        hasActivePlayback: Boolean,
        sidebarVisible: Boolean,
        sidebarHasFocus: Boolean,
    ): Boolean =
        route != null &&
            route !in PLAYBACK_DESTINATIONS &&
            route != Destinations.SETTINGS &&
            canResumeAfterSidebarBack(hasActivePlayback) &&
            (!sidebarVisible || !sidebarHasFocus)

    fun disarm() { armedBySidebarBack = false }

    fun clearStoppedPlayback() {
        armedBySidebarBack = false
        reopenedPlayerEntryId = null
        inheritReentryForNextPlayer = false
        fullscreenExitEntryId = null
        menuBackReturnsToPlayer = false
    }
}

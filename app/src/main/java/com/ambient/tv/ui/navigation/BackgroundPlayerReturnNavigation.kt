package com.ambient.tv.ui.navigation

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry

/** Sources whose fullscreen screen can be reopened from a live mini-player. */
internal val PLAYBACK_DESTINATIONS = setOf(
    Destinations.PLAYER, Destinations.TVH_PLAYER, Destinations.YOUTUBE_PLAYER,
    Destinations.TELEGRAM_PLAYER, Destinations.SPOTIFY_PLAYER, Destinations.RADIO_PLAYER,
)

internal data class BackgroundPlayerOrigin(val route: String, val entryId: String)

/** The route is a Navigation pattern, but the return address must contain its actual arguments. */
internal fun concretePlaybackOriginRoute(pattern: String?, arguments: Map<String, Any?>): String? {
    if (pattern.isNullOrBlank()) return null
    val placeholder = Regex("""\{([^{}]+)\}""")
    var missingPath = false
    val path = placeholder.replace(pattern.substringBefore('?')) { match ->
        val value = arguments[match.groupValues[1]]?.toString()
        if (value == null) {
            missingPath = true
            ""
        } else Uri.encode(value)
    }
    if (missingPath) return null
    val query = pattern.substringAfter('?', "").split('&').mapNotNull { field ->
        if (field.isEmpty()) return@mapNotNull null
        var missingField = false
        val encoded = placeholder.replace(field) { match ->
            val value = arguments[match.groupValues[1]]?.toString()
            if (value == null) {
                missingField = true
                ""
            } else Uri.encode(value)
        }
        encoded.takeUnless { missingField }
    }
    return if (query.isEmpty()) path else path + "?" + query.joinToString("&")
}

/** Only immutable identities are kept; no Activity, NavBackStackEntry, ViewModel or FocusRequester. */
@Suppress("DEPRECATION")
internal fun playbackBrowseOrigin(entry: NavBackStackEntry?): BackgroundPlayerOrigin? {
    val source = entry ?: return null
    if (source.destination.route in PLAYBACK_DESTINATIONS) return null
    val args = source.arguments?.let { bundle ->
        bundle.keySet().associateWith { key -> bundle.get(key) }
    }.orEmpty()
    val route = concretePlaybackOriginRoute(source.destination.route, args) ?: return null
    return BackgroundPlayerOrigin(route, source.id)
}

/**
 * One-shot Back chain + playback origin. The origin survives sidebar navigation, but is never
 * replaced just because a running player is brought back to fullscreen via its mini-player.
 */
internal class BackgroundPlayerReturnNavigation {
    var origin by mutableStateOf<BackgroundPlayerOrigin?>(null)
        private set
    var armedBySidebarBack by mutableStateOf(false)
        private set

    private var lastPlayerEntryId: String? = null
    private var reopenedPlayerEntryId: String? = null

    fun enterPlayer(entryId: String, predecessor: BackgroundPlayerOrigin?) {
        if (lastPlayerEntryId == entryId) return
        lastPlayerEntryId = entryId
        if (reopenedPlayerEntryId != entryId) origin = predecessor
        reopenedPlayerEntryId = null
        armedBySidebarBack = false
    }

    fun markMiniPlayerReentry(entryId: String?) {
        reopenedPlayerEntryId = entryId
        armedBySidebarBack = false
    }

    fun enterBrowse() { armedBySidebarBack = false }

    fun armAfterSidebarBack(hasMiniPlayer: Boolean) {
        armedBySidebarBack = hasMiniPlayer
    }

    fun canResumeAfterSidebarBack(hasActivePlayback: Boolean): Boolean =
        armedBySidebarBack && hasActivePlayback

    fun disarm() { armedBySidebarBack = false }

    fun clearStoppedPlayback() {
        origin = null
        armedBySidebarBack = false
        reopenedPlayerEntryId = null
        lastPlayerEntryId = null
    }
}

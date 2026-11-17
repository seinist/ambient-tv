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

internal data class BackgroundPlayerOrigin(
    /** The screen and selected in-place category to show if the original entry was removed. */
    val route: String,
    val entryId: String,
    /** Actual argument route of the existing NavBackStackEntry, which may show another chip. */
    val backStackRoute: String = route,
)

/** Some TV browse chips switch category in-place without updating the NavBackStackEntry. */
internal fun displayedPlaybackBrowseRoute(
    stackRoute: String,
    destination: String?,
    youtubeFeedKey: String?,
    youtubeStartFeedKey: String,
    telegramSectionKey: String?,
    radioSectionKey: String?,
): String = when (destination) {
    Destinations.YOUTUBE, Destinations.YOUTUBE_FEED -> youtubeFeedKey?.let {
        Destinations.youtubeSection(it, youtubeStartFeedKey)
    } ?: stackRoute
    Destinations.TELEGRAM, Destinations.TELEGRAM_SECTION -> telegramSectionKey?.let(Destinations::telegramSection)
        ?: stackRoute
    Destinations.RADIO, Destinations.RADIO_SECTION -> radioSectionKey?.let(Destinations::radioSection)
        ?: stackRoute
    else -> stackRoute
}

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
        // Auto-Next and in-player link navigation may replace one player entry with another
        // without a browse entry underneath. That is still the same playback origin.
        if (reopenedPlayerEntryId != entryId && predecessor != null) origin = predecessor
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

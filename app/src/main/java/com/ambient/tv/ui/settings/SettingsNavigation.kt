package com.ambient.tv.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.ambient.tv.R
import java.util.Locale

internal val LocalSettingsSearchLanding = staticCompositionLocalOf<String?> { null }

internal enum class SettingsArea(val titleRes: Int) {
    START(R.string.settings_area_start),
    SYSTEMS(R.string.settings_area_systems),
    NETWORK(R.string.settings_area_network),
    PLAYBACK(R.string.settings_area_playback),
    APP(R.string.settings_area_app),
}

/** Declaration order is the display order within each area. */
internal enum class SettingsCategory(val area: SettingsArea, val titleRes: Int, val hintRes: Int) {
    LANGUAGE(SettingsArea.START, R.string.settings_language_title, R.string.settings_page_language_hint),
    APPEARANCE(SettingsArea.START, R.string.settings_appearance_title, R.string.settings_page_appearance_hint),
    BACKGROUND(SettingsArea.START, R.string.settings_background_page_title, R.string.settings_page_background_hint),
    HOME(SettingsArea.START, R.string.settings_home_rows_title, R.string.settings_page_home_hint),
    LIBRARY(SettingsArea.START, R.string.settings_library_title, R.string.settings_page_library_hint),
    /** Media-card activation behavior (formerly titled Navigation). */
    MEDIA_SELECTION(SettingsArea.START, R.string.settings_media_selection_title, R.string.settings_page_media_selection_hint),
    SCREENSAVER(SettingsArea.START, R.string.settings_screensaver_title, R.string.settings_page_screensaver_hint),
    NAV_SOUNDS(SettingsArea.START, R.string.settings_nav_sounds_title, R.string.settings_nav_sounds_page_hint),
    /** Plex system page (legacy enum name NETWORK kept as fromKey alias). */
    PLEX(SettingsArea.SYSTEMS, R.string.settings_network_title, R.string.settings_page_network_hint),
    JELLYFIN(SettingsArea.SYSTEMS, R.string.settings_jellyfin_title, R.string.settings_page_jellyfin_hint),
    EMBY(SettingsArea.SYSTEMS, R.string.settings_emby_title, R.string.settings_page_emby_hint),
    TVHEADEND(SettingsArea.SYSTEMS, R.string.settings_tvh_title, R.string.settings_page_tvheadend_hint),
    DLNA(SettingsArea.SYSTEMS, R.string.dlna_title, R.string.settings_page_dlna_hint),
    FILES(SettingsArea.SYSTEMS, R.string.files_title, R.string.settings_page_files_hint),
    YOUTUBE(SettingsArea.SYSTEMS, R.string.settings_youtube_group, R.string.settings_page_youtube_hint),
    SPOTIFY(SettingsArea.SYSTEMS, R.string.settings_spotify_title, R.string.settings_page_spotify_hint),
    TRAILERS(SettingsArea.SYSTEMS, R.string.trailers_title, R.string.trailers_setup_hint),
    /** M3U playlists with XMLTV guides; offered as a setup card until a playlist is saved. */
    IPTV(SettingsArea.SYSTEMS, R.string.iptv_title, R.string.iptv_settings_hint),
    COMPANION(SettingsArea.NETWORK, R.string.settings_network_devices, R.string.settings_companion_hint),
    RECEIVER(SettingsArea.NETWORK, R.string.settings_network_receiver, R.string.renderer_settings_hint),
    /** WireGuard tunnel page (legacy enum name VPN kept as fromKey alias). */
    WIREGUARD(SettingsArea.NETWORK, R.string.settings_vpn_title, R.string.settings_page_vpn_hint),
    WAKE(SettingsArea.NETWORK, R.string.settings_network_wake, R.string.settings_network_wake_hint),
    PLAYBACK(SettingsArea.PLAYBACK, R.string.settings_player_page_title, R.string.settings_page_playback_hint),
    LIVE_TV(SettingsArea.PLAYBACK, R.string.settings_live_tv_title, R.string.settings_page_live_tv_hint),
    IMAGE(SettingsArea.PLAYBACK, R.string.settings_hub_image, R.string.settings_page_image_hint),
    AUDIO(SettingsArea.PLAYBACK, R.string.settings_hub_audio, R.string.settings_page_audio_hint),
    SUBTITLES(SettingsArea.PLAYBACK, R.string.subtitle_policy_title, R.string.settings_page_subtitles_hint),
    /** Seek jump distance. */
    SEEK(SettingsArea.PLAYBACK, R.string.settings_seek_page_title, R.string.settings_page_seek_hint),
    NEXT_UP(SettingsArea.PLAYBACK, R.string.settings_skipping_title, R.string.settings_page_next_up_hint),
    VISUALIZER(SettingsArea.PLAYBACK, R.string.settings_audio_visualizer, R.string.settings_page_visualizer_hint),
    PHOTOS(SettingsArea.PLAYBACK, R.string.settings_photos_title, R.string.settings_page_photos_hint),
    NOTIFICATIONS(SettingsArea.APP, R.string.notice_history, R.string.notice_history_hint),
    TRANSFER(SettingsArea.APP, R.string.settings_companion_transfer, R.string.settings_companion_transfer_hint),
    BACKUP(SettingsArea.APP, R.string.settings_backup_title, R.string.settings_page_backup_hint),
    DIAGNOSTICS(SettingsArea.APP, R.string.settings_diagnostics_title, R.string.settings_page_diagnostics_hint),
    /** Pointer page; Spotify developer client ID lives on the Spotify system page. */
    ADVANCED(SettingsArea.APP, R.string.settings_advanced_title, R.string.settings_page_advanced_hint),
    ABOUT(SettingsArea.APP, R.string.settings_about_title, R.string.settings_page_about_hint),
;

    companion object {
        fun fromKey(key: String?): SettingsCategory? = when (key?.lowercase(Locale.ROOT)) {
            null, "" -> null
            "plex", "network" -> PLEX
            "vpn", "wireguard" -> WIREGUARD
            "upnp", "receiver", "renderer" -> RECEIVER
            "wol", "wake", "mac" -> WAKE
            "sync", "transfer" -> TRANSFER
            "tvh", "tvheadend" -> TVHEADEND
            "iptv", "m3u" -> IPTV
            "live_tv", "livetv", "timeshift", "reminders" -> LIVE_TV
            "locale", "language" -> LANGUAGE
            "nav", "navigation", "media_selection", "mediaselection" -> MEDIA_SELECTION
            "companion", "phone", "handy", "devices" -> COMPANION
            "info", "about" -> ABOUT
            "libraries", "library" -> LIBRARY
            "picture", "video", "image" -> IMAGE
            "sound", "audio" -> AUDIO
            "nav_sounds", "navigation_sounds", "sound_editor" -> NAV_SOUNDS
            "photo", "photos", "slideshow" -> PHOTOS
            "seek", "playback_behavior" -> SEEK
            "log", "logs", "logging", "debug", "diagnostics" -> DIAGNOSTICS
            // Empty ADVANCED hub page removed; deep-link to the Spotify page (account section holds the client ID).
            "advanced", "developer", "clientid", "client_id" -> SPOTIFY
            else -> entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}

/** Search all terms, including aliases such as WOL, even when their words are reordered. */
internal fun settingsSearchMatches(query: String, text: String): Boolean {
    val terms = query.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val haystack = text.lowercase(Locale.ROOT)
    return terms.all(haystack::contains)
}

/**
 * Hub stack, one Left/Back each: input page → area index → area rail → global Settings.
 * The item that opened the next view stays selected and takes focus again on the way back.
 *
 * Directly opened pages also return to their parent area index.
 */
internal data class SettingsHubNav(
    val page: SettingsCategory? = null,
    val search: Boolean = false,
    val returnToSearch: Boolean = false,
    val areaMenu: Boolean = false,
) {
    fun pop(): SettingsHubNav? = when {
        page != null -> when {
            returnToSearch -> copy(
                page = null,
                search = true,
                areaMenu = false,
            )
            else -> copy(page = null, search = false, areaMenu = false)
        }
        search -> copy(search = false, returnToSearch = false, areaMenu = false)
        !areaMenu -> copy(areaMenu = true)
        else -> null
    }

    fun leftClosesPage(): Boolean = page != null
}

/** Registered only while Settings is composed, including sidebar/miniplayer exits. */
typealias SettingsExitGuard = (action: () -> Unit) -> Unit


/** Approximate LazyColumn index to scroll to when opening a page from Settings search. */
internal fun settingsSearchScrollIndex(category: SettingsCategory, query: String): Int? {
    val q = query.lowercase()
    if (q.isBlank()) return null
    return when (category) {
        SettingsCategory.APPEARANCE -> when {
            listOf("hdr", "sdr").any { it in q } -> 1
            listOf("scale", "skal", "größe", "groesse", "size", "zoom").any { it in q } -> 3
            listOf("accent", "akzent", "colour", "color", "farbe").any { it in q } -> 5
            listOf("theme", "thema", "oled", "dim", "dark", "dunkel").any { it in q } -> 2
            else -> 0
        }
        SettingsCategory.SEEK -> 0
        SettingsCategory.PLAYBACK -> when {
            listOf("drop", "bildaussetzer", "prüfdauer", "duration").any { it in q } -> 1
            listOf("backend", "player", "mpv", "vlc", "media3").any { it in q } -> 3
            else -> 0
        }
        SettingsCategory.BACKGROUND -> when {
            listOf("video", "dim", "abdunk", "opacity").any { it in q } -> 1
            else -> 0
        }
        SettingsCategory.SPOTIFY -> when {
            listOf("developer", "entwickler", "client", "advanced").any { it in q } -> 3
            else -> 0
        }
        else -> null
    }
}

/** Prefer Start-page tab when search mentions home widgets; otherwise submenu chips. */
internal fun settingsSearchPrefersStartPageTab(query: String): Boolean {
    val q = query.lowercase()
    return listOf("home", "start", "widget", "reihe", "row").any { it in q }
}

/** The Wake page lists saved server profiles above the machine groups when there is a choice to make. */
internal fun hasWakeProfilePicker(state: SettingsUiState): Boolean =
    state.savedServers.size > 1 || state.savedJellyfinServers.size > 1 || state.savedEmbyServers.size > 1

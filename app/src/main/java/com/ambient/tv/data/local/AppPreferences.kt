package com.ambient.tv.data.local

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import androidx.core.content.edit
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.EmbyServerProfile
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.ui.theme.normalizedAccentKey
import com.ambient.tv.vpn.WireGuardProfile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Singleton
class AppPreferences @Inject constructor(
    @RegularPrefs private val prefs: SharedPreferences,
    @TitleSettingsPrefs private val titlePrefs: SharedPreferences = prefs,
) {
    private val json = Json { ignoreUnknownKeys = true }
    /** Media servers the "Continue watching · all systems" Home row takes titles from (default: all). */
    var allContinueSystems: Set<String>
        get() = prefs.getStringSet("all_continue_systems", null)?.toSet() ?: ALL_CONTINUE_DEFAULT_SYSTEMS
        set(value) = prefs.edit { putStringSet("all_continue_systems", value.toSet()) }

    /** YouTube "Watch on TV": linked phones may send YouTube videos to this app (off by default). */
    var youtubeTvLinkEnabled: Boolean
        get() = prefs.getBoolean("youtube_tv_link_enabled", false)
        set(value) = prefs.edit { putBoolean("youtube_tv_link_enabled", value) }

    /** Diagnostic log for release builds (Settings → Diagnostics); off by default, device-local. */
    var diagnosticLogEnabled: Boolean
        get() = prefs.getBoolean("diagnostic_log_enabled", false)
        set(value) = prefs.edit { putBoolean("diagnostic_log_enabled", value) }

    /** Receiver identity is device-local, like its UDN and trusted senders. */
    var dlnaRendererName: String
        get() = prefs.getString("dlna_renderer_name", "").orEmpty()
        set(value) = prefs.edit { putString("dlna_renderer_name", value.trim().take(80)) }

    /** DLNA MediaRenderer: other apps on the LAN may send media to this app (off by default). */
    var dlnaRendererEnabled: Boolean
        get() = prefs.getBoolean("dlna_renderer_enabled", false)
        set(value) = prefs.edit { putBoolean("dlna_renderer_enabled", value) }
    /** Ask before playing from a sender not marked "always". */
    var dlnaRendererAsk: Boolean
        get() = prefs.getBoolean("dlna_renderer_ask", true)
        set(value) = prefs.edit { putBoolean("dlna_renderer_ask", value) }
    /** Senders (app and address, as the renderer prompt shows them) allowed without asking. */
    var dlnaRendererTrusted: Set<String>
        get() = prefs.getStringSet("dlna_renderer_trusted", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet("dlna_renderer_trusted", value.toSet()) }
    /** Stable UPnP device id, so control points keep recognizing this renderer. */
    val dlnaRendererUdn: String
        @Synchronized get() = prefs.getString("dlna_renderer_udn", null) ?: "uuid:${java.util.UUID.randomUUID()}".also {
            prefs.edit { putString("dlna_renderer_udn", it) }
        }

    /** Watch Next and preview channels on the Android TV home screen. */
    var launcherIntegrationEnabled: Boolean
        get() = prefs.getBoolean("launcher_integration_enabled", true)
        set(value) = prefs.edit { putBoolean("launcher_integration_enabled", value) }

    var trailersEnabled: Boolean
        get() = prefs.getBoolean("trailers_enabled", true)
        set(value) = prefs.edit { putBoolean("trailers_enabled", value) }
    var trailerLanguage: String
        get() = prefs.getString("trailer_language", "auto").orEmpty().takeIf { it in setOf("auto", "de", "en") } ?: "auto"
        set(value) = prefs.edit { putString("trailer_language", value) }

    /** Custom display names per system key; see [SystemNameOverrides]. */
    var systemNames: Map<String, String>
        get() = runCatching { json.decodeFromString<Map<String, String>>(prefs.getString("system_names", "{}")!!) }
            .getOrDefault(emptyMap()).let(SystemNameOverrides::normalized)
        set(value) {
            val names = SystemNameOverrides.normalized(value)
            prefs.edit { putString("system_names", json.encodeToString(names)) }
            SystemNameOverrides.names = names
        }

    init {
        SystemNameOverrides.names = systemNames
    }

    /** Main-menu entries that have a divider below them (menu editor). Missing key = none. */
    var sidebarDividersAfter: Set<String>
        // Ids of systems that no longer exist are dropped on load (Search/Home are the globals).
        get() = prefs.getString("sidebar_dividers_after", "").orEmpty().split(',')
            .filter { it in SystemNameOverrides.KEYS || it == "search" || it == "home" }.toSet()
        set(value) = prefs.edit { putString("sidebar_dividers_after", value.filter { it.isNotBlank() }.sorted().joinToString(",")) }

    /**
     * Per-system entry order of the menu (system id -> entry ids, e.g. "plex" -> ["plex:home", …]).
     * Unknown systems are dropped on load; unknown entry ids are ignored when the menu is built.
     */
    var sidebarEntryOrder: Map<String, List<String>>
        get() = decodeLevelMap(prefs.getString("sidebar_entry_order", null))
        set(value) = prefs.edit { putString("sidebar_entry_order", encodeLevelMap(value)) }

    /** Per-system entry ids with a divider below them (same keys as [sidebarEntryOrder]). */
    var sidebarEntryDividers: Map<String, List<String>>
        get() = decodeLevelMap(prefs.getString("sidebar_entry_dividers", null))
        set(value) = prefs.edit { putString("sidebar_entry_dividers", encodeLevelMap(value)) }

    private fun decodeLevelMap(raw: String?): Map<String, List<String>> = raw?.let {
        runCatching { Json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull()
    }.orEmpty().filterKeys { it in SystemNameOverrides.KEYS }
        .mapValues { (_, ids) -> ids.filter { it.isNotBlank() }.distinct() }
        .filterValues { it.isNotEmpty() }

    private fun encodeLevelMap(value: Map<String, List<String>>): String =
        Json.encodeToString<Map<String, List<String>>>(value.filterValues { it.isNotEmpty() }.toSortedMap().toMap(LinkedHashMap()))

    var sidebarSystemOrder: List<String>
        get() {
            val known = SystemNameOverrides.KEYS
            val saved = prefs.getString("sidebar_system_order", "").orEmpty().split(',').filter { it in known }.distinct()
            return saved + known.filterNot { it in saved }
        }
        set(value) = prefs.edit { putString("sidebar_system_order", value.distinct().joinToString(",")) }

    var filesEnabled: Boolean
        get() = prefs.getBoolean("files_enabled", false)
        set(value) = prefs.edit { putBoolean("files_enabled", value) }

    // Photo viewer / slideshow (all systems).
    var photoSlideshowIntervalSeconds: Int
        get() = prefs.getInt("photo_slideshow_interval", 6).coerceIn(3, 60)
        set(value) = prefs.edit { putInt("photo_slideshow_interval", value.coerceIn(3, 60)) }
    /** "fade", "slide", "none" */
    var photoTransitionKey: String
        get() = prefs.getString("photo_transition", "fade").orEmpty().ifBlank { "fade" }
        set(value) = prefs.edit { putString("photo_transition", value) }
    var photoKenBurns: Boolean
        get() = prefs.getBoolean("photo_ken_burns", false)
        set(value) = prefs.edit { putBoolean("photo_ken_burns", value) }
    var photoShuffle: Boolean
        get() = prefs.getBoolean("photo_shuffle", false)
        set(value) = prefs.edit { putBoolean("photo_shuffle", value) }
    var photoIncludeSubfolders: Boolean
        get() = prefs.getBoolean("photo_include_subfolders", false)
        set(value) = prefs.edit { putBoolean("photo_include_subfolders", value) }
    var photoSortKey: String
        get() = prefs.getString("photo_sort", "date_desc").orEmpty().ifBlank { "date_desc" }
        set(value) = prefs.edit { putString("photo_sort", value) }
    var photoShowInfo: Boolean
        get() = prefs.getBoolean("photo_show_info", false)
        set(value) = prefs.edit { putBoolean("photo_show_info", value) }
    /** Album key used by the "photos" screensaver mode; blank = recent photos of all libraries. */
    var screensaverPhotoAlbumKey: String
        get() = prefs.getString("screensaver_photo_album", "").orEmpty()
        set(value) = prefs.edit { putString("screensaver_photo_album", value) }
    var screensaverPhotoAlbumTitle: String
        get() = prefs.getString("screensaver_photo_album_title", "").orEmpty()
        set(value) = prefs.edit { putString("screensaver_photo_album_title", value) }

    internal val fileSourceLock = Any()

    /**
     * Profiles, Home rows and file sources are JSON lists read on many UI paths. Decoding is
     * reused while the stored string is unchanged; the parsed lists are immutable.
     */
    private val decodedLists = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> decodeCached(key: String, raw: String, decode: (String) -> T): T {
        decodedLists[key]?.let { (cachedRaw, value) -> if (cachedRaw == raw) return value as T }
        return decode(raw).also { decodedLists[key] = raw to it }
    }

    var fileSources: List<com.ambient.tv.data.files.FileSource>
        get() = runCatching { decodeCached("file_sources", prefs.getString("file_sources", "[]")!!) { json.decodeFromString<List<com.ambient.tv.data.files.FileSource>>(it) } }
            .getOrDefault(emptyList()).mapNotNull { runCatching { it.validated() }.getOrNull() }.distinctBy { it.id }.take(50)
        set(value) = synchronized(fileSourceLock) {
            val next = value.map { it.validated() }.distinctBy { it.id }.take(50)
            val previous = fileSources.associateBy { it.id }
            val reusable = next.filter { previous[it.id]?.sameLocation(it) == true }.map { it.id }.toSet()
            // A source ID can survive an edit/import while its NAS/share/root changes. Never
            // apply the old path's favorite or playback position to a different filesystem.
            val favorites = runCatching {
                json.decodeFromString<List<com.ambient.tv.data.files.FileEntry>>(prefs.getString("file_favorites", "[]")!!)
            }.getOrDefault(emptyList()).filter { it.sourceId in reusable }.takeLast(300)
            val history = runCatching {
                json.decodeFromString<List<com.ambient.tv.data.files.FileHistory>>(prefs.getString("file_history", "[]")!!)
            }.getOrDefault(emptyList()).filter { it.entry.sourceId in reusable }.take(500)
            prefs.edit {
                putString("file_sources", json.encodeToString(next))
                putString("file_favorites", json.encodeToString(favorites))
                putString("file_history", json.encodeToString(history))
            }
        }

    var plexEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLEX_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_PLEX_ENABLED, value) }

    var jellyfinEnabled: Boolean
        get() = prefs.getBoolean(KEY_JELLYFIN_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_JELLYFIN_ENABLED, value) }

    var jellyfinHost: String
        get() = prefs.getString(KEY_JELLYFIN_HOST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_JELLYFIN_HOST, NetworkEndpoint.normalizeHost(value)) }

    var jellyfinPort: Int
        get() = NetworkEndpoint.validPortOrDefault(prefs.getInt(KEY_JELLYFIN_PORT, DEFAULT_JELLYFIN_PORT), DEFAULT_JELLYFIN_PORT)
        set(value) = prefs.edit { putInt(KEY_JELLYFIN_PORT, NetworkEndpoint.validPortOrDefault(value, DEFAULT_JELLYFIN_PORT)) }

    var jellyfinUseHttps: Boolean
        get() = prefs.getBoolean(KEY_JELLYFIN_HTTPS, false)
        set(value) = prefs.edit { putBoolean(KEY_JELLYFIN_HTTPS, value) }

    var jellyfinBasePath: String
        get() = prefs.getString(KEY_JELLYFIN_BASE_PATH, "") ?: ""
        set(value) = prefs.edit { putString(KEY_JELLYFIN_BASE_PATH, normalizeBasePath(value)) }

    var wireGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_WG_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_WG_ENABLED, value) }

    fun wireGuardProfile(): WireGuardProfile? =
        prefs.getString(KEY_WG_PROFILE, null)?.let { raw ->
            runCatching { json.decodeFromString<WireGuardProfile>(raw).normalized() }.getOrNull()
        }?.takeIf { it.ready() }

    var wireGuardPlexEnabled: Boolean
        get() = prefs.getBoolean(KEY_WG_PLEX, false)
        set(value) = prefs.edit { putBoolean(KEY_WG_PLEX, value) }

    var wireGuardJellyfinEnabled: Boolean
        get() = prefs.getBoolean(KEY_WG_JELLYFIN, false)
        set(value) = prefs.edit { putBoolean(KEY_WG_JELLYFIN, value) }

    var wireGuardTvheadendEnabled: Boolean
        get() = prefs.getBoolean(KEY_WG_TVHEADEND, false)
        set(value) = prefs.edit { putBoolean(KEY_WG_TVHEADEND, value) }

    fun setWireGuardProfile(profile: WireGuardProfile?) {
        prefs.edit {
            if (profile == null) remove(KEY_WG_PROFILE) else putString(KEY_WG_PROFILE, json.encodeToString(profile.normalized()))
        }
    }

    var jellyfinMacAddress: String?
        get() = prefs.getString(KEY_JELLYFIN_MAC, null)
        set(value) = prefs.edit { putString(KEY_JELLYFIN_MAC, value?.trim()?.takeIf { it.isNotBlank() }) }

    var jellyfinAutoWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_JELLYFIN_AUTO_WAKE, true)
        set(value) = prefs.edit { putBoolean(KEY_JELLYFIN_AUTO_WAKE, value) }

    var activeJellyfinServerId: String?
        get() = prefs.getString(KEY_ACTIVE_JELLYFIN_SERVER_ID, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE_JELLYFIN_SERVER_ID, value) }

    var jellyfinUserId: String?
        get() = prefs.getString(KEY_JELLYFIN_USER_ID, null)
        set(value) = prefs.edit { putString(KEY_JELLYFIN_USER_ID, value?.takeIf { it.isNotBlank() }) }

    var jellyfinUsername: String?
        get() = prefs.getString(KEY_JELLYFIN_USERNAME, null)
        set(value) = prefs.edit { putString(KEY_JELLYFIN_USERNAME, value?.trim()?.takeIf { it.isNotBlank() }) }

    var jellyfinHiddenSectionKeys: Set<String>
        get() = prefs.getString(KEY_JELLYFIN_HIDDEN_SECTION_KEYS, null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
        set(value) = prefs.edit { putString(KEY_JELLYFIN_HIDDEN_SECTION_KEYS, value.joinToString(",")) }

    /** The active Plex profile still describes the configured address (as for Jellyfin/Emby). */
    val matchesActivePlexEndpoint: Boolean
        get() {
            val id = activeServerId ?: return false
            val profile = serverProfiles.firstOrNull { it.id == id } ?: return false
            return profile.host.equals(host, true) && profile.port == port && profile.useHttps == plexUseHttps
        }

    val matchesActiveJellyfinEndpoint: Boolean
        get() {
            val profile = activeJellyfinServerProfile() ?: return false
            return profile.host.equals(jellyfinHost, true) && profile.port == jellyfinPort &&
                profile.useHttps == jellyfinUseHttps && profile.basePath == jellyfinBasePath
        }

    val isJellyfinConfigured: Boolean
        get() {
            val profile = activeJellyfinServerProfile() ?: return false
            return jellyfinEnabled && matchesActiveJellyfinEndpoint &&
                jellyfinHost.isNotBlank() &&
                !jellyfinUserId.isNullOrBlank() &&
                profile.authenticated &&
                "jellyfin:${profile.id}" !in loginRejected
        }

    var embyEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMBY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_EMBY_ENABLED, value) }

    var embyHost: String
        get() = prefs.getString(KEY_EMBY_HOST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_EMBY_HOST, NetworkEndpoint.normalizeHost(value)) }

    var embyPort: Int
        get() = NetworkEndpoint.validPortOrDefault(prefs.getInt(KEY_EMBY_PORT, DEFAULT_EMBY_PORT), DEFAULT_EMBY_PORT)
        set(value) = prefs.edit { putInt(KEY_EMBY_PORT, NetworkEndpoint.validPortOrDefault(value, DEFAULT_EMBY_PORT)) }

    var embyUseHttps: Boolean
        get() = prefs.getBoolean(KEY_EMBY_HTTPS, false)
        set(value) = prefs.edit { putBoolean(KEY_EMBY_HTTPS, value) }

    var embyBasePath: String
        get() = prefs.getString(KEY_EMBY_BASE_PATH, "") ?: ""
        set(value) = prefs.edit { putString(KEY_EMBY_BASE_PATH, normalizeBasePath(value)) }

    var wireGuardEmbyEnabled: Boolean
        get() = prefs.getBoolean(KEY_WG_EMBY, false)
        set(value) = prefs.edit { putBoolean(KEY_WG_EMBY, value) }

    var embyMacAddress: String?
        get() = prefs.getString(KEY_EMBY_MAC, null)
        set(value) = prefs.edit { putString(KEY_EMBY_MAC, value?.trim()?.takeIf { it.isNotBlank() }) }

    var embyAutoWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMBY_AUTO_WAKE, true)
        set(value) = prefs.edit { putBoolean(KEY_EMBY_AUTO_WAKE, value) }

    var activeEmbyServerId: String?
        get() = prefs.getString(KEY_ACTIVE_EMBY_SERVER_ID, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE_EMBY_SERVER_ID, value) }

    var embyUserId: String?
        get() = prefs.getString(KEY_EMBY_USER_ID, null)
        set(value) = prefs.edit { putString(KEY_EMBY_USER_ID, value?.takeIf { it.isNotBlank() }) }

    var embyUsername: String?
        get() = prefs.getString(KEY_EMBY_USERNAME, null)
        set(value) = prefs.edit { putString(KEY_EMBY_USERNAME, value?.trim()?.takeIf { it.isNotBlank() }) }

    var embyHiddenSectionKeys: Set<String>
        get() = prefs.getString(KEY_EMBY_HIDDEN_SECTION_KEYS, null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
        set(value) = prefs.edit { putString(KEY_EMBY_HIDDEN_SECTION_KEYS, value.joinToString(",")) }

    val matchesActiveEmbyEndpoint: Boolean
        get() {
            val profile = activeEmbyServerProfile() ?: return false
            return profile.host.equals(embyHost, true) && profile.port == embyPort &&
                profile.useHttps == embyUseHttps && profile.basePath == embyBasePath
        }

    val isEmbyConfigured: Boolean
        get() {
            val profile = activeEmbyServerProfile() ?: return false
            return embyEnabled && matchesActiveEmbyEndpoint &&
                embyHost.isNotBlank() &&
                !embyUserId.isNullOrBlank() &&
                profile.authenticated &&
                "emby:${profile.id}" !in loginRejected
        }

    var youtubeEnabled: Boolean
        get() = prefs.getBoolean(KEY_YOUTUBE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_YOUTUBE_ENABLED, value) }

    var spotifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPOTIFY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SPOTIFY_ENABLED, value) }

    var spotifyClientId: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SPOTIFY_CLIENT_ID, value.trim()) }

    var spotifyDeviceName: String
        get() = prefs.getString(KEY_SPOTIFY_DEVICE_NAME, com.ambient.tv.branding.AppBrand.name) ?: com.ambient.tv.branding.AppBrand.name
        set(value) = prefs.edit { putString(KEY_SPOTIFY_DEVICE_NAME, value.trim().ifBlank { com.ambient.tv.branding.AppBrand.name }) }

    /** False until the user (or the first receiver start) chose a Connect name. */
    val spotifyDeviceNameStored: Boolean
        get() = prefs.contains(KEY_SPOTIFY_DEVICE_NAME)

    val spotifyDeviceId: String
        get() = prefs.getString(KEY_SPOTIFY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_SPOTIFY_DEVICE_ID, id) }
        }

    var spotifyBitrateKbps: Int
        get() = prefs.getInt(KEY_SPOTIFY_BITRATE, 320).takeIf { it in setOf(96, 160, 320) } ?: 320
        set(value) = prefs.edit { putInt(KEY_SPOTIFY_BITRATE, value.takeIf { it in setOf(96, 160, 320) } ?: 320) }

    var spotifyVisualizerModeKey: String
        get() = prefs.getString(KEY_SPOTIFY_VISUALIZER, "cover") ?: "cover"
        set(value) = prefs.edit { putString(KEY_SPOTIFY_VISUALIZER, value) }

    var spotifySubmenuKeys: List<String>
        get() {
            val raw = prefs.getString(KEY_SPOTIFY_SUBMENU_KEYS, null) ?: return DEFAULT_SPOTIFY_SUBMENU_KEYS
            return raw.split(',').map(String::trim).filter { it in SPOTIFY_SUBMENU_KEYS }.distinct()
        }
        set(value) = prefs.edit { putString(KEY_SPOTIFY_SUBMENU_KEYS, value.filter { it in SPOTIFY_SUBMENU_KEYS }.distinct().joinToString(",")) }

    var tvheadendSubmenuKeys: List<String>
        get() {
            val raw = prefs.getString(KEY_TVH_SUBMENU_KEYS, null) ?: return DEFAULT_TVH_SUBMENU_KEYS
            return raw.split(',').map(String::trim).filter { it in TVH_SUBMENU_KEYS }.distinct()
        }
        set(value) = prefs.edit { putString(KEY_TVH_SUBMENU_KEYS, value.filter { it in TVH_SUBMENU_KEYS }.distinct().joinToString(",")) }

    var plexHiddenSectionKeys: Set<String>
        get() = prefs.getString(KEY_PLEX_HIDDEN_SECTION_KEYS, null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
        set(value) = prefs.edit { putString(KEY_PLEX_HIDDEN_SECTION_KEYS, value.joinToString(",")) }

    /** Systems whose sidebar submenu leaves out the shortcut to their Settings page. */
    var sidebarSettingsShortcutHidden: Set<String>
        get() = prefs.getString(KEY_SIDEBAR_SETTINGS_SHORTCUT_HIDDEN, null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
        set(value) = prefs.edit { putString(KEY_SIDEBAR_SETTINGS_SHORTCUT_HIDDEN, value.joinToString(",")) }

    /** Rows switched off on a media server's own start page, as "system.rowType". */
    var systemHomeHiddenRows: Set<String>
        get() = prefs.getString(KEY_SYSTEM_HOME_HIDDEN_ROWS, null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            ?: DEFAULT_SYSTEM_HOME_HIDDEN_ROWS
        set(value) = prefs.edit { putString(KEY_SYSTEM_HOME_HIDDEN_ROWS, value.joinToString(",")) }

    /** Home rows are intentionally independent from sidebar visibility. A hidden sidebar child
     * may still be useful as a Home shortcut. Older installations get their former Home layout. */
    var homeRows: List<HomeRowDefinition>
        get() {
            val raw = prefs.getString(KEY_HOME_ROWS, null) ?: return legacyHomeRows()
            val rows = runCatching { decodeCached(KEY_HOME_ROWS, raw) { json.decodeFromString<List<HomeRowDefinition>>(it) } }
                .getOrDefault(legacyHomeRows())
                .filter { it.system in HOME_SYSTEMS && it.displayMode in HomeRowDefinition.DISPLAY_MODES }
                .distinctBy { it.system to it.contentKey }
            if (prefs.getBoolean(KEY_HOME_ROWS_DEFAULT_POSTER, false)) return rows
            val migrated = rows.withDefaultPosterCards()
            prefs.edit {
                putBoolean(KEY_HOME_ROWS_DEFAULT_POSTER, true)
                putString(KEY_HOME_ROWS, json.encodeToString(migrated))
            }
            return migrated
        }
        set(value) = prefs.edit {
            putBoolean(KEY_HOME_ROWS_DEFAULT_POSTER, true)
            putString(KEY_HOME_ROWS, json.encodeToString(value.filter {
                it.system in HOME_SYSTEMS && it.displayMode in HomeRowDefinition.DISPLAY_MODES
            }.distinctBy { it.system to it.contentKey }))
        }

    fun resetHomeRowsToLegacy() = prefs.edit { remove(KEY_HOME_ROWS) }

    private fun legacyHomeRows(): List<HomeRowDefinition> = buildList {
        if (showContinueWatchingRow) add(HomeRowDefinition(id = "legacy:plex:continue", system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "continue", displayMode = HomeRowDefinition.DISPLAY_POSTER))
        if (showRecentHomeRows) add(HomeRowDefinition(id = "legacy:plex:recent", system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "recent", displayMode = HomeRowDefinition.DISPLAY_POSTER))
        if (showCollectionHomeRows) add(HomeRowDefinition(id = "legacy:plex:collections", system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "collections", displayMode = HomeRowDefinition.DISPLAY_POSTER))
        if (showLibraryRowsOnHome) add(HomeRowDefinition(id = "legacy:plex:libraries", system = HomeRowDefinition.SYSTEM_PLEX, contentKey = "libraries", displayMode = HomeRowDefinition.DISPLAY_POSTER))
        if (tvheadendShowOnHome) add(HomeRowDefinition(id = "legacy:tvheadend:live", system = HomeRowDefinition.SYSTEM_TVHEADEND, contentKey = "live", displayMode = HomeRowDefinition.DISPLAY_LANDSCAPE))
    }

    var initialSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_SETUP_COMPLETED, false)
        set(value) = prefs.edit { putBoolean(KEY_INITIAL_SETUP_COMPLETED, value) }

    val needsInitialSetup: Boolean
        get() = !initialSetupCompleted && !filesEnabled && !isConfigured && !isJellyfinConfigured && !isEmbyConfigured && !isTvheadendConfigured

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) {
            val normalized = NetworkEndpoint.normalizeHost(value)
            if (normalized != host) plexLoginVerified = false
            prefs.edit { putString(KEY_HOST, normalized) }
        }

    var port: Int
        get() = NetworkEndpoint.validPortOrDefault(prefs.getInt(KEY_PORT, DEFAULT_PORT), DEFAULT_PORT)
        set(value) {
            val valid = NetworkEndpoint.validPortOrDefault(value, DEFAULT_PORT)
            if (valid != port) plexLoginVerified = false
            prefs.edit { putInt(KEY_PORT, valid) }
        }

    /** Prefer HTTPS for the active Plex PMS (reverse proxy / direct TLS). Default stays HTTP for classic LAN PMS. */
    var plexUseHttps: Boolean
        get() = prefs.getBoolean(KEY_PLEX_HTTPS, false)
        set(value) {
            if (value != plexUseHttps) plexLoginVerified = false
            prefs.edit { putBoolean(KEY_PLEX_HTTPS, value) }
        }

    var macAddress: String?
        get() = prefs.getString(KEY_MAC, null)
        set(value) = prefs.edit { putString(KEY_MAC, value) }

    var autoWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_WAKE, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_WAKE, value) }

    /** Extra Wake-on-LAN cycles (new burst + full wait) after the first, for every system and file/DLNA source. */
    var wolRetryCount: Int
        get() = com.ambient.tv.wol.normalizedWolRetryCount(prefs.getInt(KEY_WOL_RETRY_COUNT, com.ambient.tv.wol.WOL_RETRY_COUNT_DEFAULT))
        set(value) = prefs.edit { putInt(KEY_WOL_RETRY_COUNT, com.ambient.tv.wol.normalizedWolRetryCount(value)) }

    var activeServerId: String?
        get() = prefs.getString(KEY_ACTIVE_SERVER_ID, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE_SERVER_ID, value) }

    var uiLanguageKey: String
        get() = LanguagePreferences.normalizeKey(prefs.getString(KEY_UI_LANGUAGE, LanguagePreferences.AUTO))
        set(value) = prefs.edit { putString(KEY_UI_LANGUAGE, LanguagePreferences.normalizeKey(value)) }

    var metadataLanguageKey: String
        get() = LanguagePreferences.normalizeKey(prefs.getString(KEY_METADATA_LANGUAGE, LanguagePreferences.AUTO))
        set(value) = prefs.edit { putString(KEY_METADATA_LANGUAGE, LanguagePreferences.normalizeKey(value)) }

    /** Raw language preference. "auto" follows the app UI language at playback time. */
    var audioLanguageKey: String
        get() = LanguagePreferences.normalizeKey(prefs.getString(KEY_AUDIO_LANGUAGE, LanguagePreferences.AUTO))
        set(value) = prefs.edit { putString(KEY_AUDIO_LANGUAGE, LanguagePreferences.normalizeKey(value)) }

    val playbackAudioLanguage: String
        get() = LanguagePreferences.resolvePlayback(audioLanguageKey, uiLanguageKey, LanguagePreferences.systemLanguageTag())

    val playbackSubtitleLanguage: String
        get() = subtitlePreferences.targetLanguage(playbackAudioLanguage, LanguagePreferences.systemLanguageTag())

    /** Legacy/player-facing nullable facade. Null means Auto. */
    var preferredAudioLanguage: String?
        get() = audioLanguageKey.takeUnless { it == LanguagePreferences.AUTO }
        set(value) { audioLanguageKey = value ?: LanguagePreferences.AUTO }

    var preferredSubtitleLanguage: String?
        get() = prefs.getString(KEY_SUBTITLE_LANGUAGE, null)
        set(value) = prefs.edit { putString(KEY_SUBTITLE_LANGUAGE, value) }

    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SUBTITLES_ENABLED, value) }

    var forcedSubtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORCED_SUBTITLES_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_FORCED_SUBTITLES_ENABLED, value) }

    var subtitleModeKey: String
        get() = prefs.getString("subtitle_mode", null)?.let { SubtitleMode.fromKey(it).key }
            ?: when { forcedSubtitlesEnabled -> "forced"; subtitlesEnabled -> "full"; else -> "off" }
        set(value) = prefs.edit {
            val mode = SubtitleMode.fromKey(value)
            putString("subtitle_mode", mode.key)
            putBoolean(KEY_FORCED_SUBTITLES_ENABLED, mode == SubtitleMode.FORCED)
            putBoolean(KEY_SUBTITLES_ENABLED, mode == SubtitleMode.FULL)
        }

    var subtitleLanguageKey: String
        get() = LanguagePreferences.normalizeKey(prefs.getString("subtitle_language", if (forcedSubtitlesEnabled) "auto" else preferredSubtitleLanguage))
        set(value) = prefs.edit { putString("subtitle_language", LanguagePreferences.normalizeKey(value)) }

    var subtitleFallbackLanguageKey: String
        get() = prefs.getString("subtitle_fallback_language", "none") ?: "none"
        set(value) = prefs.edit { putString("subtitle_fallback_language", value.takeIf { it != "auto" && it.isNotBlank() } ?: "none") }

    var fullSubtitlesOnForeignAudio: Boolean
        get() = prefs.getBoolean("full_subtitles_on_foreign_audio", false)
        set(value) = prefs.edit { putBoolean("full_subtitles_on_foreign_audio", value) }

    var forcedSubtitlesInUiLanguage: Boolean
        get() = prefs.getBoolean("forced_subtitles_in_ui_language", false)
        set(value) = prefs.edit { putBoolean("forced_subtitles_in_ui_language", value) }

    val subtitlePreferences: SubtitlePreferences
        get() = SubtitlePreferences(SubtitleMode.fromKey(subtitleModeKey), subtitleLanguageKey,
            subtitleFallbackLanguageKey, fullSubtitlesOnForeignAudio, audioLanguageKey, forcedSubtitlesInUiLanguage, uiLanguageKey)

    var chooseServerOnStartup: Boolean
        get() = prefs.getBoolean(KEY_CHOOSE_SERVER_ON_STARTUP, true)
        set(value) = prefs.edit { putBoolean(KEY_CHOOSE_SERVER_ON_STARTUP, value) }


    var localNetworkOnly: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_NETWORK_ONLY, true)
        set(value) = prefs.edit { putBoolean(KEY_LOCAL_NETWORK_ONLY, value) }

    /** Detail pages play the item's theme song; one switch per system that serves theme music. */
    var plexThemeMusicEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLEX_THEME_MUSIC, false)
        set(value) = prefs.edit { putBoolean(KEY_PLEX_THEME_MUSIC, value) }

    var jellyfinThemeMusicEnabled: Boolean
        get() = prefs.getBoolean(KEY_JELLYFIN_THEME_MUSIC, false)
        set(value) = prefs.edit { putBoolean(KEY_JELLYFIN_THEME_MUSIC, value) }

    var embyThemeMusicEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMBY_THEME_MUSIC, false)
        set(value) = prefs.edit { putBoolean(KEY_EMBY_THEME_MUSIC, value) }

    /**
     * Shared theme-music volume for the singleton ThemeMusicPlayer (0–1).
     * Default ~40% so titles are audible without competing with film dialogue.
     */
    var themeMusicVolume: Float
        get() = prefs.getFloat(KEY_THEME_MUSIC_VOLUME, DEFAULT_THEME_MUSIC_VOLUME).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_THEME_MUSIC_VOLUME, value.coerceIn(0f, 1f)) }

    var autoSelectDisplayVersion: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SELECT_DISPLAY_VERSION, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SELECT_DISPLAY_VERSION, value) }

    /** Global SVP gate: off keeps the filter out even where a preset saved an SVP mode. */
    var svpAvailable: Boolean
        get() = prefs.getBoolean(KEY_SVP_AVAILABLE, false)
        set(value) = prefs.edit { putBoolean(KEY_SVP_AVAILABLE, value) }

    var displayChangeNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DISPLAY_CHANGE_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_DISPLAY_CHANGE_NOTIFICATIONS, value) }

    /** Kodi-style: off, always, start_stop, or start. */
    var displayRefreshAdjustmentKey: String
        get() {
            val stored = prefs.getString(KEY_DISPLAY_REFRESH_ADJUSTMENT, DISPLAY_REFRESH_START_STOP).orEmpty()
                .takeIf { it in DISPLAY_REFRESH_ADJUSTMENTS } ?: DISPLAY_REFRESH_START_STOP
            // Older backups/direct preference writes can contain Auto + Off, which had no effect.
            return if (stored == DISPLAY_REFRESH_OFF && preferredDisplayModeKey in setOf(DISPLAY_MODE_AUTO_CONTENT, DISPLAY_MODE_AUTO_DISPLAY)) DISPLAY_REFRESH_START_STOP else stored
        }
        set(value) = prefs.edit {
            val key = value.takeIf { it in DISPLAY_REFRESH_ADJUSTMENTS } ?: DISPLAY_REFRESH_START_STOP
            putString(KEY_DISPLAY_REFRESH_ADJUSTMENT, key)
            if (key == DISPLAY_REFRESH_OFF && preferredDisplayModeKey in setOf(DISPLAY_MODE_AUTO_CONTENT, DISPLAY_MODE_AUTO_DISPLAY)) putString(KEY_PREFERRED_DISPLAY_MODE, "system")
        }

    /** Stable width×height@Hz keys; empty means all display-supported modes are allowed. */
    var displayModeWhitelist: Set<String>
        get() = prefs.getStringSet(KEY_DISPLAY_MODE_WHITELIST, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_DISPLAY_MODE_WHITELIST, value.toSet()) }

    /** Stable mode key or an automatic content/full-display matching policy. */
    var preferredDisplayModeKey: String
        get() = prefs.getString(KEY_PREFERRED_DISPLAY_MODE, "system").orEmpty().ifBlank { "system" }
        set(value) = prefs.edit {
            val key = value.ifBlank { "system" }
            putString(KEY_PREFERRED_DISPLAY_MODE, key)
            if (key in setOf(DISPLAY_MODE_AUTO_CONTENT, DISPLAY_MODE_AUTO_DISPLAY) &&
                prefs.getString(KEY_DISPLAY_REFRESH_ADJUSTMENT, DISPLAY_REFRESH_START_STOP) == DISPLAY_REFRESH_OFF) {
                putString(KEY_DISPLAY_REFRESH_ADJUSTMENT, DISPLAY_REFRESH_START_STOP)
            }
        }

    var allowDoubleRefreshRate: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_DOUBLE_REFRESH_RATE, true)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_DOUBLE_REFRESH_RATE, value) }

    var allowThreeTwoPulldown: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_THREE_TWO_PULLDOWN, true)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_THREE_TWO_PULLDOWN, value) }

    /** 50→100 / 60→120 Hz doubling; off by default because it doubles 4K GPU/composition work. */
    var allowHighRefreshDoubling: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_HIGH_REFRESH_DOUBLING, false)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_HIGH_REFRESH_DOUBLING, value) }

    /**
     * MPV/VLC GPU render buffer: auto | panel | ui. Panel renders at the physical HDMI mode (4K),
     * UI at the window's logical size; the display scales the rest. See [gpuRenderAtPanelResolution].
     */
    var gpuRenderResolutionKey: String
        get() = prefs.getString(KEY_GPU_RENDER_RESOLUTION, GPU_RENDER_AUTO).takeIf { it in GPU_RENDER_RESOLUTIONS } ?: GPU_RENDER_AUTO
        set(value) = prefs.edit { putString(KEY_GPU_RENDER_RESOLUTION, value.takeIf { it in GPU_RENDER_RESOLUTIONS } ?: GPU_RENDER_AUTO) }

    /** MPV GPU SDR output curve: auto | gamma2.2 | bt.1886 (target-trc and the matching surface tag). */
    var mpvSdrCurveKey: String
        get() = prefs.getString(KEY_MPV_SDR_CURVE, MPV_SDR_CURVE_AUTO).takeIf { it in MPV_SDR_CURVES } ?: MPV_SDR_CURVE_AUTO
        set(value) = prefs.edit { putString(KEY_MPV_SDR_CURVE, value.takeIf { it in MPV_SDR_CURVES } ?: MPV_SDR_CURVE_AUTO) }

    /**
     * The user picked an SVP mode in an SVP menu. Under playback mode Auto SVP runs only then;
     * a legacy stored value (e.g. old "auto" → light) must not start interpolation by itself.
     */
    var svpExplicitChoice: Boolean
        get() = prefs.getBoolean(KEY_SVP_EXPLICIT_CHOICE, false)
        set(value) = prefs.edit { putBoolean(KEY_SVP_EXPLICIT_CHOICE, value) }

    /** MPV zero-copy (hwdec=mediacodec on vo=gpu) failed on this device; use mediacodec-copy. */
    var mpvZeroCopyFailed: Boolean
        get() = prefs.getBoolean(KEY_MPV_ZERO_COPY_FAILED, false)
        set(value) = prefs.edit { putBoolean(KEY_MPV_ZERO_COPY_FAILED, value) }

    /** Kodi-style pause after an app-requested display mode change; 0 is off, 0.5 s steps up to 20 s. */
    /** Kodi's "Sync playback to display": mpv video-sync=display-resample on GPU outputs. */
    var syncPlaybackToDisplay: Boolean
        get() = prefs.getBoolean(KEY_SYNC_PLAYBACK_TO_DISPLAY, false)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_PLAYBACK_TO_DISPLAY, value) }

    var displaySwitchPauseMs: Int
        get() = normalizeDisplaySwitchPauseMs(prefs.getInt(KEY_DISPLAY_SWITCH_PAUSE_MS, 0))
        set(value) = prefs.edit { putInt(KEY_DISPLAY_SWITCH_PAUSE_MS, normalizeDisplaySwitchPauseMs(value)) }

    /** What a media-card activation does. Details remains the conservative default. */
    var mediaSelectionBehaviorKey: String
        get() = MediaSelectionPreferences.normalize(prefs.getString(KEY_MEDIA_SELECTION_BEHAVIOR, MediaSelectionPreferences.DETAILS))
        set(value) = prefs.edit { putString(KEY_MEDIA_SELECTION_BEHAVIOR, MediaSelectionPreferences.normalize(value)) }

    var showLibraryRowsOnHome: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LIBRARY_ROWS_ON_HOME, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LIBRARY_ROWS_ON_HOME, value) }

    var showContinueWatchingRow: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CONTINUE_HUB, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_CONTINUE_HUB, value) }

    var showRecentHomeRows: Boolean
        get() = prefs.getBoolean(KEY_SHOW_RECENT_HUBS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_RECENT_HUBS, value) }

    var showCollectionHomeRows: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COLLECTION_HUBS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_COLLECTION_HUBS, value) }

    /** 0 disables periodic Plex refresh. Non-zero values are seconds. */
    var plexAutoRefreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_PLEX_AUTO_REFRESH_INTERVAL_SECONDS, 60).takeIf { it in PLEX_REFRESH_INTERVALS } ?: 60
        set(value) = prefs.edit { putInt(KEY_PLEX_AUTO_REFRESH_INTERVAL_SECONDS, value.takeIf { it in PLEX_REFRESH_INTERVALS } ?: 60) }

    var libraryGridColumns: Int
        get() = prefs.getInt(KEY_LIBRARY_GRID_COLUMNS, 8).coerceIn(4, 12)
        set(value) = prefs.edit { putInt(KEY_LIBRARY_GRID_COLUMNS, value.coerceIn(4, 12)) }

    var libraryListRows: Int
        get() = prefs.getInt(KEY_LIBRARY_LIST_ROWS, 7).coerceIn(4, 10)
        set(value) = prefs.edit { putInt(KEY_LIBRARY_LIST_ROWS, value.coerceIn(4, 10)) }

    /**
     * Requests larger artwork from media providers for 4K/high-density TV UIs.
     * Kept opt-in because the larger images increase network traffic, server work and disk cache use.
     */
    var highQualityAssetsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIGH_QUALITY_ASSETS, false)
        set(value) = prefs.edit { putBoolean(KEY_HIGH_QUALITY_ASSETS, value) }

    /** Seed once before composition; explicit and restored values always win. */
    fun initializeUiScale(isTelevision: Boolean, displayWidth: Int, displayHeight: Int) {
        if (!prefs.contains(KEY_UI_SCALE)) {
            uiScale = UiScalePolicy.initialScale(isTelevision, displayWidth, displayHeight)
        }
    }

    var uiScale: Float
        get() = UiScalePolicy.normalize(prefs.getFloat(KEY_UI_SCALE, 1f))
        set(value) = prefs.edit { putFloat(KEY_UI_SCALE, UiScalePolicy.normalize(value)) }

    private val overscanState = androidx.compose.runtime.mutableIntStateOf(OverscanPolicy.normalize(prefs.getInt(KEY_OVERSCAN_PERCENT, 0)))

    /** Inset of the whole UI from each screen edge, in percent of the display size. Applies live. */
    var overscanPercent: Int
        get() = overscanState.intValue
        set(value) {
            val normalized = OverscanPolicy.normalize(value)
            prefs.edit { putInt(KEY_OVERSCAN_PERCENT, normalized) }
            overscanState.intValue = normalized
        }

    var accentColorKey: String
        get() = normalizedAccentKey(prefs.getString(KEY_ACCENT_COLOR, "gold") ?: "gold")
        set(value) = prefs.edit { putString(KEY_ACCENT_COLOR, value) }

    var customAccentHex: String
        get() = com.ambient.tv.ui.theme.normalizedCustomAccentHex(prefs.getString(KEY_CUSTOM_ACCENT_HEX, "#E5A00D").orEmpty())
        set(value) = prefs.edit { putString(KEY_CUSTOM_ACCENT_HEX, com.ambient.tv.ui.theme.normalizedCustomAccentHex(value)) }

    var customPlaybackAccentHex: String
        get() = com.ambient.tv.ui.theme.normalizedCustomPlaybackAccentHex(
            prefs.getString(KEY_CUSTOM_PLAYBACK_ACCENT_HEX, null), customAccentHex,
        )
        set(value) = prefs.edit {
            putString(KEY_CUSTOM_PLAYBACK_ACCENT_HEX, com.ambient.tv.ui.theme.normalizedCustomPlaybackAccentHex(value, customAccentHex))
        }

    /** Off by default: playback status reuses the main accent until a second color is opted into. */
    var dualAccentEnabled: Boolean
        get() = prefs.getBoolean(KEY_DUAL_ACCENT_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_DUAL_ACCENT_ENABLED, value) }

    var themeModeKey: String
        get() = prefs.getString(KEY_THEME_MODE, "dark") ?: "dark"
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value) }

    var uiHdrModeKey: String
        get() = com.ambient.tv.ui.display.normalizedUiHdrMode(prefs.getString("ui_hdr_mode", "auto"))
        set(value) = prefs.edit { putString("ui_hdr_mode", com.ambient.tv.ui.display.normalizedUiHdrMode(value)) }

    /** Zero keeps the automatic output budget. */
    var sdrToHdrPeakNits: Int
        get() = com.ambient.tv.ui.display.normalizedSdrToHdrPeakNits(prefs.getInt("sdr_to_hdr_peak_nits", 0))
        set(value) = prefs.edit { putInt("sdr_to_hdr_peak_nits", com.ambient.tv.ui.display.normalizedSdrToHdrPeakNits(value)) }

    /** SDR white inside the HDR signal, in nits. 0 keeps the renderer default (203, mpv hdr-reference-white=auto). */
    var sdrToHdrReferenceWhiteNits: Int
        get() = com.ambient.tv.ui.display.normalizedSdrReferenceWhiteNits(prefs.getInt("sdr_to_hdr_reference_white_nits", 0))
        set(value) = prefs.edit { putInt("sdr_to_hdr_reference_white_nits", com.ambient.tv.ui.display.normalizedSdrReferenceWhiteNits(value)) }

    /** Stored opt-in; the HDR mode and output capabilities gate its runtime effect. */
    var sdrToHdrEnabled: Boolean
        get() = prefs.getBoolean(KEY_SDR_TO_HDR_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SDR_TO_HDR_ENABLED, value) }

    /**
     * Legacy single performance switch: auto | balanced | maximum | low.
     * Interface and playback each have their own key. This remains the default source
     * for a side until that side is saved by its check or by its own setting.
     */
    var performanceModeKey: String
        get() = prefs.getString(KEY_PERFORMANCE_MODE, "auto").takeIf { it in PERFORMANCE_MODES } ?: "auto"
        set(value) = prefs.edit { putString(KEY_PERFORMANCE_MODE, value.takeIf { it in PERFORMANCE_MODES } ?: "auto") }

    /** Interface performance. Null until the interface check or its setting has been saved. */
    var uiPerformanceModeKey: String?
        get() = prefs.getString(KEY_UI_PERFORMANCE_MODE, null)?.takeIf { it in PERFORMANCE_MODES }
        set(value) = prefs.edit {
            val normalized = value?.takeIf { it in PERFORMANCE_MODES }
            if (normalized == null) remove(KEY_UI_PERFORMANCE_MODE) else putString(KEY_UI_PERFORMANCE_MODE, normalized)
        }

    /** Playback performance. Null until the playback check or its setting has been saved. */
    var playbackPerformanceModeKey: String?
        get() = prefs.getString(KEY_PLAYBACK_PERFORMANCE_MODE, null)?.takeIf { it in PERFORMANCE_MODES }
        set(value) = prefs.edit {
            val normalized = value?.takeIf { it in PERFORMANCE_MODES }
            if (normalized == null) remove(KEY_PLAYBACK_PERFORMANCE_MODE) else putString(KEY_PLAYBACK_PERFORMANCE_MODE, normalized)
        }

    /** Zero preserves the adaptive 2/6 second filter watchdog window. */
    var frameDropTestSeconds: Int
        get() = prefs.getInt("frame_drop_test_seconds", 0).let { if (it in 2..60) it else 0 }
        set(value) = prefs.edit { putInt("frame_drop_test_seconds", if (value in 2..60) value else 0) }

    fun effectiveUiPerformanceModeKey(): String = uiPerformanceModeKey ?: performanceModeKey

    fun effectivePlaybackPerformanceModeKey(): String = playbackPerformanceModeKey ?: performanceModeKey

    var homeBackgroundEverywhere: Boolean
        get() = prefs.getBoolean(KEY_HOME_BACKGROUND_EVERYWHERE, false)
        set(value) = prefs.edit { putBoolean(KEY_HOME_BACKGROUND_EVERYWHERE, value) }

    var focusArtworkBackdrop: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_ARTWORK_BACKDROP, true)
        set(value) = prefs.edit { putBoolean(KEY_FOCUS_ARTWORK_BACKDROP, value) }

    /** Emits on every preference write; callers derive and de-duplicate what they care about. */
    fun changes(): kotlinx.coroutines.flow.Flow<Unit> =
        kotlinx.coroutines.flow.callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    fun observeHomeBackground(): kotlinx.coroutines.flow.Flow<HomeBackgroundSettings> =
        kotlinx.coroutines.flow.callbackFlow {
            fun snapshot() = HomeBackgroundSettings(homeBackgroundEverywhere, focusArtworkBackdrop, homeHeroStyleKey,
                homeHeroIntensityPercent, homeHeroSpreadKey)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in setOf(KEY_HOME_BACKGROUND_EVERYWHERE, KEY_FOCUS_ARTWORK_BACKDROP, KEY_HOME_HERO_STYLE,
                        KEY_HOME_HERO_INTENSITY, KEY_HOME_HERO_SPREAD)) trySend(snapshot())
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(snapshot())
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    var homeHeroStyleKey: String
        get() = HomeHeroStyle.normalize(prefs.getString(KEY_HOME_HERO_STYLE, HomeHeroStyle.DEFAULT))
        set(value) = prefs.edit { putString(KEY_HOME_HERO_STYLE, HomeHeroStyle.normalize(value)) }

    var homeHeroIntensityPercent: Int
        get() = HomeHeroStyle.normalizeIntensity(prefs.getInt(KEY_HOME_HERO_INTENSITY, HomeHeroStyle.DEFAULT_INTENSITY))
        set(value) = prefs.edit { putInt(KEY_HOME_HERO_INTENSITY, HomeHeroStyle.normalizeIntensity(value)) }

    var homeHeroSpreadKey: String
        get() = HomeHeroStyle.normalizeSpread(prefs.getString(KEY_HOME_HERO_SPREAD, HomeHeroStyle.DEFAULT_SPREAD))
        set(value) = prefs.edit { putString(KEY_HOME_HERO_SPREAD, HomeHeroStyle.normalizeSpread(value)) }

    /** Seconds the player OSD stays without input (3/5/8/15); mirrored into PlayerOsdTimeout. */
    var playerOsdSeconds: Int
        get() = prefs.getInt("player_osd_seconds", com.ambient.tv.ui.common.PlayerOsdTimeout.DEFAULT_SECONDS)
            .takeIf { it in com.ambient.tv.ui.common.PlayerOsdTimeout.SECONDS } ?: com.ambient.tv.ui.common.PlayerOsdTimeout.DEFAULT_SECONDS
        set(value) {
            val valid = value.takeIf { it in com.ambient.tv.ui.common.PlayerOsdTimeout.SECONDS } ?: com.ambient.tv.ui.common.PlayerOsdTimeout.DEFAULT_SECONDS
            prefs.edit { putInt("player_osd_seconds", valid) }
            com.ambient.tv.ui.common.PlayerOsdTimeout.seconds = valid
        }

    /** off | short | normal | long. The notification history remains available when off. */
    var notificationDisplayDurationKey: String
        get() = prefs.getString(KEY_NOTIFICATION_DISPLAY_DURATION, "normal")
            .takeIf { it in NOTIFICATION_DISPLAY_DURATIONS } ?: "normal"
        set(value) = prefs.edit {
            putString(KEY_NOTIFICATION_DISPLAY_DURATION, value.takeIf { it in NOTIFICATION_DISPLAY_DURATIONS } ?: "normal")
        }

    /** Kodi-like menu/navigation clicks. Opt-in; hard-off under Sparsam via PerformanceProfile. */
    var navSoundsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NAV_SOUNDS_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_NAV_SOUNDS_ENABLED, value) }

    /** 0f..1f linear gain for [navSoundsEnabled]; default ~30%. */
    var navSoundsVolume: Float
        get() = prefs.getFloat(KEY_NAV_SOUNDS_VOLUME, DEFAULT_NAV_SOUNDS_VOLUME).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_NAV_SOUNDS_VOLUME, value.coerceIn(0f, 1f)) }

    var navSoundBank: String
        get() = prefs.getString("nav_sound_bank_v1", "").orEmpty()
        set(value) = prefs.edit { putString("nav_sound_bank_v1", if (value.isBlank()) "" else com.ambient.tv.ui.sound.NavSoundBank.decode(value).encode()) }

    var playbackBackendKey: String
        get() = prefs.getString(KEY_PLAYBACK_BACKEND, "auto").takeIf { it in PLAYBACK_BACKENDS } ?: "auto"
        set(value) = prefs.edit { putString(KEY_PLAYBACK_BACKEND, value.takeIf { it in PLAYBACK_BACKENDS } ?: "auto") }

    /** OSD row with renderer, codec, HDR and similar stream badges. */
    var playerTechBadgesVisible: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_TECH_BADGES_VISIBLE, true)
        set(value) = prefs.edit { putBoolean(KEY_PLAYER_TECH_BADGES_VISIBLE, value) }

    private val liveTvOptionsRevision = androidx.compose.runtime.mutableIntStateOf(0)
    var liveTimeshiftEnabled: Boolean
        get() { liveTvOptionsRevision.intValue; return prefs.getBoolean("live_timeshift_enabled", false) }
        set(value) { prefs.edit { putBoolean("live_timeshift_enabled", value) }; liveTvOptionsRevision.intValue++ }
    var liveDirectRecordEnabled: Boolean
        get() { liveTvOptionsRevision.intValue; return prefs.getBoolean("live_direct_record_enabled", false) }
        set(value) { prefs.edit { putBoolean("live_direct_record_enabled", value) }; liveTvOptionsRevision.intValue++ }

    internal val hasLiveTimeshiftSize: Boolean get() = prefs.contains("live_timeshift_mb")

    @Synchronized
    internal fun initializeLiveTimeshiftSize(megabytes: Int) {
        if (!hasLiveTimeshiftSize) liveTimeshiftMb = megabytes
    }

    /** Live TV timeshift buffer on this device in MiB; 0 = off. Applies to every live source. */
    var liveTimeshiftMb: Int
        get() = prefs.getInt("live_timeshift_mb", 1024).takeIf { it in LIVE_TIMESHIFT_SIZES } ?: 1024
        set(value) = prefs.edit { putInt("live_timeshift_mb", value.takeIf { it in LIVE_TIMESHIFT_SIZES } ?: 1024) }

    /** TVHeadend can use a different decoder than Plex. Existing installations inherit the
     * global selection until a TVHeadend-specific choice is made. */
    var tvheadendPlaybackBackendKey: String
        get() = prefs.getString(KEY_TVH_PLAYBACK_BACKEND, null)
            .takeIf { it in PLAYBACK_BACKENDS }
            ?: playbackBackendKey
        set(value) = prefs.edit { putString(KEY_TVH_PLAYBACK_BACKEND, value.takeIf { it in PLAYBACK_BACKENDS } ?: "auto") }

    var visibleBackgroundVideoPlayback: Boolean
        get() = prefs.getBoolean(KEY_VISIBLE_BACKGROUND_VIDEO_PLAYBACK, false)
        set(value) = prefs.edit { putBoolean(KEY_VISIBLE_BACKGROUND_VIDEO_PLAYBACK, value) }

    var backgroundVideoDimPercent: Int
        get() = prefs.getInt("background_video_dim_percent", 75).coerceIn(60, 90)
        set(value) = prefs.edit { putInt("background_video_dim_percent", value.coerceIn(60, 90)) }

    var mpvVideoOutputModeKey: String
        get() = prefs.getString(KEY_MPV_VIDEO_OUTPUT_MODE, "direct").takeIf { it in MPV_VIDEO_OUTPUT_MODES } ?: "direct"
        set(value) = prefs.edit { putString(KEY_MPV_VIDEO_OUTPUT_MODE, value.takeIf { it in MPV_VIDEO_OUTPUT_MODES } ?: "direct") }

    var mpvVideoDecoderKey: String
        get() = prefs.getString("mpv_video_decoder", "auto").takeIf { it in MPV_VIDEO_DECODERS } ?: "auto"
        set(value) = prefs.edit { putString("mpv_video_decoder", value.takeIf { it in MPV_VIDEO_DECODERS } ?: "auto") }

    /** MPV: HDR10 on an HDR10 display through gpu-next/Vulkan so shaders run (off = direct surface). */
    var mpvHdrGpuEnabled: Boolean
        get() = prefs.getBoolean("mpv_hdr_gpu", false)
        set(value) = prefs.edit { putBoolean("mpv_hdr_gpu", value) }

    var vlcVideoOutputModeKey: String
        get() = prefs.getString("vlc_video_output_mode", "auto").takeIf { it in VLC_VIDEO_OUTPUT_MODES } ?: "auto"
        set(value) = prefs.edit { putString("vlc_video_output_mode", value.takeIf { it in VLC_VIDEO_OUTPUT_MODES } ?: "auto") }

    var audioSettings: AudioSettings
        get() = prefs.getString("audio_settings_v1", null)?.let {
            // Stats-for-Nerds is session-only; ignore any legacy persisted true.
            runCatching {
                val saved = json.decodeFromString<AudioSettings>(it).normalized().copy(performanceHint = false)
                // Older releases stored their untouched default as TV. Upgrade that default to
                // route-following Auto; keep every customized or explicitly different profile.
                if (saved.selectedProfile == "tv" && saved.profiles.values.all { profile -> profile == AudioOptions() })
                    saved.copy(selectedProfile = "auto") else saved
            }.getOrNull()
        } ?: AudioSettings()
        set(value) {
            prefs.edit().putString(
                "audio_settings_v1",
                json.encodeToString(value.normalized().copy(performanceHint = false)),
            ).apply()
        }

    /** Per-title picture/filter choices, newest [TitleVideoSettings.MAX_TITLES] titles. */
    var titleVideoSettings: Map<String, TitleVideoSettings>
        get() = titlePrefs.getString("title_video_settings_v1", null)?.let { raw ->
            runCatching { decodeCached("title_video_settings_v1", raw) { json.decodeFromString<Map<String, TitleVideoSettings>>(it) } }.getOrNull()
        } ?: emptyMap()
        set(value) { titlePrefs.edit { putString("title_video_settings_v1", json.encodeToString(value)) } }

    /** Width presets use a new storage key; preserve v1 so collapsed height variants can be recovered. */
    var resolutionVideoProfiles: Map<String, ResolutionVideoProfile>
        get() {
            val current = prefs.getString("resolution_video_profiles_width_v2", null)
            val raw = current ?: prefs.getString("resolution_video_profiles_v1", null) ?: return emptyMap()
            val profiles = runCatching {
                VideoResolutionClass.migrateProfiles(json.decodeFromString<Map<String, ResolutionVideoProfile>>(raw))
            }.getOrNull() ?: return emptyMap()
            if (current == null) prefs.edit { putString("resolution_video_profiles_width_v2", json.encodeToString(profiles)) }
            return profiles
        }
        set(value) = prefs.edit {
            putString("resolution_video_profiles_width_v2", json.encodeToString(VideoResolutionClass.migrateProfiles(value)))
        }

    var mpvImageOptions: MpvImageOptions
        get() = prefs.getString("mpv_image_options", null)?.let {
            runCatching { json.decodeFromString<MpvImageOptions>(it).normalized() }.getOrNull()
        } ?: MpvImageOptions(scaler = mpvScalingFilterKey, deband = if (mpvDebandEnabled) "medium" else "off")
        set(value) {
            val safe = value.normalized()
            prefs.edit { putString("mpv_image_options", json.encodeToString(safe)) }
            mpvScalingFilterKey = safe.scaler
            mpvDebandEnabled = safe.deband != "off"
        }

    var vlcImageOptions: VlcImageOptions
        get() = prefs.getString("vlc_image_options", null)?.let {
            runCatching { json.decodeFromString<VlcImageOptions>(it).normalized() }.getOrNull()
        } ?: VlcImageOptions(scaler = vlcScalingFilterKey)
        set(value) {
            val safe = value.normalized()
            prefs.edit { putString("vlc_image_options", json.encodeToString(safe)) }
            vlcScalingFilterKey = safe.scaler
        }

    var media3ImageOptions: Media3ImageOptions
        get() = prefs.getString("media3_image_options", null)?.let {
            runCatching { json.decodeFromString<Media3ImageOptions>(it).normalized() }.getOrNull()
        } ?: Media3ImageOptions()
        set(value) = prefs.edit { putString("media3_image_options", json.encodeToString(value.normalized())) }

    var mpvScalingFilterKey: String
        get() = prefs.getString(KEY_MPV_SCALING_FILTER, "lanczos").takeIf { it in MPV_SCALING_FILTERS } ?: "lanczos"
        set(value) = prefs.edit { putString(KEY_MPV_SCALING_FILTER, value.takeIf { it in MPV_SCALING_FILTERS } ?: "lanczos") }

    var mpvDebandEnabled: Boolean
        get() = prefs.getBoolean(KEY_MPV_DEBAND, true)
        set(value) = prefs.edit { putBoolean(KEY_MPV_DEBAND, value) }

    var vlcScalingFilterKey: String
        get() = prefs.getString(KEY_VLC_SCALING_FILTER, "bilinear").takeIf { it in VLC_SCALING_FILTERS } ?: "bilinear"
        set(value) = prefs.edit { putString(KEY_VLC_SCALING_FILTER, value.takeIf { it in VLC_SCALING_FILTERS } ?: "bilinear") }

    /**
     * Global audio passthrough for library + live playback (Plex/JF/Emby/Files/TVH/YouTube).
     * Migrated from legacy [KEY_PLEX_AUDIO_PASSTHROUGH] / [KEY_TVH_AUDIO_PASSTHROUGH]:
     * if either old flag was true → true.
     */
    var mediaAudioPassthroughEnabled: Boolean
        get() {
            migrateMediaAudioPassthroughOnce()
            return prefs.getBoolean(KEY_MEDIA_AUDIO_PASSTHROUGH, false)
        }
        set(value) {
            migrateMediaAudioPassthroughOnce()
            prefs.edit { putBoolean(KEY_MEDIA_AUDIO_PASSTHROUGH, value) }
        }

    /** One-shot: OR of legacy plex/tvh flags into [KEY_MEDIA_AUDIO_PASSTHROUGH], then drop old keys. */
    private fun migrateMediaAudioPassthroughOnce() {
        if (prefs.contains(KEY_MEDIA_AUDIO_PASSTHROUGH)) return
        val enabled = prefs.getBoolean(KEY_PLEX_AUDIO_PASSTHROUGH, false) ||
            prefs.getBoolean(KEY_TVH_AUDIO_PASSTHROUGH, false)
        prefs.edit {
            putBoolean(KEY_MEDIA_AUDIO_PASSTHROUGH, enabled)
            remove(KEY_PLEX_AUDIO_PASSTHROUGH)
            remove(KEY_TVH_AUDIO_PASSTHROUGH)
        }
    }

    var audioVisualizerModeKey: String
        get() = prefs.getString(KEY_AUDIO_VISUALIZER_MODE, "projectm") ?: "projectm"
        set(value) = prefs.edit { putString(KEY_AUDIO_VISUALIZER_MODE, value) }

    var projectmShowPresetName: Boolean
        get() = prefs.getBoolean(KEY_PROJECTM_SHOW_PRESET_NAME, true)
        set(value) = prefs.edit { putBoolean(KEY_PROJECTM_SHOW_PRESET_NAME, value) }

    var projectmPresetIntervalSeconds: Int
        get() = prefs.getInt(KEY_PROJECTM_PRESET_INTERVAL, 60).coerceIn(10, 600)
        set(value) = prefs.edit { putInt(KEY_PROJECTM_PRESET_INTERVAL, value.coerceIn(10, 600)) }

    var projectmTransitionSeconds: Int
        get() = prefs.getInt(KEY_PROJECTM_TRANSITION_SECONDS, 2).coerceIn(0, 10)
        set(value) = prefs.edit { putInt(KEY_PROJECTM_TRANSITION_SECONDS, value.coerceIn(0, 10)) }

    var projectmQualityKey: String
        get() = prefs.getString(KEY_PROJECTM_QUALITY, "balanced") ?: "balanced"
        set(value) = prefs.edit { putString(KEY_PROJECTM_QUALITY, value) }

    var projectmResolutionKey: String
        get() = com.ambient.tv.ui.common.projectm.ProjectMRenderResolution.normalize(prefs.getString(KEY_PROJECTM_RESOLUTION, null))
        set(value) = prefs.edit { putString(KEY_PROJECTM_RESOLUTION, com.ambient.tv.ui.common.projectm.ProjectMRenderResolution.normalize(value)) }

    var projectmHdr4kAllowed: Boolean
        get() = prefs.getBoolean(KEY_PROJECTM_HDR_4K, false)
        set(value) = prefs.edit { putBoolean(KEY_PROJECTM_HDR_4K, value) }

    var projectmPresetCategoryKey: String
        get() = prefs.getString(KEY_PROJECTM_CATEGORY, "all") ?: "all"
        set(value) = prefs.edit { putString(KEY_PROJECTM_CATEGORY, value) }


    var youtubeSponsorBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_YOUTUBE_SPONSORBLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_YOUTUBE_SPONSORBLOCK, value) }

    var youtubeAutoplayNextEnabled: Boolean
        get() = prefs.getBoolean(KEY_YOUTUBE_AUTOPLAY_NEXT, false)
        set(value) = prefs.edit { putBoolean(KEY_YOUTUBE_AUTOPLAY_NEXT, value) }

    /** Independent of [mediaSelectionBehaviorKey]. Play immediately is the YouTube default. */
    var youtubeMediaSelectionBehaviorKey: String
        get() = MediaSelectionPreferences.normalize(
            prefs.getString(KEY_YOUTUBE_MEDIA_SELECTION_BEHAVIOR, MediaSelectionPreferences.PLAY),
            MediaSelectionPreferences.PLAY,
        )
        set(value) = prefs.edit {
            putString(
                KEY_YOUTUBE_MEDIA_SELECTION_BEHAVIOR,
                MediaSelectionPreferences.normalize(value, MediaSelectionPreferences.PLAY),
            )
        }

    var creditsSkipMode: String
        get() = prefs.getString("credits_skip_mode", "button")?.takeIf { it in listOf("off", "button", "auto") } ?: "button"
        set(value) = prefs.edit { putString("credits_skip_mode", value.takeIf { it in listOf("off", "button", "auto") } ?: "button") }

    var introSkipMode: String
        get() = prefs.getString("intro_skip_mode", "button")?.takeIf { it in listOf("off", "button", "auto") } ?: "button"
        set(value) = prefs.edit { putString("intro_skip_mode", value.takeIf { it in listOf("off", "button", "auto") } ?: "button") }

    var nextUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_NEXT_UP_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_NEXT_UP_ENABLED, value) }

    var nextUpLeadSeconds: Int
        get() = prefs.getInt(KEY_NEXT_UP_LEAD_SECONDS, 30).takeIf { it in NEXT_UP_LEAD_SECONDS } ?: 30
        set(value) = prefs.edit { putInt(KEY_NEXT_UP_LEAD_SECONDS, value.takeIf { it in NEXT_UP_LEAD_SECONDS } ?: 30) }

    /** Zero keeps normal end-of-video autoplay. */
    var nextUpCreditsDelaySeconds: Int
        get() = prefs.getInt("next_up_credits_delay_seconds", 0).takeIf { it in listOf(0, 5, 10, 15, 30) } ?: 0
        set(value) = prefs.edit { putInt("next_up_credits_delay_seconds", value.takeIf { it in listOf(0, 5, 10, 15, 30) } ?: 0) }

    var nextUpAutoplayEpisodes: Boolean
        get() = prefs.getBoolean(KEY_NEXT_UP_AUTOPLAY_EPISODES, false)
        set(value) = prefs.edit { putBoolean(KEY_NEXT_UP_AUTOPLAY_EPISODES, value) }

    var nextUpAutoplayCollections: Boolean
        get() = prefs.getBoolean(KEY_NEXT_UP_AUTOPLAY_COLLECTIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_NEXT_UP_AUTOPLAY_COLLECTIONS, value) }

    var nextUpOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_NEXT_UP_OVERLAY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_NEXT_UP_OVERLAY_ENABLED, value) }

    var nextUpStillWatchingEpisodes: Int
        get() = prefs.getInt(KEY_NEXT_UP_STILL_WATCHING, 3).takeIf { it in NEXT_UP_STILL_WATCHING_VALUES } ?: 3
        set(value) = prefs.edit { putInt(KEY_NEXT_UP_STILL_WATCHING, value.takeIf { it in NEXT_UP_STILL_WATCHING_VALUES } ?: 3) }

    /** Sleep timer armed at the start of every playback session (not on zapping/next episode). */
    var sleepAutoStartKey: String
        get() = prefs.getString(KEY_SLEEP_AUTO_START, null)?.takeIf { it in SLEEP_AUTO_START_KEYS } ?: SLEEP_AUTO_START_OFF
        set(value) = prefs.edit { putString(KEY_SLEEP_AUTO_START, value.takeIf { it in SLEEP_AUTO_START_KEYS } ?: SLEEP_AUTO_START_OFF) }

    /** Non-pausing notice this many minutes before a timed sleep stop; 0 = off. */
    /** Daily stop time as minute of day (0..1439), [SLEEP_DAILY_STOP_OFF] when off. */
    var sleepDailyStopMinute: Int
        get() = prefs.getInt(KEY_SLEEP_DAILY_STOP, SLEEP_DAILY_STOP_OFF).takeIf { it in 0 until 1440 } ?: SLEEP_DAILY_STOP_OFF
        set(value) = prefs.edit { putInt(KEY_SLEEP_DAILY_STOP, value.takeIf { it in 0 until 1440 } ?: SLEEP_DAILY_STOP_OFF) }

    var sleepWarningMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_WARNING_MINUTES, SLEEP_WARNING_DEFAULT).takeIf { it in SLEEP_WARNING_VALUES } ?: SLEEP_WARNING_DEFAULT
        set(value) = prefs.edit { putInt(KEY_SLEEP_WARNING_MINUTES, value.takeIf { it in SLEEP_WARNING_VALUES } ?: SLEEP_WARNING_DEFAULT) }

    /** SmartTube-style YouTube sections visible on the YouTube screen, in display order. */
    var youtubeFeedKeys: List<String>
        get() {
            val raw = prefs.getString(KEY_YOUTUBE_FEEDS, null)
            val allowed = YOUTUBE_FEED_KEYS
            val parsed = raw?.split(',')?.map(String::trim)?.filter { it in allowed }?.distinct().orEmpty()
            return parsed.ifEmpty { DEFAULT_YOUTUBE_FEEDS }
        }
        set(value) {
            val normalized = value.filter { it in YOUTUBE_FEED_KEYS }.distinct().ifEmpty { listOf("home") }
            prefs.edit { putString(KEY_YOUTUBE_FEEDS, normalized.joinToString(",")) }
        }

    /** 0 = automatic/unlimited, otherwise maximum video height in pixels. */
    var youtubeMaxVideoHeight: Int
        get() = prefs.getInt(KEY_YOUTUBE_MAX_VIDEO_HEIGHT, 0).takeIf { it in setOf(0, 720, 1080, 1440, 2160) } ?: 0
        set(value) = prefs.edit { putInt(KEY_YOUTUBE_MAX_VIDEO_HEIGHT, value.takeIf { it in setOf(0, 720, 1080, 1440, 2160) } ?: 0) }

    /** SmartTube video format preset key ([com.ambient.tv.youtube.YOUTUBE_VIDEO_PRESETS]); "auto" = per-device default. */
    var youtubeVideoPreset: String
        get() = prefs.getString(KEY_YOUTUBE_VIDEO_PRESET, null)?.takeIf { com.ambient.tv.youtube.youtubeVideoPreset(it) != null }
            ?: com.ambient.tv.youtube.YOUTUBE_PRESET_AUTO
        set(value) = prefs.edit { putString(KEY_YOUTUBE_VIDEO_PRESET,
            value.takeIf { com.ambient.tv.youtube.youtubeVideoPreset(it) != null } ?: com.ambient.tv.youtube.YOUTUBE_PRESET_AUTO) }

    /** D-pad Up/Down big-jump seek amount in the video player, in seconds. */
    var bigSeekJumpSeconds: Int
        get() = prefs.getInt(KEY_BIG_SEEK_JUMP_SECONDS, 60).coerceIn(10, 300)
        set(value) = prefs.edit { putInt(KEY_BIG_SEEK_JUMP_SECONDS, value.coerceIn(10, 300)) }

    var screensaverEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREENSAVER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SCREENSAVER_ENABLED, value) }

    var screensaverTimeoutMinutes: Int
        get() = prefs.getInt(KEY_SCREENSAVER_TIMEOUT_MINUTES, 10).coerceIn(1, 120)
        set(value) = prefs.edit { putInt(KEY_SCREENSAVER_TIMEOUT_MINUTES, value.coerceIn(1, 120)) }

    var screensaverModeKey: String
        get() = prefs.getString(KEY_SCREENSAVER_MODE, "ambient") ?: "ambient"
        set(value) = prefs.edit { putString(KEY_SCREENSAVER_MODE, value) }

    var screensaverShowClock: Boolean
        get() = prefs.getBoolean(KEY_SCREENSAVER_SHOW_CLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_SCREENSAVER_SHOW_CLOCK, value) }

    var screensaverShowDate: Boolean
        get() = prefs.getBoolean(KEY_SCREENSAVER_SHOW_DATE, true)
        set(value) = prefs.edit { putBoolean(KEY_SCREENSAVER_SHOW_DATE, value) }

    var screensaverUse24HourClock: Boolean
        get() = prefs.getBoolean(KEY_SCREENSAVER_24H_CLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_SCREENSAVER_24H_CLOCK, value) }

    var screensaverUseArtwork: Boolean
        get() = prefs.getBoolean(KEY_SCREENSAVER_USE_ARTWORK, true)
        set(value) = prefs.edit { putBoolean(KEY_SCREENSAVER_USE_ARTWORK, value) }

    var screensaverArtworkIntervalSeconds: Int
        get() = prefs.getInt(KEY_SCREENSAVER_ARTWORK_INTERVAL_SECONDS, 30).coerceIn(10, 300)
        set(value) = prefs.edit { putInt(KEY_SCREENSAVER_ARTWORK_INTERVAL_SECONDS, value.coerceIn(10, 300)) }

    /** Visual brightness in percent. OLED intentionally caps effective content brightness separately. */
    var screensaverDimPercent: Int
        get() = prefs.getInt(KEY_SCREENSAVER_DIM_PERCENT, 70).coerceIn(20, 100)
        set(value) = prefs.edit { putInt(KEY_SCREENSAVER_DIM_PERCENT, value.coerceIn(20, 100)) }

    /** Render quality of the GPU screensaver scenes: economy, balanced or high. */
    var screensaverQualityKey: String
        get() = prefs.getString(KEY_SCREENSAVER_QUALITY, "balanced") ?: "balanced"
        set(value) = prefs.edit { putString(KEY_SCREENSAVER_QUALITY, value) }

    var tvheadendEnabled: Boolean
        get() = prefs.getBoolean(KEY_TVH_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_TVH_ENABLED, value) }

    var tvheadendHost: String
        get() = prefs.getString(KEY_TVH_HOST, "") ?: ""
        set(value) {
            if (value.trim() != tvheadendHost) tvheadendLoginVerified = false
            prefs.edit { putString(KEY_TVH_HOST, value.trim()) }
        }

    var tvheadendPort: Int
        get() = prefs.getInt(KEY_TVH_PORT, DEFAULT_TVH_PORT)
        set(value) {
            if (value.coerceIn(1, 65535) != tvheadendPort) tvheadendLoginVerified = false
            prefs.edit { putInt(KEY_TVH_PORT, value.coerceIn(1, 65535)) }
        }

    /** Prefer HTTPS for TVHeadend (reverse proxy / TLS). Default stays HTTP; host URLs with https:// still win. */
    var tvheadendUseHttps: Boolean
        get() = prefs.getBoolean(KEY_TVH_HTTPS, false)
        set(value) {
            if (value != tvheadendUseHttps) tvheadendLoginVerified = false
            prefs.edit { putBoolean(KEY_TVH_HTTPS, value) }
        }

    var tvheadendUsername: String
        get() = prefs.getString(KEY_TVH_USERNAME, "") ?: ""
        set(value) {
            if (value.trim() != tvheadendUsername) tvheadendLoginVerified = false
            prefs.edit { putString(KEY_TVH_USERNAME, value.trim()) }
        }

    val tvheadendHistoryScope: String
        get() = RecentTvChannels.scope(tvheadendHost, tvheadendPort, tvheadendUsername)

    private fun recentTvHistories(): List<RecentTvChannels> = runCatching {
        json.decodeFromString<List<RecentTvChannels>>(prefs.getString("recent_tv_channels", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    val recentTvChannelIds: List<String>
        get() = recentTvHistories().firstOrNull { it.scope == tvheadendHistoryScope }
            ?.channelIds.orEmpty().filter(String::isNotBlank).distinct().take(RecentTvChannels.LIMIT)

    @Synchronized
    fun recordWatchedTvChannel(scope: String, channelId: String) {
        if (channelId.isBlank() || scope.isBlank()) return
        val configured = when (scope.substringBefore(':')) {
            "plex" -> plexEnabled && serverProfiles.any { scope == "plex:${it.id}" }
            "jellyfin" -> jellyfinEnabled && jellyfinServerProfiles.any { it.authenticated && scope == "jellyfin:${it.id}:${it.userId}" }
            "emby" -> embyEnabled && embyServerProfiles.any { it.authenticated && scope == "emby:${it.id}:${it.userId}" }
            else -> hasTvheadendEndpoint && tvheadendLoginVerified && scope == tvheadendHistoryScope
        }
        if (!configured) return
        val histories = recentTvHistories()
        val updated = (histories.firstOrNull { it.scope == scope } ?: RecentTvChannels(scope)).watched(channelId)
        prefs.edit { putString("recent_tv_channels", json.encodeToString(
            (listOf(updated) + histories.filterNot { it.scope == scope }).take(32))) }
    }

    fun recentTvChannelIds(sourceId: String): List<String> = recentTvHistories().firstOrNull { it.scope == sourceId }
        ?.channelIds.orEmpty().filter(String::isNotBlank).distinct().take(RecentTvChannels.LIMIT)

    fun clearRecentTvChannels() = prefs.edit { remove("recent_tv_channels") }

    var tvheadendStreamProfile: String
        get() = prefs.getString(KEY_TVH_STREAM_PROFILE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TVH_STREAM_PROFILE, value.trim()) }

    var tvheadendMacAddress: String?
        get() = prefs.getString(KEY_TVH_MAC, null)
        set(value) = prefs.edit { putString(KEY_TVH_MAC, value?.trim()?.takeIf { it.isNotBlank() }) }

    var tvheadendAutoWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_TVH_AUTO_WAKE, true)
        set(value) = prefs.edit { putBoolean(KEY_TVH_AUTO_WAKE, value) }

    var tvheadendShowOnHome: Boolean
        get() = prefs.getBoolean(KEY_TVH_SHOW_ON_HOME, true)
        set(value) = prefs.edit { putBoolean(KEY_TVH_SHOW_ON_HOME, value) }

    var tvheadendDeinterlaceModeKey: String
        get() = prefs.getString(KEY_TVH_DEINTERLACE_MODE, "off") ?: "off"
        set(value) = prefs.edit { putString(KEY_TVH_DEINTERLACE_MODE, value) }

    var tvheadendDeinterlaceFilterKey: String
        get() = prefs.getString(KEY_TVH_DEINTERLACE_FILTER, "auto") ?: "auto"
        set(value) = prefs.edit { putString(KEY_TVH_DEINTERLACE_FILTER, value) }

    var tvheadendMpvDeinterlaceFilterKey: String
        get() = prefs.getString("tvh_mpv_deinterlace_filter", null)
            ?.let { DeinterlaceCatalog.normalize("mpv", it) }
            ?: DeinterlaceCatalog.migrateMpv(tvheadendDeinterlaceFilterKey)
        set(value) = prefs.edit { putString("tvh_mpv_deinterlace_filter", DeinterlaceCatalog.normalize("mpv", value)) }

    /** Address entered; enough to try a connection (settings test, verification). */
    val hasTvheadendEndpoint: Boolean
        get() = tvheadendEnabled && tvheadendHost.isNotBlank()

    /**
     * Usable in Home, search and Live TV unless TVHeadend proved the login wrong. A login that is
     * merely unconfirmed (new address, server asleep, one stray 401) stays visible.
     */
    val isTvheadendConfigured: Boolean
        get() = hasTvheadendEndpoint && tvheadendLoginState != TvheadendLoginState.REJECTED

    /** Address set but the login was rejected: the menu keeps TVHeadend with "check sign-in". */
    val tvheadendLoginRejected: Boolean
        get() = hasTvheadendEndpoint && tvheadendLoginState == TvheadendLoginState.REJECTED

    /**
     * TVHeadend login: unknown (address/login changed, not yet confirmed), verified (last call
     * succeeded) or rejected (explicit test or repeated 401s, see TvheadendLoginRejection).
     * Migration: the legacy boolean "true" is verified, "false" becomes unknown; installs that never
     * stored either count as verified, as before.
     */
    var tvheadendLoginState: TvheadendLoginState
        get() = storedTvheadendLoginState(
            prefs.getString(KEY_TVH_LOGIN_STATE, null),
            if (prefs.contains(KEY_TVH_LOGIN_VERIFIED)) prefs.getBoolean(KEY_TVH_LOGIN_VERIFIED, true) else null,
        )
        set(value) {
            val current = tvheadendLoginState
            if (prefs.getString(KEY_TVH_LOGIN_STATE, null) == value.name && !prefs.contains(KEY_TVH_LOGIN_VERIFIED)) return
            prefs.edit {
                putString(KEY_TVH_LOGIN_STATE, value.name)
                remove(KEY_TVH_LOGIN_VERIFIED)
            }
            if (current != value) LoginStateSignal.changed()
        }

    /** Compatibility view: true is verified; false only means "not confirmed" (never rejected). */
    var tvheadendLoginVerified: Boolean
        get() = tvheadendLoginState == TvheadendLoginState.VERIFIED
        set(value) {
            if (value) tvheadendLoginState = TvheadendLoginState.VERIFIED
            else if (tvheadendLoginState != TvheadendLoginState.UNKNOWN) tvheadendLoginState = TvheadendLoginState.UNKNOWN
        }

    val clientIdentifier: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_CLIENT_ID, it) }
        }


    fun sectionPage(sectionKey: String): String? = prefs.getString("section_page_$sectionKey", null)

    fun setSectionPage(sectionKey: String, value: String) {
        prefs.edit { putString("section_page_$sectionKey", value) }
    }

    fun guideOptions(sourceId: String): com.ambient.tv.livetv.GuideOptions = runCatching {
        json.decodeFromString<com.ambient.tv.livetv.GuideOptions>(prefs.getString("guide_options_$sourceId", null) ?: "{}")
    }.getOrDefault(com.ambient.tv.livetv.GuideOptions())

    fun setGuideOptions(sourceId: String, options: com.ambient.tv.livetv.GuideOptions) {
        prefs.edit { putString("guide_options_$sourceId", json.encodeToString(options)) }
    }

    fun sectionFocus(stateKey: String): String? = focusValue("section_focus_$stateKey")

    fun setSectionFocus(stateKey: String, value: String) {
        queueFocusWrite(mapOf("section_focus_$stateKey" to value))
    }

    /** Item and recommendation row are one restore target; queue only one preference write. */
    fun setSectionFocusSnapshot(stateKey: String, value: String, rowId: String?) {
        queueFocusWrite(mapOf("section_focus_$stateKey" to value, "section_focus_row_$stateKey" to rowId))
    }

    fun sectionFocusRow(stateKey: String): String? = focusValue("section_focus_row_$stateKey")

    fun setSectionFocusRow(stateKey: String, value: String?) {
        queueFocusWrite(mapOf("section_focus_row_$stateKey" to value))
    }

    /*
     * Focus moves with every D-pad step. Writing the preference file each time rewrote it many
     * times a second while scrolling, so focus values are held in memory and written together
     * shortly after the last step (and before a library-preference export or restore).
     */
    private val focusLock = Any()
    private val pendingFocus = HashMap<String, String?>()
    private var focusFlushScheduled = false
    private val focusWriter by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "focus-prefs").apply { isDaemon = true }
        }
    }

    private fun focusValue(key: String): String? = synchronized(focusLock) {
        if (pendingFocus.containsKey(key)) return pendingFocus[key]
        prefs.getString(key, null)
    }

    private fun queueFocusWrite(values: Map<String, String?>) {
        synchronized(focusLock) {
            if (values.all { (key, value) -> (if (pendingFocus.containsKey(key)) pendingFocus[key] else prefs.getString(key, null)) == value }) return
            pendingFocus.putAll(values)
            if (focusFlushScheduled) return
            focusFlushScheduled = true
        }
        focusWriter.schedule(::flushFocusWrites, FOCUS_WRITE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun flushFocusWrites() {
        val values = synchronized(focusLock) {
            focusFlushScheduled = false
            HashMap(pendingFocus).also { pendingFocus.clear() }
        }
        if (values.isEmpty()) return
        prefs.edit {
            values.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
        }
    }

    fun sectionSort(sectionKey: String): String? = prefs.getString("section_sort_$sectionKey", null)

    fun setSectionSort(sectionKey: String, value: String) {
        prefs.edit { putString("section_sort_$sectionKey", value) }
    }

    fun sectionFilter(sectionKey: String): String? = prefs.getString("section_filter_$sectionKey", null)

    fun setSectionFilter(sectionKey: String, value: String) {
        prefs.edit { putString("section_filter_$sectionKey", value) }
    }

    fun sectionSortOrder(sectionKey: String): String? = prefs.getString("section_sort_order_$sectionKey", null)

    fun setSectionSortOrder(sectionKey: String, value: String) {
        prefs.edit { putString("section_sort_order_$sectionKey", value) }
    }

    fun sectionQualityFilter(sectionKey: String): String? = prefs.getString("section_filter_quality_$sectionKey", null)

    fun setSectionQualityFilter(sectionKey: String, value: String) {
        prefs.edit { putString("section_filter_quality_$sectionKey", value) }
    }

    fun sectionGenreFilter(sectionKey: String): String? = prefs.getString("section_filter_genre_$sectionKey", null)

    fun setSectionGenreFilter(sectionKey: String, value: String?) {
        prefs.edit {
            if (value.isNullOrBlank()) remove("section_filter_genre_$sectionKey")
            else putString("section_filter_genre_$sectionKey", value)
        }
    }

    fun sectionDecadeFilter(sectionKey: String): String? = prefs.getString("section_filter_decade_$sectionKey", null)

    fun setSectionDecadeFilter(sectionKey: String, value: String?) {
        prefs.edit {
            if (value.isNullOrBlank()) remove("section_filter_decade_$sectionKey")
            else putString("section_filter_decade_$sectionKey", value)
        }
    }

    fun sectionContentRatingFilter(sectionKey: String): String? = prefs.getString("section_filter_content_rating_$sectionKey", null)

    fun setSectionContentRatingFilter(sectionKey: String, value: String?) {
        prefs.edit {
            if (value.isNullOrBlank()) remove("section_filter_content_rating_$sectionKey")
            else putString("section_filter_content_rating_$sectionKey", value)
        }
    }

    fun sectionViewMode(sectionKey: String): String? = prefs.getString("section_view_$sectionKey", null)

    fun setSectionViewMode(sectionKey: String, value: String) {
        prefs.edit { putString("section_view_$sectionKey", value) }
    }

    fun recentSearches(): List<String> = prefs.getStringSet(KEY_RECENT_SEARCHES, emptySet())
        .orEmpty()
        .toList()
        .sortedByDescending { prefs.getLong("search_ts_${it.lowercase()}", 0L) }
        .take(8)

    fun rememberSearch(query: String) {
        val normalized = query.trim()
        if (normalized.length < 2) return
        val current = prefs.getStringSet(KEY_RECENT_SEARCHES, emptySet()).orEmpty().toMutableSet()
        current.removeAll { it.equals(normalized, ignoreCase = true) }
        current.add(normalized)
        prefs.edit {
            putStringSet(KEY_RECENT_SEARCHES, current.toList().takeLast(12).toSet())
            putLong("search_ts_${normalized.lowercase()}", System.currentTimeMillis())
        }
    }

    fun clearRecentSearches() {
        prefs.edit {
            remove(KEY_RECENT_SEARCHES)
            prefs.all.keys.filter { it.startsWith("search_ts_") }.forEach(::remove)
        }
    }

    val serverProfiles: List<ServerProfile>
        get() {
            val raw = prefs.getString(KEY_SERVER_PROFILES, null) ?: return migrateLegacyProfile()
            return runCatching { decodeCached(KEY_SERVER_PROFILES, raw) { json.decodeFromString<List<ServerProfile>>(it) } }
                .getOrDefault(emptyList())
                .map(::normalizeServerProfile)
        }

    /** Server profiles ("jellyfin:<id>", "emby:<id>") whose session the server last rejected (401). */
    val loginRejected: Set<String>
        get() = prefs.getStringSet(KEY_LOGIN_REJECTED, null)?.toSet().orEmpty()

    fun setLoginRejected(profileKey: String, rejected: Boolean) {
        val current = loginRejected
        if ((profileKey in current) == rejected) return
        prefs.edit { putStringSet(KEY_LOGIN_REJECTED, if (rejected) current + profileKey else current - profileKey) }
        LoginStateSignal.changed()
    }

    /** Address entered; enough to try a connection (settings test, verification). */
    val hasPlexEndpoint: Boolean
        get() = plexEnabled && host.isNotBlank()

    /** Shown in menus, Home and search only once the server accepted the address and token. */
    val isConfigured: Boolean
        get() = hasPlexEndpoint && plexLoginVerified

    /** Same model as [tvheadendLoginVerified], for the Plex server and its token. */
    var plexLoginVerified: Boolean
        get() = prefs.getBoolean(KEY_PLEX_LOGIN_VERIFIED, true)
        set(value) {
            val current = prefs.getBoolean(KEY_PLEX_LOGIN_VERIFIED, true)
            if (prefs.contains(KEY_PLEX_LOGIN_VERIFIED) && current == value) return
            prefs.edit { putBoolean(KEY_PLEX_LOGIN_VERIFIED, value) }
            // Recording the legacy default is not a change anyone needs to react to.
            if (current != value) LoginStateSignal.changed()
        }

    val jellyfinServerProfiles: List<JellyfinServerProfile>
        get() {
            val raw = prefs.getString(KEY_JELLYFIN_SERVER_PROFILES, null) ?: return emptyList()
            return runCatching { decodeCached(KEY_JELLYFIN_SERVER_PROFILES, raw) { json.decodeFromString<List<JellyfinServerProfile>>(it) } }
                .getOrDefault(emptyList())
                .map(::normalizeJellyfinServerProfile)
        }

    fun upsertJellyfinServerProfile(profile: JellyfinServerProfile, makeActive: Boolean = true) {
        val normalized = normalizeJellyfinServerProfile(profile)
        val profiles = jellyfinServerProfiles.toMutableList()
        val index = profiles.indexOfFirst { it.id == normalized.id }
        if (index >= 0) profiles[index] = normalized else profiles += normalized
        prefs.edit { putString(KEY_JELLYFIN_SERVER_PROFILES, json.encodeToString(profiles)) }
        if (makeActive) activateJellyfinServer(normalized.id)
    }

    fun activateJellyfinServer(profileId: String): Boolean {
        val profile = jellyfinServerProfiles.firstOrNull { it.id == profileId } ?: return false
        activeJellyfinServerId = profile.id
        jellyfinHost = profile.host
        jellyfinPort = profile.port
        jellyfinUseHttps = profile.useHttps
        jellyfinBasePath = profile.basePath
        jellyfinUserId = profile.userId
        jellyfinUsername = profile.username
        jellyfinMacAddress = profile.macAddress
        jellyfinAutoWakeEnabled = profile.autoWakeEnabled
        return true
    }

    fun activeJellyfinServerProfile(): JellyfinServerProfile? {
        fun JellyfinServerProfile.matchesCurrentEndpoint() =
            host.equals(jellyfinHost, ignoreCase = true) &&
                port == jellyfinPort &&
                useHttps == jellyfinUseHttps &&
                basePath == jellyfinBasePath
        return jellyfinServerProfiles
            .firstOrNull { it.id == activeJellyfinServerId && it.matchesCurrentEndpoint() }
            ?: jellyfinServerProfiles.firstOrNull { it.matchesCurrentEndpoint() }
    }

    fun removeJellyfinServerProfile(profileId: String): JellyfinServerProfile? {
        val profiles = jellyfinServerProfiles
        val removed = profiles.firstOrNull { it.id == profileId } ?: return null
        val remaining = profiles.filterNot { it.id == profileId }
        prefs.edit { putString(KEY_JELLYFIN_SERVER_PROFILES, json.encodeToString(remaining)) }
        if (activeJellyfinServerId == profileId) {
            val replacement = remaining.firstOrNull()
            if (replacement != null) activateJellyfinServer(replacement.id) else {
                activeJellyfinServerId = null
                jellyfinHost = ""
                jellyfinUserId = null
                jellyfinUsername = null
                jellyfinMacAddress = null
            }
        }
        return removed
    }

    fun replaceJellyfinServerProfiles(profiles: List<JellyfinServerProfile>, activeId: String?) {
        val normalized = profiles.map(::normalizeJellyfinServerProfile)
        prefs.edit { putString(KEY_JELLYFIN_SERVER_PROFILES, json.encodeToString(normalized)) }
        val active = normalized.firstOrNull { it.id == activeId } ?: normalized.firstOrNull()
        if (active != null) activateJellyfinServer(active.id) else {
            activeJellyfinServerId = null
            jellyfinHost = ""
            jellyfinUserId = null
            jellyfinUsername = null
            jellyfinMacAddress = null
        }
    }

    fun updateActiveJellyfinServerMac(mac: String) {
        jellyfinMacAddress = mac
        val active = activeJellyfinServerProfile() ?: return
        upsertJellyfinServerProfile(active.copy(macAddress = mac), makeActive = true)
    }

    private fun normalizeJellyfinServerProfile(profile: JellyfinServerProfile) = profile.copy(
        host = NetworkEndpoint.normalizeHost(profile.host),
        port = NetworkEndpoint.validPortOrDefault(profile.port, DEFAULT_JELLYFIN_PORT),
        basePath = normalizeBasePath(profile.basePath),
        // Profiles written before authentication state was explicit could only have a user id
        // after a successful login. Keep those existing installations authenticated.
        authenticated = profile.authenticated || !profile.userId.isNullOrBlank(),
    )

    val embyServerProfiles: List<EmbyServerProfile>
        get() {
            val raw = prefs.getString(KEY_EMBY_SERVER_PROFILES, null) ?: return emptyList()
            return runCatching { decodeCached(KEY_EMBY_SERVER_PROFILES, raw) { json.decodeFromString<List<EmbyServerProfile>>(it) } }
                .getOrDefault(emptyList())
                .map(::normalizeEmbyServerProfile)
        }

    fun upsertEmbyServerProfile(profile: EmbyServerProfile, makeActive: Boolean = true) {
        val normalized = normalizeEmbyServerProfile(profile)
        val profiles = embyServerProfiles.toMutableList()
        val index = profiles.indexOfFirst { it.id == normalized.id }
        if (index >= 0) profiles[index] = normalized else profiles += normalized
        prefs.edit { putString(KEY_EMBY_SERVER_PROFILES, json.encodeToString(profiles)) }
        if (makeActive) activateEmbyServer(normalized.id)
    }

    fun activateEmbyServer(profileId: String): Boolean {
        val profile = embyServerProfiles.firstOrNull { it.id == profileId } ?: return false
        activeEmbyServerId = profile.id
        embyHost = profile.host
        embyPort = profile.port
        embyUseHttps = profile.useHttps
        embyBasePath = profile.basePath
        embyUserId = profile.userId
        embyUsername = profile.username
        embyMacAddress = profile.macAddress
        embyAutoWakeEnabled = profile.autoWakeEnabled
        return true
    }

    fun activeEmbyServerProfile(): EmbyServerProfile? {
        fun EmbyServerProfile.matchesCurrentEndpoint() =
            host.equals(embyHost, ignoreCase = true) &&
                port == embyPort &&
                useHttps == embyUseHttps &&
                basePath == embyBasePath
        return embyServerProfiles
            .firstOrNull { it.id == activeEmbyServerId && it.matchesCurrentEndpoint() }
            ?: embyServerProfiles.firstOrNull { it.matchesCurrentEndpoint() }
    }

    fun removeEmbyServerProfile(profileId: String): EmbyServerProfile? {
        val profiles = embyServerProfiles
        val removed = profiles.firstOrNull { it.id == profileId } ?: return null
        val remaining = profiles.filterNot { it.id == profileId }
        prefs.edit { putString(KEY_EMBY_SERVER_PROFILES, json.encodeToString(remaining)) }
        if (activeEmbyServerId == profileId) {
            val replacement = remaining.firstOrNull()
            if (replacement != null) activateEmbyServer(replacement.id) else {
                activeEmbyServerId = null
                embyHost = ""
                embyUserId = null
                embyUsername = null
                embyMacAddress = null
            }
        }
        return removed
    }

    fun replaceEmbyServerProfiles(profiles: List<EmbyServerProfile>, activeId: String?) {
        val normalized = profiles.map(::normalizeEmbyServerProfile)
        prefs.edit { putString(KEY_EMBY_SERVER_PROFILES, json.encodeToString(normalized)) }
        val active = normalized.firstOrNull { it.id == activeId } ?: normalized.firstOrNull()
        if (active != null) activateEmbyServer(active.id) else {
            activeEmbyServerId = null
            embyHost = ""
            embyUserId = null
            embyUsername = null
            embyMacAddress = null
        }
    }

    fun updateActiveEmbyServerMac(mac: String) {
        embyMacAddress = mac
        val active = activeEmbyServerProfile() ?: return
        upsertEmbyServerProfile(active.copy(macAddress = mac), makeActive = true)
    }

    private fun normalizeEmbyServerProfile(profile: EmbyServerProfile) = profile.copy(
        host = NetworkEndpoint.normalizeHost(profile.host),
        port = NetworkEndpoint.validPortOrDefault(profile.port, DEFAULT_EMBY_PORT),
        basePath = normalizeBasePath(profile.basePath),
        authenticated = profile.authenticated || !profile.userId.isNullOrBlank(),
    )

    private fun normalizeBasePath(value: String): String =
        runCatching { com.ambient.tv.network.BasePathNormalizer.normalize(value) }.getOrDefault("")

    fun upsertServerProfile(profile: ServerProfile, makeActive: Boolean = true) {
        val normalized = normalizeServerProfile(profile)
        val profiles = serverProfiles.toMutableList()
        val existingIndex = profiles.indexOfFirst { it.id == normalized.id }
        if (existingIndex >= 0) profiles[existingIndex] = normalized else profiles.add(normalized)
        prefs.edit { putString(KEY_SERVER_PROFILES, json.encodeToString(profiles)) }
        if (makeActive) activateServer(normalized.id)
    }

    fun activateServer(serverId: String): Boolean {
        val profile = serverProfiles.firstOrNull { it.id == serverId } ?: return false
        activeServerId = profile.id
        host = profile.host
        port = profile.port
        plexUseHttps = profile.useHttps
        macAddress = profile.macAddress
        autoWakeEnabled = profile.autoWakeEnabled
        return true
    }

    fun activeServerProfile(): ServerProfile? {
        val profiles = serverProfiles
        return profiles.firstOrNull { it.id == activeServerId }
            ?: profiles.firstOrNull { it.host.equals(host, ignoreCase = true) && it.port == port }
    }

    fun removeServerProfile(serverId: String): ServerProfile? {
        val profiles = serverProfiles
        val removed = profiles.firstOrNull { it.id == serverId } ?: return null
        val remaining = profiles.filterNot { it.id == serverId }
        prefs.edit { putString(KEY_SERVER_PROFILES, json.encodeToString(remaining)) }
        if (activeServerId == serverId) {
            val replacement = remaining.firstOrNull()
            if (replacement != null) {
                activateServer(replacement.id)
            } else {
                activeServerId = null
                host = ""
                port = DEFAULT_PORT
                plexUseHttps = false
                macAddress = null
            }
        }
        return removed
    }


    fun replaceServerProfiles(profiles: List<ServerProfile>, activeId: String?) {
        val normalizedProfiles = profiles.map(::normalizeServerProfile)
        prefs.edit { putString(KEY_SERVER_PROFILES, json.encodeToString(normalizedProfiles)) }
        val active = normalizedProfiles.firstOrNull { it.id == activeId } ?: normalizedProfiles.firstOrNull()
        if (active != null) {
            activeServerId = active.id
            host = active.host
            port = active.port
            plexUseHttps = active.useHttps
            macAddress = active.macAddress
            autoWakeEnabled = active.autoWakeEnabled
        } else {
            activeServerId = null
            host = ""
            port = DEFAULT_PORT
            plexUseHttps = false
            macAddress = null
        }
    }

    private fun normalizeServerProfile(profile: ServerProfile): ServerProfile = profile.copy(
        host = NetworkEndpoint.normalizeHost(profile.host),
        port = NetworkEndpoint.validPortOrDefault(profile.port, DEFAULT_PORT),
    )

    fun portableLibraryPreferences(): Map<String, String> = run { flushFocusWrites(); prefs.all }
        .filterKeys { key -> key.startsWith("section_sort_") || key.startsWith("section_filter_") || key.startsWith("section_view_") || key.startsWith("section_page_") || key.startsWith("section_focus_") }
        .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
        .toMap()

    fun restorePortableLibraryPreferences(values: Map<String, String>) {
        flushFocusWrites()
        prefs.edit {
            prefs.all.keys
                .filter { it.startsWith("section_sort_") || it.startsWith("section_filter_") || it.startsWith("section_view_") || it.startsWith("section_page_") || it.startsWith("section_focus_") }
                .forEach(::remove)
            values.forEach { (key, value) ->
                if (key.startsWith("section_sort_") || key.startsWith("section_filter_") || key.startsWith("section_view_") || key.startsWith("section_page_") || key.startsWith("section_focus_")) {
                    putString(key, value)
                }
            }
        }
    }

    fun updateActiveServerMac(mac: String) {
        macAddress = mac
        val active = activeServerProfile() ?: return
        upsertServerProfile(active.copy(macAddress = mac), makeActive = true)
    }

    private fun migrateLegacyProfile(): List<ServerProfile> {
        if (host.isBlank()) return emptyList()
        val profile = ServerProfile(
            id = activeServerId ?: UUID.randomUUID().toString(),
            name = host,
            host = host,
            port = port,
            useHttps = plexUseHttps,
            macAddress = macAddress,
            autoWakeEnabled = autoWakeEnabled,
        )
        activeServerId = profile.id
        prefs.edit { putString(KEY_SERVER_PROFILES, json.encodeToString(listOf(profile))) }
        return listOf(profile)
    }

    companion object {
        private const val FOCUS_WRITE_DELAY_MS = 750L
        const val DEFAULT_PORT = 32400
        const val KEY_PLEX_ENABLED = "plex_enabled"
        const val KEY_JELLYFIN_ENABLED = "jellyfin_enabled"
        const val KEY_JELLYFIN_HOST = "jellyfin_host"
        const val KEY_JELLYFIN_PORT = "jellyfin_port"
        const val KEY_JELLYFIN_HTTPS = "jellyfin_https"
        const val KEY_JELLYFIN_BASE_PATH = "jellyfin_base_path"
        const val KEY_WG_ENABLED = "wireguard_enabled"
        const val KEY_WG_PROFILE = "wireguard_profile"
        const val KEY_WG_PLEX = "wireguard_plex_enabled"
        const val KEY_WG_JELLYFIN = "wireguard_jellyfin_enabled"
        const val KEY_WG_TVHEADEND = "wireguard_tvheadend_enabled"
        const val KEY_JELLYFIN_MAC = "jellyfin_mac"
        const val KEY_JELLYFIN_AUTO_WAKE = "jellyfin_auto_wake_enabled"
        const val KEY_ACTIVE_JELLYFIN_SERVER_ID = "active_jellyfin_server_id"
        const val KEY_JELLYFIN_SERVER_PROFILES = "jellyfin_server_profiles_v1"
        const val KEY_JELLYFIN_USER_ID = "jellyfin_user_id"
        const val KEY_JELLYFIN_USERNAME = "jellyfin_username"
        const val KEY_JELLYFIN_HIDDEN_SECTION_KEYS = "jellyfin_hidden_section_keys"
        const val DEFAULT_JELLYFIN_PORT = 8096
        const val KEY_EMBY_ENABLED = "emby_enabled"
        const val KEY_EMBY_HOST = "emby_host"
        const val KEY_EMBY_PORT = "emby_port"
        const val KEY_EMBY_HTTPS = "emby_https"
        const val KEY_EMBY_BASE_PATH = "emby_base_path"
        const val KEY_WG_EMBY = "wireguard_emby_enabled"
        const val KEY_EMBY_MAC = "emby_mac"
        const val KEY_EMBY_AUTO_WAKE = "emby_auto_wake_enabled"
        const val KEY_ACTIVE_EMBY_SERVER_ID = "active_emby_server_id"
        const val KEY_EMBY_SERVER_PROFILES = "emby_server_profiles_v1"
        const val KEY_EMBY_USER_ID = "emby_user_id"
        const val KEY_EMBY_USERNAME = "emby_username"
        const val KEY_EMBY_HIDDEN_SECTION_KEYS = "emby_hidden_section_keys"
        const val DEFAULT_EMBY_PORT = 8096
        const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
        const val KEY_SPOTIFY_ENABLED = "spotify_enabled"
        const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
        const val KEY_SPOTIFY_DEVICE_NAME = "spotify_device_name"
        const val KEY_SPOTIFY_DEVICE_ID = "spotify_device_id"
        const val KEY_SPOTIFY_BITRATE = "spotify_bitrate_kbps"
        const val KEY_SPOTIFY_VISUALIZER = "spotify_visualizer_mode"
        const val KEY_SPOTIFY_SUBMENU_KEYS = "spotify_submenu_keys"
        val SPOTIFY_SUBMENU_KEYS = setOf("home", "liked", "playlists", "albums", "artists", "now_playing")
        val DEFAULT_SPOTIFY_SUBMENU_KEYS = listOf("home", "liked", "playlists", "albums", "artists", "now_playing")
        const val KEY_TVH_SUBMENU_KEYS = "tvh_submenu_keys"
        val TVH_SUBMENU_KEYS = setOf("live", "guide", "recordings", "timers")
        val DEFAULT_TVH_SUBMENU_KEYS = listOf("live", "guide", "recordings", "timers")
        const val KEY_PLEX_HIDDEN_SECTION_KEYS = "plex_hidden_section_keys"
        const val KEY_SIDEBAR_SETTINGS_SHORTCUT_HIDDEN = "sidebar_settings_shortcut_hidden"
        const val KEY_HOME_ROWS = "home_rows_v1"
        const val KEY_SYSTEM_HOME_HIDDEN_ROWS = "system_home_hidden_rows"
        // Plex's own rows repeat much of what the other rows show, and a library has many of them.
        val DEFAULT_SYSTEM_HOME_HIDDEN_ROWS = setOf("${HomeRowDefinition.SYSTEM_PLEX}.${HomeRowDefinition.CONTENT_HUBS}")
        const val KEY_HOME_ROWS_DEFAULT_POSTER = "home_rows_default_poster"
        val ALL_CONTINUE_DEFAULT_SYSTEMS = setOf(
            HomeRowDefinition.SYSTEM_PLEX,
            HomeRowDefinition.SYSTEM_JELLYFIN,
            HomeRowDefinition.SYSTEM_EMBY,
        )
        val HOME_SYSTEMS = setOf(
            HomeRowDefinition.SYSTEM_ALL,
            HomeRowDefinition.SYSTEM_PLEX,
            HomeRowDefinition.SYSTEM_JELLYFIN,
            HomeRowDefinition.SYSTEM_EMBY,
            HomeRowDefinition.SYSTEM_TVHEADEND,
            HomeRowDefinition.SYSTEM_YOUTUBE,
            HomeRowDefinition.SYSTEM_SPOTIFY,
            HomeRowDefinition.SYSTEM_PHOTOS,
        )
        const val KEY_INITIAL_SETUP_COMPLETED = "initial_setup_completed"
        const val KEY_HOST = "server_host"
        const val KEY_PORT = "server_port"
        const val KEY_PLEX_HTTPS = "plex_https"
        const val KEY_MAC = "server_mac"
        const val KEY_AUTO_WAKE = "auto_wake_enabled"
        const val KEY_WOL_RETRY_COUNT = "wol_retry_count"
        const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        const val KEY_SERVER_PROFILES = "server_profiles_v1"
        const val KEY_UI_LANGUAGE = "ui_language"
        const val KEY_METADATA_LANGUAGE = "metadata_language"
        const val KEY_AUDIO_LANGUAGE = "preferred_audio_language"
        const val KEY_SUBTITLE_LANGUAGE = "preferred_subtitle_language"
        const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"
        const val KEY_FORCED_SUBTITLES_ENABLED = "forced_subtitles_enabled"
        const val KEY_CHOOSE_SERVER_ON_STARTUP = "choose_server_on_startup"
        const val KEY_LOCAL_NETWORK_ONLY = "local_network_only"
        const val KEY_PLEX_THEME_MUSIC = "plex_theme_music_enabled"
        const val KEY_JELLYFIN_THEME_MUSIC = "jellyfin_theme_music_enabled"
        const val KEY_EMBY_THEME_MUSIC = "emby_theme_music_enabled"
        const val KEY_THEME_MUSIC_VOLUME = "theme_music_volume"
        const val DEFAULT_THEME_MUSIC_VOLUME = 0.4f
        const val KEY_AUTO_SELECT_DISPLAY_VERSION = "auto_select_display_version"
        const val KEY_DISPLAY_CHANGE_NOTIFICATIONS = "display_change_notifications"
        const val KEY_SVP_AVAILABLE = "svp_available"
        const val KEY_DISPLAY_REFRESH_ADJUSTMENT = "display_refresh_adjustment"
        const val KEY_DISPLAY_MODE_WHITELIST = "display_mode_whitelist"
        const val KEY_PREFERRED_DISPLAY_MODE = "preferred_display_mode"
        const val DISPLAY_MODE_AUTO_CONTENT = "auto_content"
        const val DISPLAY_MODE_AUTO_DISPLAY = "auto_display"
        const val KEY_ALLOW_DOUBLE_REFRESH_RATE = "allow_double_refresh_rate"
        const val KEY_ALLOW_THREE_TWO_PULLDOWN = "allow_three_two_pulldown"
        const val KEY_ALLOW_HIGH_REFRESH_DOUBLING = "allow_high_refresh_doubling"
        const val KEY_GPU_RENDER_RESOLUTION = "gpu_render_resolution"
        const val KEY_MPV_SDR_CURVE = "mpv_sdr_curve"
        const val KEY_SVP_EXPLICIT_CHOICE = "svp_explicit_choice"
        const val KEY_MPV_ZERO_COPY_FAILED = "mpv_zero_copy_failed"
        const val GPU_RENDER_AUTO = "auto"
        const val GPU_RENDER_PANEL = "panel"
        const val GPU_RENDER_UI = "ui"
        val GPU_RENDER_RESOLUTIONS = listOf(GPU_RENDER_AUTO, GPU_RENDER_PANEL, GPU_RENDER_UI)
        const val MPV_SDR_CURVE_AUTO = "auto"
        const val MPV_SDR_CURVE_GAMMA22 = "gamma2.2"
        const val MPV_SDR_CURVE_BT1886 = "bt.1886"
        val MPV_SDR_CURVES = listOf(MPV_SDR_CURVE_AUTO, MPV_SDR_CURVE_GAMMA22, MPV_SDR_CURVE_BT1886)
        const val KEY_DISPLAY_SWITCH_PAUSE_MS = "display_switch_pause_ms"
        const val KEY_SYNC_PLAYBACK_TO_DISPLAY = "sync_playback_to_display"
        const val DISPLAY_SWITCH_PAUSE_STEP_MS = 500
        const val DISPLAY_SWITCH_PAUSE_MAX_MS = 20_000
        /** Typical HDMI resync of an AV receiver with passthrough. */
        const val DISPLAY_SWITCH_PAUSE_RECOMMENDED_MS = 3_000

        /** Rounds to the nearest 0.5 s step within off..20 s. */
        fun normalizeDisplaySwitchPauseMs(value: Int): Int {
            val clamped = value.coerceIn(0, DISPLAY_SWITCH_PAUSE_MAX_MS)
            return ((clamped + DISPLAY_SWITCH_PAUSE_STEP_MS / 2) / DISPLAY_SWITCH_PAUSE_STEP_MS) * DISPLAY_SWITCH_PAUSE_STEP_MS
        }
        val DISPLAY_REFRESH_ADJUSTMENTS = setOf("off", "always", "start_stop", "start")
        const val DISPLAY_REFRESH_OFF = "off"
        const val DISPLAY_REFRESH_ALWAYS = "always"
        const val DISPLAY_REFRESH_START_STOP = "start_stop"
        const val DISPLAY_REFRESH_START = "start"
        const val KEY_SHOW_LIBRARY_ROWS_ON_HOME = "show_library_rows_on_home"
        const val KEY_MEDIA_SELECTION_BEHAVIOR = "media_selection_behavior"
        const val KEY_SHOW_CONTINUE_HUB = "show_continue_hub"
        const val KEY_SHOW_RECENT_HUBS = "show_recent_hubs"
        const val KEY_SHOW_COLLECTION_HUBS = "show_collection_hubs"
        const val KEY_PLEX_AUTO_REFRESH_INTERVAL_SECONDS = "plex_auto_refresh_interval_seconds"
        val PLEX_REFRESH_INTERVALS = setOf(0, 30, 60, 120)
        const val KEY_LIBRARY_GRID_COLUMNS = "library_grid_columns"
        const val KEY_LIBRARY_LIST_ROWS = "library_list_rows"
        const val KEY_HIGH_QUALITY_ASSETS = "high_quality_assets_enabled"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_OVERSCAN_PERCENT = "overscan_percent"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_CUSTOM_ACCENT_HEX = "custom_accent_hex"
        const val KEY_CUSTOM_PLAYBACK_ACCENT_HEX = "custom_playback_accent_hex"
        const val KEY_DUAL_ACCENT_ENABLED = "dual_accent_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SDR_TO_HDR_ENABLED = "sdr_to_hdr_enabled"
        const val KEY_PERFORMANCE_MODE = "performance_mode"
        const val KEY_UI_PERFORMANCE_MODE = "ui_performance_mode"
        const val KEY_PLAYBACK_PERFORMANCE_MODE = "playback_performance_mode"
        const val KEY_HOME_BACKGROUND_EVERYWHERE = "home_background_everywhere"
        const val KEY_FOCUS_ARTWORK_BACKDROP = "focus_artwork_backdrop"
        const val KEY_HOME_HERO_STYLE = "home_hero_style"
        const val KEY_HOME_HERO_INTENSITY = "home_hero_intensity"
        const val KEY_HOME_HERO_SPREAD = "home_hero_spread"
        const val KEY_NOTIFICATION_DISPLAY_DURATION = "notification_display_duration"
        const val KEY_NAV_SOUNDS_ENABLED = "nav_sounds_enabled"
        const val KEY_NAV_SOUNDS_VOLUME = "nav_sounds_volume"
        const val DEFAULT_NAV_SOUNDS_VOLUME = 0.3f
        val PERFORMANCE_MODES = setOf("auto", "balanced", "maximum", "low")
        val NOTIFICATION_DISPLAY_DURATIONS = setOf("off", "short", "normal", "long")
        const val KEY_PLAYBACK_BACKEND = "playback_backend"
        const val KEY_PLAYER_TECH_BADGES_VISIBLE = "player_tech_badges_visible"
        const val KEY_TVH_PLAYBACK_BACKEND = "tvh_playback_backend"
        val PLAYBACK_BACKENDS = setOf("auto", "media3", "vlc", "mpv")
        val LIVE_TIMESHIFT_SIZES = listOf(0, 512, 1024, 2048, 4096)
        const val KEY_VISIBLE_BACKGROUND_VIDEO_PLAYBACK = "visible_background_video_playback"
        const val KEY_MPV_VIDEO_OUTPUT_MODE = "mpv_video_output_mode"
        const val KEY_MPV_SCALING_FILTER = "mpv_scaling_filter"
        const val KEY_MPV_DEBAND = "mpv_deband"
        const val KEY_VLC_SCALING_FILTER = "vlc_scaling_filter"
        val MPV_VIDEO_OUTPUT_MODES = setOf("auto", "direct", "gpu", "vulkan")
        val MPV_VIDEO_DECODERS = setOf("auto", "mediacodec", "vulkan", "software")
        val VLC_VIDEO_OUTPUT_MODES = setOf("auto", "android", "vulkan")
        val MPV_SCALING_FILTERS = VideoImageCatalog.mpvScalers.keys
        val VLC_SCALING_FILTERS = VideoImageCatalog.vlcScalers.keys
        const val KEY_MEDIA_AUDIO_PASSTHROUGH = "media_audio_passthrough"
        /** @deprecated Migrated into [KEY_MEDIA_AUDIO_PASSTHROUGH]; kept for one-shot local migrate. */
        const val KEY_PLEX_AUDIO_PASSTHROUGH = "plex_audio_passthrough"
        /** @deprecated Migrated into [KEY_MEDIA_AUDIO_PASSTHROUGH]; kept for one-shot local migrate. */
        const val KEY_TVH_AUDIO_PASSTHROUGH = "tvh_audio_passthrough"
        const val KEY_AUDIO_VISUALIZER_MODE = "audio_visualizer_mode"
        const val KEY_PROJECTM_SHOW_PRESET_NAME = "projectm_show_preset_name"
        const val KEY_PROJECTM_PRESET_INTERVAL = "projectm_preset_interval"
        const val KEY_PROJECTM_TRANSITION_SECONDS = "projectm_transition_seconds"
        const val KEY_PROJECTM_QUALITY = "projectm_quality"
        const val KEY_PROJECTM_RESOLUTION = "projectm_resolution"
        const val KEY_PROJECTM_HDR_4K = "projectm_hdr_4k"
        const val KEY_PROJECTM_CATEGORY = "projectm_category"
        const val KEY_BIG_SEEK_JUMP_SECONDS = "big_seek_jump_seconds"
        const val KEY_YOUTUBE_SPONSORBLOCK = "youtube_sponsorblock_enabled"
        const val KEY_YOUTUBE_AUTOPLAY_NEXT = "youtube_autoplay_next_enabled"
        const val KEY_YOUTUBE_MEDIA_SELECTION_BEHAVIOR = "youtube_media_selection_behavior"
        const val KEY_NEXT_UP_ENABLED = "next_up_enabled"
        const val KEY_NEXT_UP_LEAD_SECONDS = "next_up_lead_seconds"
        const val KEY_NEXT_UP_AUTOPLAY_EPISODES = "next_up_autoplay_episodes"
        const val KEY_NEXT_UP_AUTOPLAY_COLLECTIONS = "next_up_autoplay_collections"
        const val KEY_NEXT_UP_OVERLAY_ENABLED = "next_up_overlay_enabled"
        const val KEY_NEXT_UP_STILL_WATCHING = "next_up_still_watching_episodes"
        val NEXT_UP_LEAD_SECONDS = setOf(10, 20, 30, 60)
        val NEXT_UP_STILL_WATCHING_VALUES = setOf(0, 2, 3, 5)
        const val KEY_SLEEP_AUTO_START = "sleep_auto_start"
        const val KEY_SLEEP_WARNING_MINUTES = "sleep_warning_minutes"
        const val KEY_SLEEP_DAILY_STOP = "sleep_daily_stop_minute"
        const val SLEEP_DAILY_STOP_OFF = -1
        const val SLEEP_AUTO_START_OFF = "off"
        const val SLEEP_AUTO_START_END = "end"
        const val SLEEP_AUTO_START_INACTIVITY = "inactivity"
        val SLEEP_AUTO_START_KEYS = listOf(SLEEP_AUTO_START_OFF, "30", "60", "90", "120", SLEEP_AUTO_START_END, SLEEP_AUTO_START_INACTIVITY)
        val SLEEP_WARNING_VALUES = listOf(0, 1, 2, 5)
        const val SLEEP_WARNING_DEFAULT = 1
        const val KEY_YOUTUBE_FEEDS = "youtube_feed_keys"
        const val KEY_YOUTUBE_MAX_VIDEO_HEIGHT = "youtube_max_video_height"
        const val KEY_YOUTUBE_VIDEO_PRESET = "youtube_video_preset"
        val YOUTUBE_FEED_KEYS = setOf("home", "subscriptions", "history", "playlists", "shorts", "music", "news", "gaming", "live", "kids")
        val DEFAULT_YOUTUBE_FEEDS = listOf("home", "subscriptions", "history", "playlists", "shorts", "music", "news", "gaming", "live", "kids")
        const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"
        const val KEY_SCREENSAVER_TIMEOUT_MINUTES = "screensaver_timeout_minutes"
        const val KEY_SCREENSAVER_MODE = "screensaver_mode"
        const val KEY_SCREENSAVER_SHOW_CLOCK = "screensaver_show_clock"
        const val KEY_SCREENSAVER_SHOW_DATE = "screensaver_show_date"
        const val KEY_SCREENSAVER_24H_CLOCK = "screensaver_24h_clock"
        const val KEY_SCREENSAVER_USE_ARTWORK = "screensaver_use_artwork"
        const val KEY_SCREENSAVER_ARTWORK_INTERVAL_SECONDS = "screensaver_artwork_interval_seconds"
        const val KEY_SCREENSAVER_DIM_PERCENT = "screensaver_dim_percent"
        const val KEY_SCREENSAVER_QUALITY = "screensaver_quality"
        const val KEY_TVH_ENABLED = "tvh_enabled"
        const val KEY_TVH_HOST = "tvh_host"
        const val KEY_TVH_LOGIN_VERIFIED = "tvh_login_verified"
        const val KEY_TVH_LOGIN_STATE = "tvh_login_state"
        const val KEY_PLEX_LOGIN_VERIFIED = "plex_login_verified"
        const val KEY_LOGIN_REJECTED = "login_rejected_profiles"
        const val KEY_TVH_PORT = "tvh_port"
        const val KEY_TVH_HTTPS = "tvh_https"
        const val KEY_TVH_USERNAME = "tvh_username"
        const val KEY_TVH_STREAM_PROFILE = "tvh_stream_profile"
        const val KEY_TVH_MAC = "tvh_mac"
        const val KEY_TVH_AUTO_WAKE = "tvh_auto_wake_enabled"
        const val KEY_TVH_SHOW_ON_HOME = "tvh_show_on_home"
        const val KEY_TVH_DEINTERLACE_MODE = "tvh_deinterlace_mode"
        const val KEY_TVH_DEINTERLACE_FILTER = "tvh_deinterlace_filter"
        const val DEFAULT_TVH_PORT = 9981
        const val KEY_CLIENT_ID = "client_identifier"
        const val KEY_RECENT_SEARCHES = "recent_searches"
    }
}

/** See [AppPreferences.tvheadendLoginState]. */
enum class TvheadendLoginState { UNKNOWN, VERIFIED, REJECTED }

/** Stored state wins; otherwise the legacy boolean (false → unknown), otherwise verified. */
internal fun storedTvheadendLoginState(stored: String?, legacyVerified: Boolean?): TvheadendLoginState =
    stored?.let { name -> TvheadendLoginState.entries.firstOrNull { it.name == name } }
        ?: when (legacyVerified) {
            false -> TvheadendLoginState.UNKNOWN
            else -> TvheadendLoginState.VERIFIED
        }

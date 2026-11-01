package com.ambient.tv.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(
    @EncryptedPrefs private val prefs: SharedPreferences,
    private val appPreferences: AppPreferences,
) {
    val tmdbCredentialVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** User-owned TMDB API key; exported only when credentials are explicitly included. */
    var tmdbApiKey: String?
        get() = prefs.getString("tmdb_api_key", null)
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) remove("tmdb_api_key") else putString("tmdb_api_key", value)
            }
            tmdbCredentialVersion.value++
        }
    var wireGuardPrivateKey: String?
        get() = prefs.getString(KEY_WG_PRIVATE, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_WG_PRIVATE) else putString(KEY_WG_PRIVATE, value)
        }

    var wireGuardPresharedKey: String?
        get() = prefs.getString(KEY_WG_PSK, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_WG_PSK) else putString(KEY_WG_PSK, value)
        }

    fun smbPasswordForSource(id: String): String? = prefs.getString("smb_password_$id", null)
    fun setSmbPassword(id: String, password: String?) {
        prefs.edit().apply { if (password == null) remove("smb_password_$id") else putString("smb_password_$id", password) }.apply()
    }

    /** IPTV playlists (JSON). Encrypted: provider URLs usually carry the account in the query string. */
    var iptvSourcesJson: String?
        get() = prefs.getString("iptv_sources", null)
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove("iptv_sources") else putString("iptv_sources", value) }

    /**
     * Telegram application credentials are configured per installation, just like Spotify's
     * client ID. The ID and hash are written in one encrypted-preferences transaction;
     * neither is ever written to the plain AppPreferences or included in a normal backup.
     */
    val monogramApiId: String get() = prefs.getString("monogram_api_id", "").orEmpty()
    val monogramApiHash: String get() = prefs.getString("monogram_api_hash", "").orEmpty()

    fun monogramCredentials(): Pair<Int, String>? {
        val id = monogramApiId.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val hash = monogramApiHash.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
            ?: return null
        return id to hash
    }

    fun setMonogramCredentials(id: Int, hash: String) {
        require(id > 0 && hash.matches(Regex("[a-fA-F0-9]{32}"))) {
            "Invalid Monogram API application credentials"
        }
        prefs.edit {
            putString("monogram_api_id", id.toString())
            putString("monogram_api_hash", hash.lowercase(java.util.Locale.ROOT))
        }
    }

    /** OpenSubtitles.com: the user's own API consumer key and (optional) account for the download quota. */
    var openSubtitlesApiKey: String?
        get() = prefs.getString("opensubtitles_api_key", null)
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove("opensubtitles_api_key") else putString("opensubtitles_api_key", value.trim()) }
    var openSubtitlesUsername: String?
        get() = prefs.getString("opensubtitles_username", null)
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove("opensubtitles_username") else putString("opensubtitles_username", value.trim()) }
    var openSubtitlesPassword: String?
        get() = prefs.getString("opensubtitles_password", null)
        set(value) = prefs.edit { if (value.isNullOrEmpty()) remove("opensubtitles_password") else putString("opensubtitles_password", value) }

    /** SFTP private key (OpenSSH/PEM/PuTTY text); its passphrase lives in the source's password slot. */
    fun sshKeyForSource(id: String): String? = prefs.getString("ssh_key_$id", null)
    fun setSshKey(id: String, key: String?) {
        prefs.edit().apply { if (key.isNullOrBlank()) remove("ssh_key_$id") else putString("ssh_key_$id", key) }.apply()
    }

    init {
        migrateLegacyTokenOnce()
    }

    var token: String?
        // Bound to the active profile's address so a changed host/port never receives the token.
        get() = if (appPreferences.matchesActivePlexEndpoint) appPreferences.activeServerId?.let(::tokenForServer) else null
        set(value) {
            appPreferences.activeServerId?.let { setTokenForServer(it, value) }
        }

    fun tokenForServer(serverId: String): String? = prefs.getString(keyForServer(serverId), null)

    fun setTokenForServer(serverId: String, token: String?) {
        prefs.edit {
            if (token.isNullOrBlank()) remove(keyForServer(serverId)) else putString(keyForServer(serverId), token)
        }
    }

    fun removeTokenForServer(serverId: String) {
        prefs.edit { remove(keyForServer(serverId)) }
    }

    var jellyfinToken: String?
        get() = if (appPreferences.matchesActiveJellyfinEndpoint) appPreferences.activeJellyfinServerId?.let(::jellyfinTokenForServer) else null
        set(value) { appPreferences.activeJellyfinServerId?.let { setJellyfinTokenForServer(it, value) } }

    fun jellyfinTokenForServer(serverId: String): String? = prefs.getString("$KEY_JELLYFIN_TOKEN_PREFIX$serverId", null)

    fun setJellyfinTokenForServer(serverId: String, token: String?) {
        prefs.edit {
            val key = "$KEY_JELLYFIN_TOKEN_PREFIX$serverId"
            if (token.isNullOrBlank()) remove(key) else putString(key, token)
        }
    }

    fun removeJellyfinTokenForServer(serverId: String) {
        prefs.edit { remove("$KEY_JELLYFIN_TOKEN_PREFIX$serverId") }
    }

    var jellyfinPassword: String?
        get() = appPreferences.activeJellyfinServerId?.let(::jellyfinPasswordForServer)
        set(value) { appPreferences.activeJellyfinServerId?.let { setJellyfinPasswordForServer(it, value) } }

    fun jellyfinPasswordForServer(serverId: String): String? =
        prefs.getString("$KEY_JELLYFIN_PASSWORD_PREFIX$serverId", null)

    fun setJellyfinPasswordForServer(serverId: String, password: String?) {
        prefs.edit {
            val key = "$KEY_JELLYFIN_PASSWORD_PREFIX$serverId"
            if (password.isNullOrEmpty()) remove(key) else putString(key, password)
        }
    }

    fun removeJellyfinPasswordForServer(serverId: String) {
        prefs.edit { remove("$KEY_JELLYFIN_PASSWORD_PREFIX$serverId") }
    }

    fun retainOnlyJellyfinCredentials(serverIds: Set<String>) {
        val keysToRemove = prefs.all.keys.filter { key ->
            when {
                key.startsWith(KEY_JELLYFIN_TOKEN_PREFIX) -> key.removePrefix(KEY_JELLYFIN_TOKEN_PREFIX) !in serverIds
                key.startsWith(KEY_JELLYFIN_PASSWORD_PREFIX) -> key.removePrefix(KEY_JELLYFIN_PASSWORD_PREFIX) !in serverIds
                else -> false
            }
        }
        if (keysToRemove.isNotEmpty()) prefs.edit { keysToRemove.forEach(::remove) }
    }

    var embyToken: String?
        get() = if (appPreferences.matchesActiveEmbyEndpoint) appPreferences.activeEmbyServerId?.let(::embyTokenForServer) else null
        set(value) { appPreferences.activeEmbyServerId?.let { setEmbyTokenForServer(it, value) } }

    fun embyTokenForServer(serverId: String): String? = prefs.getString("$KEY_EMBY_TOKEN_PREFIX$serverId", null)

    fun setEmbyTokenForServer(serverId: String, token: String?) {
        prefs.edit {
            val key = "$KEY_EMBY_TOKEN_PREFIX$serverId"
            if (token.isNullOrBlank()) remove(key) else putString(key, token)
        }
    }

    fun removeEmbyTokenForServer(serverId: String) {
        prefs.edit { remove("$KEY_EMBY_TOKEN_PREFIX$serverId") }
    }

    var embyPassword: String?
        get() = appPreferences.activeEmbyServerId?.let(::embyPasswordForServer)
        set(value) { appPreferences.activeEmbyServerId?.let { setEmbyPasswordForServer(it, value) } }

    fun embyPasswordForServer(serverId: String): String? =
        prefs.getString("$KEY_EMBY_PASSWORD_PREFIX$serverId", null)

    fun setEmbyPasswordForServer(serverId: String, password: String?) {
        prefs.edit {
            val key = "$KEY_EMBY_PASSWORD_PREFIX$serverId"
            if (password.isNullOrEmpty()) remove(key) else putString(key, password)
        }
    }

    fun removeEmbyPasswordForServer(serverId: String) {
        prefs.edit { remove("$KEY_EMBY_PASSWORD_PREFIX$serverId") }
    }

    fun retainOnlyEmbyCredentials(serverIds: Set<String>) {
        val keysToRemove = prefs.all.keys.filter { key ->
            when {
                key.startsWith(KEY_EMBY_TOKEN_PREFIX) -> key.removePrefix(KEY_EMBY_TOKEN_PREFIX) !in serverIds
                key.startsWith(KEY_EMBY_PASSWORD_PREFIX) -> key.removePrefix(KEY_EMBY_PASSWORD_PREFIX) !in serverIds
                else -> false
            }
        }
        if (keysToRemove.isNotEmpty()) prefs.edit { keysToRemove.forEach(::remove) }
    }

    var tvheadendPassword: String?
        get() = prefs.getString(KEY_TVH_PASSWORD, null)
        set(value) = prefs.edit {
            if (value.isNullOrEmpty()) remove(KEY_TVH_PASSWORD) else putString(KEY_TVH_PASSWORD, value)
        }

    var spotifyWebAccessToken: String?
        get() = prefs.getString(KEY_SPOTIFY_WEB_TOKEN, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_SPOTIFY_WEB_TOKEN) else putString(KEY_SPOTIFY_WEB_TOKEN, value)
        }


    var spotifyWebRefreshToken: String?
        get() = prefs.getString(KEY_SPOTIFY_WEB_REFRESH_TOKEN, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_SPOTIFY_WEB_REFRESH_TOKEN) else putString(KEY_SPOTIFY_WEB_REFRESH_TOKEN, value)
        }

    var spotifyWebAccessTokenExpiresAtMs: Long
        get() = prefs.getLong(KEY_SPOTIFY_WEB_EXPIRES_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_SPOTIFY_WEB_EXPIRES_AT, value) }

    var spotifyWebScopes: String?
        get() = prefs.getString(KEY_SPOTIFY_WEB_SCOPES, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_SPOTIFY_WEB_SCOPES) else putString(KEY_SPOTIFY_WEB_SCOPES, value)
        }

    var spotifyAccountName: String?
        get() = prefs.getString(KEY_SPOTIFY_ACCOUNT_NAME, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_SPOTIFY_ACCOUNT_NAME) else putString(KEY_SPOTIFY_ACCOUNT_NAME, value)
        }


    var spotifyPkceVerifier: String?
        get() = prefs.getString(KEY_SPOTIFY_PKCE_VERIFIER, null)
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove(KEY_SPOTIFY_PKCE_VERIFIER) else putString(KEY_SPOTIFY_PKCE_VERIFIER, value) }

    var spotifyPkceState: String?
        get() = prefs.getString(KEY_SPOTIFY_PKCE_STATE, null)
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove(KEY_SPOTIFY_PKCE_STATE) else putString(KEY_SPOTIFY_PKCE_STATE, value) }

    /** Client ID that created the active PKCE request. Keeps browser round-trips stable even if
     * Settings are edited while Spotify is open. Client IDs are public identifiers, not secrets. */
    var spotifyPkceClientId: String?
        get() = prefs.getString(KEY_SPOTIFY_PKCE_CLIENT_ID, null)
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove(KEY_SPOTIFY_PKCE_CLIENT_ID) else putString(KEY_SPOTIFY_PKCE_CLIENT_ID, value) }

    /** Client ID associated with the stored refresh token. */
    var spotifyWebClientId: String?
        get() = prefs.getString(KEY_SPOTIFY_WEB_CLIENT_ID, null)
        set(value) = prefs.edit { if (value.isNullOrBlank()) remove(KEY_SPOTIFY_WEB_CLIENT_ID) else putString(KEY_SPOTIFY_WEB_CLIENT_ID, value) }

    fun clearSpotifyWebAuth() {
        prefs.edit {
            remove(KEY_SPOTIFY_WEB_TOKEN)
            remove(KEY_SPOTIFY_WEB_REFRESH_TOKEN)
            remove(KEY_SPOTIFY_WEB_EXPIRES_AT)
            remove(KEY_SPOTIFY_WEB_SCOPES)
            remove(KEY_SPOTIFY_ACCOUNT_NAME)
            remove(KEY_SPOTIFY_PKCE_VERIFIER)
            remove(KEY_SPOTIFY_PKCE_STATE)
            remove(KEY_SPOTIFY_PKCE_CLIENT_ID)
            remove(KEY_SPOTIFY_WEB_CLIENT_ID)
        }
    }

    fun retainOnlyServerTokens(serverIds: Set<String>) {
        val keysToRemove = prefs.all.keys
            .filter { it.startsWith(KEY_SERVER_TOKEN_PREFIX) && it != KEY_LEGACY_MIGRATION_DONE }
            .filter { key -> key.removePrefix(KEY_SERVER_TOKEN_PREFIX) !in serverIds }
        if (keysToRemove.isNotEmpty()) prefs.edit { keysToRemove.forEach(::remove) }
    }

    private fun migrateLegacyTokenOnce() {
        if (prefs.getBoolean(KEY_LEGACY_MIGRATION_DONE, false)) return
        val legacy = prefs.getString(KEY_TOKEN, null)
        if (!legacy.isNullOrBlank()) {
            val profiles = appPreferences.serverProfiles
            val destinationId = appPreferences.activeServerId
                ?: appPreferences.activeServerProfile()?.id
                ?: profiles.singleOrNull()?.id
            if (destinationId != null && prefs.getString(keyForServer(destinationId), null).isNullOrBlank()) {
                prefs.edit { putString(keyForServer(destinationId), legacy) }
            }
        }
        prefs.edit {
            remove(KEY_TOKEN)
            putBoolean(KEY_LEGACY_MIGRATION_DONE, true)
        }
    }

    private fun keyForServer(serverId: String) = "$KEY_SERVER_TOKEN_PREFIX$serverId"

    private companion object {
        const val KEY_TOKEN = "plex_token"
        const val KEY_SERVER_TOKEN_PREFIX = "plex_token_"
        const val KEY_LEGACY_MIGRATION_DONE = "plex_token_server_migration_v1"
        const val KEY_JELLYFIN_TOKEN_PREFIX = "jellyfin_token_"
        const val KEY_JELLYFIN_PASSWORD_PREFIX = "jellyfin_password_"
        const val KEY_EMBY_TOKEN_PREFIX = "emby_token_"
        const val KEY_EMBY_PASSWORD_PREFIX = "emby_password_"
        const val KEY_TVH_PASSWORD = "tvh_password"
        const val KEY_SPOTIFY_WEB_TOKEN = "spotify_web_access_token"
        const val KEY_SPOTIFY_WEB_REFRESH_TOKEN = "spotify_web_refresh_token"
        const val KEY_SPOTIFY_WEB_EXPIRES_AT = "spotify_web_expires_at"
        const val KEY_SPOTIFY_WEB_SCOPES = "spotify_web_scopes"
        const val KEY_SPOTIFY_ACCOUNT_NAME = "spotify_account_name"
        const val KEY_SPOTIFY_PKCE_VERIFIER = "spotify_pkce_verifier"
        const val KEY_SPOTIFY_PKCE_STATE = "spotify_pkce_state"
        const val KEY_SPOTIFY_PKCE_CLIENT_ID = "spotify_pkce_client_id"
        const val KEY_SPOTIFY_WEB_CLIENT_ID = "spotify_web_client_id"
        const val KEY_WG_PRIVATE = "wireguard_private_key"
        const val KEY_WG_PSK = "wireguard_preshared_key"
    }
}

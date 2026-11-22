package com.ambient.tv.data.browse

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowseScopes @Inject constructor(private val preferences: AppPreferences, private val tokens: TokenStore) {
    private val memo = mutableMapOf<String, Pair<String, String>>()
    @Synchronized fun forProvider(provider: String): String {
        val token = when (provider) {
            "plex" -> tokens.token
            "jellyfin" -> tokens.jellyfinToken
            "emby" -> tokens.embyToken
            else -> null
        }
        return scoped(provider, token)
    }

    /** The exact credential selected for a request must also be the one in its cache scope. */
    @Synchronized fun forMediaProviderToken(provider: String, token: String?): String {
        require(provider in setOf("plex", "jellyfin", "emby"))
        return scoped(provider, token)
    }

    private fun scoped(provider: String, mediaToken: String?): String {
        val p = preferences
        val identity = when (provider) {
            "plex" -> listOf(p.plexEnabled, p.activeServerId, p.host, p.port, p.plexUseHttps, mediaToken)
            "jellyfin" -> listOf(p.jellyfinEnabled, p.activeJellyfinServerId, p.jellyfinHost, p.jellyfinPort, p.jellyfinUseHttps, p.jellyfinBasePath, p.jellyfinUserId, mediaToken)
            "emby" -> listOf(p.embyEnabled, p.activeEmbyServerId, p.embyHost, p.embyPort, p.embyUseHttps, p.embyBasePath, p.embyUserId, mediaToken)
            "spotify" -> listOf(p.spotifyEnabled, tokens.spotifyAccountName, tokens.spotifyWebClientId, tokens.spotifyWebRefreshToken)
            // Not the enabled flag: disabling deletes the scope explicitly (TvheadendRepository.clearPersistentCache).
            "tvheadend" -> listOf(p.tvheadendHost.trim().lowercase(), p.tvheadendPort, p.tvheadendUseHttps, p.tvheadendUsername, tokens.tvheadendPassword.orEmpty())
            else -> listOf(provider)
        }.joinToString("\u0000") + "\u0000" + p.metadataLanguageKey
        return memo[provider]?.takeIf { it.first == identity }?.second ?: run {
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            "$provider:$hash".also { memo[provider] = identity to it }
        }
    }
}

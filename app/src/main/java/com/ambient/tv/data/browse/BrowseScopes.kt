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
        val p = preferences
        val identity = when (provider) {
            "plex" -> listOf(p.plexEnabled, p.activeServerId, p.host, p.port, p.plexUseHttps, tokens.token)
            "jellyfin" -> listOf(p.jellyfinEnabled, p.activeJellyfinServerId, p.jellyfinHost, p.jellyfinPort, p.jellyfinUseHttps, p.jellyfinBasePath, p.jellyfinUserId, tokens.jellyfinToken)
            "emby" -> listOf(p.embyEnabled, p.activeEmbyServerId, p.embyHost, p.embyPort, p.embyUseHttps, p.embyBasePath, p.embyUserId, tokens.embyToken)
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

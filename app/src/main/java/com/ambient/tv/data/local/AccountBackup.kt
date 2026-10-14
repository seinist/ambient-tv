package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

@Serializable
data class SmartTubeAccountBackup(val id: String, val name: String, val credential: String? = null)

@Serializable
data class SpotifyAccountBackup(
    val clientId: String = "",
    val name: String = "",
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0,
    val scopes: String = "",
)

internal fun validateAccountBackup(backup: SettingsBackup) {
    backup.smartTubeAccounts?.let { accounts ->
        require(accounts.size <= 50 && accounts.distinctBy { it.id }.size == accounts.size)
        accounts.forEach {
            require(it.id.isNotBlank() && it.id.length <= 256 && it.name.length <= 1024)
            require(it.credential == null || it.credential.isNotBlank() && it.credential.length <= 32 * 1024)
        }
    }
    backup.spotifyAccount?.let {
        require(it.clientId.length <= 256 && it.name.length <= 1024 && it.scopes.length <= 8192)
        require(it.accessToken == null || it.accessToken.isNotBlank() && it.accessToken.length <= 16 * 1024)
        require(it.refreshToken == null || it.refreshToken.isNotBlank() && it.refreshToken.length <= 16 * 1024)
        require(it.expiresAtMs >= 0)
        if (it.accessToken != null || it.refreshToken != null) require(it.clientId.isNotBlank())
    }
    require(backup.openSubtitlesApiKey == null || backup.openSubtitlesApiKey.isNotBlank() && backup.openSubtitlesApiKey.length <= 256)
    require((backup.openSubtitlesUsername?.length ?: 0) <= 256 && (backup.openSubtitlesPassword?.length ?: 0) <= 1024)
}

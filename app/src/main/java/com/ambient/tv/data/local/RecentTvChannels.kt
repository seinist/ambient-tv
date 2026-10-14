package com.ambient.tv.data.local

import com.ambient.tv.network.NetworkEndpoint
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

@Serializable
data class RecentTvChannels(val scope: String, val channelIds: List<String> = emptyList()) {
    fun watched(channelId: String): RecentTvChannels = if (channelId.isBlank()) this else
        copy(channelIds = (listOf(channelId) + channelIds).distinct().take(LIMIT))

    companion object {
        const val LIMIT = 12
        fun scope(host: String, port: Int, user: String): String {
            val configured = host.trim().trimEnd('/')
            val scheme = if (configured.startsWith("https://", true)) "https" else "http"
            val path = configured.substringAfter("://", configured).substringAfter('/', "").trim('/')
            val parts = listOf(scheme, NetworkEndpoint.extractHost(configured).lowercase(Locale.ROOT), port.toString(), path, user.trim())
            val canonical = parts.joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

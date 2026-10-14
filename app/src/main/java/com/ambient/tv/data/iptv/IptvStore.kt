package com.ambient.tv.data.iptv

import com.ambient.tv.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One IPTV provider or playlist: an M3U URL and, optionally, an XMLTV guide URL. */
@Serializable
data class IptvSource(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val playlistUrl: String,
    /** Empty: the playlist's own url-tvg / x-tvg-url, if it names one. */
    val epgUrl: String = "",
    /** Some providers only answer a particular player's user agent. */
    val userAgent: String = "",
    val enabled: Boolean = true,
) {
    fun validated(): IptvSource {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(name.isNotBlank() && name.length <= 80)
        require(isWebUrl(playlistUrl) && playlistUrl.length <= 4096)
        require(epgUrl.isBlank() || (isWebUrl(epgUrl) && epgUrl.length <= 4096))
        require(userAgent.length <= 256 && userAgent.none { it == '\n' || it == '\r' })
        return copy(name = name.trim(), playlistUrl = playlistUrl.trim(), epgUrl = epgUrl.trim(), userAgent = userAgent.trim())
    }

    companion object {
        fun isWebUrl(value: String): Boolean = value.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) } &&
            runCatching { java.net.URI(value.trim()).host?.isNotBlank() == true }.getOrDefault(false)
    }
}

@Singleton
class IptvStore @Inject constructor(private val tokens: TokenStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun sources(): List<IptvSource> = runCatching { json.decodeFromString<List<IptvSource>>(tokens.iptvSourcesJson ?: "[]") }
        .getOrDefault(emptyList()).mapNotNull { runCatching { it.validated() }.getOrNull() }.distinctBy { it.id }.take(MAX_SOURCES)

    @Synchronized fun save(source: IptvSource) {
        val valid = source.validated()
        val others = sources().filterNot { it.id == valid.id }
        require(others.size < MAX_SOURCES) { "Too many IPTV sources" }
        val existing = sources().indexOfFirst { it.id == valid.id }
        val next = if (existing < 0) others + valid else sources().map { if (it.id == valid.id) valid else it }
        tokens.iptvSourcesJson = json.encodeToString(next)
        changes.update { it + 1 }
    }

    /** Settings import: the backup's playlists replace this device's. */
    @Synchronized fun replaceAll(list: List<IptvSource>) {
        val valid = list.map { it.validated() }.distinctBy { it.id }.take(MAX_SOURCES)
        tokens.iptvSourcesJson = if (valid.isEmpty()) null else json.encodeToString(valid)
        changes.update { it + 1 }
    }

    @Synchronized fun remove(id: String) {
        tokens.iptvSourcesJson = json.encodeToString(sources().filterNot { it.id == id })
        changes.update { it + 1 }
    }

    companion object { const val MAX_SOURCES = 20 }
}

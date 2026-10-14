package com.ambient.tv.server

import com.ambient.tv.domain.model.DiscoveredPlexServer
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Plex GDM reply ("HTTP/1.0 200 OK" plus `Name:`/`Port:`/`Resource-Identifier:` headers). */
internal fun parsePlexGdmResponse(host: String, raw: String): DiscoveredPlexServer {
    val headers = raw.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }
        .toMap()
    return DiscoveredPlexServer(
        name = headers["name"]?.takeIf { it.isNotBlank() } ?: host,
        host = host,
        port = headers["port"]?.toIntOrNull() ?: 32400,
        machineIdentifier = headers["resource-identifier"] ?: headers["resourceidentifier"],
        version = headers["version"],
    )
}

/** Jellyfin/Emby UDP discovery reply, reduced to what the server pickers need. */
internal data class MediaBrowserAnnouncement(
    val name: String,
    val host: String,
    val port: Int,
    val https: Boolean,
    val basePath: String,
    val id: String?,
) {
    val key: String get() = id ?: "$host:$port"
}

private val discoveryJson = Json { ignoreUnknownKeys = true }

/**
 * Parses `{"Address":"http://host:8096/base","Id":"…","Name":"…"}`. The sender address is the
 * fallback when the announced URL has no host. Null for anything that is not such a reply.
 */
internal fun parseMediaBrowserDiscovery(text: String, senderHost: String?): MediaBrowserAnnouncement? {
    val json = runCatching { discoveryJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    fun string(name: String) = runCatching { json[name]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf(String::isNotBlank)
    val uri = string("Address")?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val host = uri.host ?: senderHost ?: return null
    val https = uri.scheme.equals("https", true)
    val port = if (uri.port > 0) uri.port else if (https) 443 else 8096
    val basePath = uri.path.orEmpty().trimEnd('/')
    return MediaBrowserAnnouncement(string("Name") ?: host, host, port, https, basePath, string("Id"))
}

/** A verified connection is reused for [windowMs] while it still points at the same endpoint. */
internal fun connectionReusable(online: Boolean, lastKey: String?, key: String, lastCheckMs: Long, nowMs: Long, windowMs: Long): Boolean =
    online && lastKey == key && nowMs - lastCheckMs <= windowMs

package com.ambient.tv.games.metadata

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** All persisted URLs are public identities. Provider credentials exist only in request builders. */
internal fun sanitizedGameMetadataUrl(input: String?): String? {
    val value = input?.takeIf { it.length in 1..4096 && it.none(Char::isISOControl) }?.trim() ?: return null
    // OkHttp leaves literal square brackets in path segments (ROM release tags).
    // Escape only the path, never the authority (including IPv6) or query.
    val authorityStart = value.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
    val normalized = if (authorityEnd >= 0 && value[authorityEnd] == '/') {
        val pathEnd = value.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) value.length else it }
        value.substring(0, authorityEnd) +
            value.substring(authorityEnd, pathEnd).replace("[", "%5B").replace("]", "%5D") +
            value.substring(pathEnd)
    } else value
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "https" || it == "http" } ?: return null
    // A URL containing HTTP user-info is an account URL, not a public source/artwork identity.
    if (uri.rawUserInfo != null || uri.host.isNullOrBlank() || uri.port !in -1..65535 || uri.port == 0) return null
    val query = uri.rawQuery?.split('&', ';')?.filter { part ->
        part.isNotEmpty() && !gameMetadataCredentialParameter(part.substringBefore('='))
    }?.joinToString("&")?.takeIf(String::isNotEmpty)
    return buildString {
        append(scheme); append("://"); append(uri.rawAuthority)
        append(uri.rawPath.orEmpty())
        if (query != null) { append('?'); append(query) }
        // Fragments can contain browser tokens and are never needed for these references.
    }
}

/** Decoding also covers case/percent-encoded keys received from imported backups. */
internal fun gameMetadataCredentialParameter(rawName: String): Boolean {
    var name = rawName
    for (attempt in 0 until 3) {
        val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrNull() ?: return true
        if (decoded == name) break
        name = decoded
    }
    if (name.any(Char::isISOControl)) return true
    val normalized = name.lowercase(Locale.ROOT).filter { it != '_' && it != '-' && it != '.' }
    return normalized in GAME_METADATA_CREDENTIAL_PARAMETERS
}

private val GAME_METADATA_CREDENTIAL_PARAMETERS = setOf("devid", "devpassword", "softname", "ssid", "sspassword", "apikey", "xapikey",
    "token", "accesstoken", "refreshtoken", "authtoken", "authorization", "auth", "key", "secret",
    "clientsecret", "password", "passwd", "pwd", "username", "user", "login")

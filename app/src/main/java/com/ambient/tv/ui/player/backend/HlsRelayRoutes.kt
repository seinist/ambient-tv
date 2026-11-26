package com.ambient.tv.ui.player.backend

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Locale

/** Recognize manifests without relying on filename case or the presence of signed query data. */
internal fun vlcHlsPath(url: HttpUrl): Boolean =
    url.encodedPath.lowercase(Locale.ROOT).let { it.endsWith(".m3u8") || it.endsWith(".m3u") }

internal fun vlcDashPath(url: HttpUrl): Boolean = url.encodedPath.endsWith(".mpd", ignoreCase = true)

internal fun vlcDashContentType(value: String?): Boolean =
    value?.substringBefore(';')?.trim()?.equals("application/dash+xml", true) == true

internal fun vlcHlsContentType(value: String?): Boolean = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) in
    setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl", "audio/mpegurl", "audio/x-mpegurl")

/** The playlist can only mint capabilities for resources it actually references. */
internal class HlsRelayRoutes(
    private val origin: HttpUrl,
    private val rootPath: String,
    private val capacity: Int = 8192,
) {
    internal data class Resource(
        val url: HttpUrl,
        val playlist: Boolean = false,
        val credentialed: Boolean = true,
        val variables: Map<String, String> = emptyMap(),
    )
    private data class TemplateResource(val template: DashRelayTemplate, val credentialed: Boolean)
    private var root = Resource(origin, vlcHlsPath(origin) || vlcDashPath(origin))
    private var nextId = 0L
    private val entries = LinkedHashMap<Resource, String>(16, 0.75f, true)
    private val paths = HashMap<String, Resource>()
    private val templates = LinkedHashMap<TemplateResource, String>(16, 0.75f, true)
    private val templatePaths = HashMap<String, TemplateResource>()

    /** Only the configured server receives the stream headers. No arbitrary localhost proxy. */
    @Synchronized fun register(url: HttpUrl, playlist: Boolean, parentCredentialed: Boolean = true,
        variables: Map<String, String> = emptyMap()): String {
        val credentialed = parentCredentialed && vlcRelayOriginAllowed(origin, url)
        if (!vlcRelayResourceAllowed(origin, url) || !credentialed && !vlcRelayCdnUrlAllowed(url))
            throw IOException("Foreign or credential-bearing manifest resource refused")
        val isManifest = playlist || vlcHlsPath(url) || vlcDashPath(url)
        val key = Resource(url, isManifest, credentialed, if (isManifest) variables.toMap() else emptyMap())
        entries[key]?.let { return it }
        if (nextId == Long.MAX_VALUE) throw IOException("Manifest capability exhausted")
        val path = "$rootPath/${++nextId}" + when {
            vlcDashPath(url) -> ".mpd"
            key.playlist -> ".m3u8"
            else -> ""
        }
        entries[key] = path
        paths[path] = key
        while (entries.size > capacity) {
            val first = entries.entries.iterator().next()
            paths.remove(first.value)
            entries.remove(first.key)
        }
        return path
    }

    /** A template capability authorizes only the numeric substitutions declared in the MPD. */
    @Synchronized fun registerTemplate(template: DashRelayTemplate, parentCredentialed: Boolean = true): String {
        val sample = template.resolve(List(template.slots.size) { "0" }) ?: throw IOException("Invalid DASH template")
        val credentialed = parentCredentialed && vlcRelayOriginAllowed(origin, sample)
        if (!vlcRelayResourceAllowed(origin, sample) || !credentialed && !vlcRelayCdnUrlAllowed(sample))
            throw IOException("Foreign DASH template refused")
        if (template.slots.isEmpty()) return register(sample, false, parentCredentialed)
        val key = TemplateResource(template, credentialed)
        val prefix = templates[key] ?: run {
            if (nextId == Long.MAX_VALUE) throw IOException("Manifest capability exhausted")
            val path = "$rootPath/t${++nextId}"
            templates[key] = path
            templatePaths[path] = key
            while (templates.size > capacity) {
                val first = templates.entries.iterator().next()
                templatePaths.remove(first.value)
                templates.remove(first.key)
            }
            path
        }
        return prefix + "/" + template.suffix()
    }

    /** Root content type may only become known after a successful HTTP response. */
    @Synchronized fun markRootPlaylist() { root = root.copy(playlist = true) }

    @Synchronized fun resolve(target: String): Resource? {
        val path = target.substringBefore('?')
        val resource = if (path == rootPath) root else paths[path] ?: run {
            if ('?' in target || !path.startsWith("$rootPath/t")) return null
            val tail = path.removePrefix("$rootPath/").split('/')
            if (tail.size < 2) return null
            val key = templatePaths["$rootPath/${tail.first()}"] ?: return null
            templates[key]
            val url = key.template.resolve(tail.drop(1)) ?: return null
            if (!vlcRelayResourceAllowed(origin, url) || !key.credentialed && !vlcRelayCdnUrlAllowed(url)) return null
            return Resource(url, credentialed = key.credentialed)
        }
        if (path != rootPath) entries[resource] // LRU on actual segment access
        if ('?' !in target) return resource
        if (!resource.playlist) return null
        // LL-HLS adds these three well-defined delivery directives to an existing playlist URI.
        // Never allow a caller to change arbitrary signed query parameters or the upstream path.
        val requested = ("http://127.0.0.1" + target).toHttpUrlOrNull() ?: return null
        if (requested.querySize !in 1..3) return null
        val names = mutableSetOf<String>()
        val builder = resource.url.newBuilder()
        for (i in 0 until requested.querySize) {
            val name = requested.queryParameterName(i)
            val value = requested.queryParameterValue(i) ?: return null
            if (!names.add(name)) return null
            val allowed = when (name) {
                "_HLS_msn", "_HLS_part" -> value.length in 1..18 && value.all { it in '0'..'9' }
                "_HLS_skip" -> value == "YES" || value == "v2"
                else -> false
            }
            if (!allowed) return null
            builder.setQueryParameter(name, value)
        }
        return resource.copy(url = builder.build())
    }
}

/** Same origin or the canonical HTTP→HTTPS upgrade, never a different service port. */
internal fun vlcRelayOriginAllowed(origin: HttpUrl, hop: HttpUrl): Boolean =
    origin.host == hop.host && (
        origin.scheme == hop.scheme && origin.port == hop.port ||
        origin.scheme == "http" && origin.port == 80 && hop.scheme == "https" && hop.port == 443
    )

private val hlsUriAttribute = Regex("""([:,]\s*URI=")([^"]+)(")""")

/**
 * Rewrite ordinary HLS variant, rendition, key, initialization and media references.
 * Unsupported variable substitution/content steering is refused instead of forwarding an
 * unresolved URL or leaking credentials to an unrelated server.
 */
internal fun rewriteVlcHlsManifest(
    text: String,
    base: HttpUrl,
    localUri: (HttpUrl, Boolean) -> String,
): String {
    val lines = text.removePrefix("\uFEFF").lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "#EXTM3U") throw IOException("Invalid HLS playlist")
    var nextPlaylist = false
    var references = 0
    fun map(raw: String, playlist: Boolean): String {
        if (++references > 4096 || raw.contains("{\$") || raw.isBlank())
            throw IOException("Unsupported HLS reference")
        val resolved = base.resolve(raw) ?: throw IOException("Invalid HLS URI")
        return localUri(resolved, playlist)
    }
    return buildString {
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-DEFINE:") || trimmed.startsWith("#EXT-X-CONTENT-STEERING:"))
                throw IOException("Unsupported HLS playlist feature")
            when {
                trimmed.startsWith("#") -> {
                    val playlist = trimmed.startsWith("#EXT-X-MEDIA:") ||
                        trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                        trimmed.startsWith("#EXT-X-RENDITION-REPORT:")
                    append(hlsUriAttribute.replace(line) { m ->
                        m.groupValues[1] + map(m.groupValues[2], playlist) + m.groupValues[3]
                    })
                    if (trimmed.startsWith("#EXT-X-STREAM-INF:")) nextPlaylist = true
                }
                trimmed.isNotEmpty() -> {
                    append(map(trimmed, nextPlaylist))
                    nextPlaylist = false
                }
                else -> append(line)
            }
            append('\n')
        }
    }
}

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

/**
 * HLS attribute values can contain commas and literal URI= text inside quoted strings.
 * Scan actual attribute boundaries rather than matching URI= anywhere in the line.
 */
private fun rewriteHlsUriAttributes(line: String, allowUri: Boolean, rewrite: (String) -> String): String {
    val colon = line.indexOf(':')
    if (colon < 0) return line
    var index = colon + 1
    var quoted = false
    var start = index
    var copied = 0
    var seenUri = false
    val output = StringBuilder(line.length)
    fun attribute(end: Int) {
        val raw = line.substring(start, end)
        val eq = raw.indexOf('=')
        if (eq < 0) return
        val name = raw.substring(0, eq).trim()
        // Interstitials and vendor extensions can contain independent URL references.
        // Passing them through would let the native demuxer bypass relay capabilities.
        if (name != "URI") {
            if (name.endsWith("-URI") || name.endsWith("-URL") || name == "URL" || name == "X-ASSET-LIST")
                throw IOException("Unsupported HLS external resource attribute")
            return
        }
        if (!allowUri) throw IOException("Unsupported HLS URI-bearing tag")
        if (seenUri) throw IOException("Duplicate HLS URI attribute")
        seenUri = true
        val value = raw.substring(eq + 1).trim()
        if (value.length < 3 || value.first() != '"' || value.last() != '"' ||
            '"' in value.substring(1, value.lastIndex))
            throw IOException("Invalid HLS URI attribute")
        val begin = start + eq + 1 + raw.substring(eq + 1).indexOf('"') + 1
        val finish = start + raw.lastIndexOf('"')
        output.append(line, copied, begin)
        output.append(rewrite(value.substring(1, value.lastIndex)))
        copied = finish
    }
    while (index < line.length) {
        when (line[index]) {
            '"' -> quoted = !quoted
            ',' -> if (!quoted) { attribute(index); start = index + 1 }
        }
        index++
    }
    if (quoted) throw IOException("Unterminated HLS attribute")
    attribute(line.length)
    return if (!seenUri) line else output.append(line, copied, line.length).toString()
}

private val hlsUriTags = setOf(
    "#EXT-X-KEY", "#EXT-X-SESSION-KEY", "#EXT-X-MAP", "#EXT-X-MEDIA",
    "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-IMAGE-STREAM-INF", "#EXT-X-SESSION-DATA",
    "#EXT-X-PART", "#EXT-X-PRELOAD-HINT", "#EXT-X-RENDITION-REPORT",
)

private val hlsVariable = Regex("""\{\$([A-Za-z0-9_-]{1,64})\}""")
private val hlsVariableName = Regex("[A-Za-z0-9_-]{1,64}")
private val hlsDefineAttribute = Regex("([A-Z0-9-]+)=\"([^\"]*)\"")

private fun hlsDefineAttributes(line: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < line.length) {
        val match = hlsDefineAttribute.matchAt(line, index) ?: throw IOException("Invalid HLS definition")
        if (result.put(match.groupValues[1], match.groupValues[2]) != null)
            throw IOException("Duplicate HLS definition attribute")
        index = match.range.last + 1
        if (index == line.length) break
        if (line[index++] != ',' || index == line.length) throw IOException("Invalid HLS definition separator")
    }
    return result
}

/** Compatibility overload for callers that do not need inherited playlist variables. */
internal fun rewriteVlcHlsManifest(
    text: String,
    base: HttpUrl,
    localUri: (HttpUrl, Boolean) -> String,
): String = rewriteVlcHlsManifest(text, base, emptyMap()) { url, playlist, _ -> localUri(url, playlist) }

/**
 * Rewrite variants, renditions, keys, initialization and media references. Variables are
 * expanded before URI validation; imports are scoped to the parent playlist that minted
 * the capability, not global state. Content steering still requires a separate protocol.
 */
internal fun rewriteVlcHlsManifest(
    text: String,
    base: HttpUrl,
    inherited: Map<String, String>,
    localUri: (HttpUrl, Boolean, Map<String, String>) -> String,
): String {
    val lines = text.removePrefix("\uFEFF").lineSequence()
    val iterator = lines.iterator()
    if (!iterator.hasNext() || iterator.next().trim() != "#EXTM3U") throw IOException("Invalid HLS playlist")
    var nextPlaylist = false
    var references = 0
    var variables: Map<String, String> = emptyMap()
    fun expand(raw: String): String {
        if (raw.length > 16_384) throw IOException("HLS line too large")
        val result = buildString {
            var offset = 0
            for (match in hlsVariable.findAll(raw)) {
                val value = variables[match.groupValues[1]] ?: throw IOException("Undefined HLS variable")
                if (length + match.range.first - offset + value.length > 16_384)
                    throw IOException("HLS variable expansion too large")
                append(raw, offset, match.range.first)
                append(value)
                offset = match.range.last + 1
            }
            if (length + raw.length - offset > 16_384) throw IOException("HLS variable expansion too large")
            append(raw, offset, raw.length)
        }
        if (result.contains("{\$")) throw IOException("Unsupported HLS variable expansion")
        return result
    }
    fun define(raw: String) {
        val attrs = hlsDefineAttributes(raw)
        val (name, value) = when (attrs.keys) {
            setOf("NAME", "VALUE") -> attrs.getValue("NAME") to expand(attrs.getValue("VALUE"))
            setOf("IMPORT") -> attrs.getValue("IMPORT").let { it to (inherited[it] ?: throw IOException("Unknown HLS import")) }
            setOf("QUERYPARAM") -> attrs.getValue("QUERYPARAM").let { it to (base.queryParameter(it) ?: throw IOException("Missing HLS query variable")) }
            else -> throw IOException("Unsupported HLS definition")
        }
        if (!hlsVariableName.matches(name) || name in variables || variables.size >= 32 ||
            value.length > 2048 || value.any { it == '"' || it == '\r' || it == '\n' || it.code < 32 } || value.contains("{\$"))
            throw IOException("Invalid HLS variable")
        variables = variables + (name to value)
    }
    fun map(raw: String, playlist: Boolean): String {
        if (++references > 4096 || raw.isBlank()) throw IOException("Unsupported HLS reference")
        val resolved = base.resolve(raw) ?: throw IOException("Invalid HLS URI")
        return localUri(resolved, playlist, if (playlist) variables else emptyMap())
    }
    return buildString {
        append("#EXTM3U\n")
        while (iterator.hasNext()) {
            val original = iterator.next()
            if (original.length > 16_384) throw IOException("HLS line too large")
            val originalTrimmed = original.trim()
            if (originalTrimmed.startsWith("#EXT-X-DEFINE:")) {
                define(originalTrimmed.substringAfter(':'))
                continue
            }
            if (originalTrimmed.startsWith("#EXT-X-CONTENT-STEERING:"))
                throw IOException("Unsupported HLS content steering")
            val line = expand(original)
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#") -> {
                    val playlist = trimmed.startsWith("#EXT-X-MEDIA:") ||
                        trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                        trimmed.startsWith("#EXT-X-RENDITION-REPORT:") ||
                        trimmed.startsWith("#EXT-X-IMAGE-STREAM-INF:")
                    // EXTINF titles and ordinary comments are not attribute lists.
                    val tag = trimmed.substringBefore(':')
                    // EXT-X tags with attribute lists may introduce new external-resource
                    // fields. Scan their actual boundaries, never EXTINF or free-form comments.
                    append(if (tag.startsWith("#EXT-X-") && ':' in trimmed)
                        rewriteHlsUriAttributes(line, tag in hlsUriTags) { map(it, playlist) } else line)
                    if (trimmed.startsWith("#EXT-X-STREAM-INF:")) nextPlaylist = true
                }
                trimmed.isNotEmpty() -> {
                    append(map(trimmed, nextPlaylist))
                    nextPlaylist = false
                }
                else -> append(line)
            }
            append('\n')
            // Bound the aggregate rewritten manifest as well as each expanded line.
            if (length > 4 * 1024 * 1024) throw IOException("Rewritten HLS manifest too large")
        }
    }
}

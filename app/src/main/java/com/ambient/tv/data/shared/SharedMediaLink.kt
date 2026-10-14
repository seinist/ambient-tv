package com.ambient.tv.data.shared

import java.net.URI
import java.util.Locale

/** An explicitly shared HTTP media resource, not a website extractor or a local-file grant. */
@ConsistentCopyVisibility
data class SharedMediaLink private constructor(
    val url: String,
    val host: String,
    val extension: String,
    private val audioHint: Boolean = false,
    /** Title sent along (DLNA metadata); never derived from the URL. */
    private val label: String? = null,
) {
    val audioOnly: Boolean get() = audioHint || extension in audioExtensions
    // Do not put signed query parameters, credentials or full URLs in labels/history/routes.
    val title: String get() = label ?: listOf(host, extension.uppercase(Locale.ROOT)).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "weba")
        private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "ogv", "m3u8", "mpd")

        private val mimeExtensions = mapOf(
            "video/mp4" to "mp4", "video/x-m4v" to "m4v", "video/x-matroska" to "mkv", "video/webm" to "webm",
            "video/quicktime" to "mov", "video/x-msvideo" to "avi", "video/avi" to "avi", "video/mp2t" to "ts",
            "video/vnd.dlna.mpeg-tts" to "ts", "video/mpeg" to "mpg", "video/ogg" to "ogv",
            "application/x-mpegurl" to "m3u8", "application/vnd.apple.mpegurl" to "m3u8", "audio/mpegurl" to "m3u8",
            "audio/x-mpegurl" to "m3u8", "application/dash+xml" to "mpd",
            "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/aac" to "aac",
            "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/ogg" to "ogg", "audio/opus" to "opus",
            "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/webm" to "weba",
        )

        fun parse(value: String): SharedMediaLink? = runCatching {
            val (uri, host) = validatedUri(value)
            val extension = uri.path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            require(extension in audioExtensions || extension in videoExtensions)
            SharedMediaLink(value, host, extension)
        }.getOrNull()

        /**
         * A URL a DLNA control point handed to the renderer. Such URLs (proxies, transcoders)
         * often have no file extension, so the type also comes from the DIDL MIME type or UPnP
         * class. Images and other non-media items are refused.
         */
        fun parseRendered(value: String, mimeType: String?, upnpClass: String?, title: String?): SharedMediaLink? = runCatching {
            val (uri, host) = validatedUri(value)
            val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val itemClass = upnpClass?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val pathExtension = uri.path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            val extension = pathExtension.takeIf { it in audioExtensions || it in videoExtensions } ?: mimeExtensions[mime].orEmpty()
            val audio = extension in audioExtensions || mime.startsWith("audio/") || itemClass.startsWith("object.item.audioitem")
            val video = extension in videoExtensions || mime.startsWith("video/") || itemClass.startsWith("object.item.videoitem")
            require(audio || video)
            require(!mime.startsWith("image/") && !itemClass.startsWith("object.item.imageitem"))
            val label = title?.filterNot { it.isISOControl() }?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
            SharedMediaLink(value, host, extension, audioHint = audio && !video, label = label)
        }.getOrNull()

        private fun validatedUri(value: String): Pair<URI, String> {
            require(value.length in 1..4096 && value.none { it.isWhitespace() || it.isISOControl() })
            val uri = URI(value)
            require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https"))
            require(uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535))
            val host = requireNotNull(uri.host).lowercase(Locale.ROOT).trimEnd('.')
            require(host.isNotEmpty() && host != "localhost" && !host.endsWith(".localhost"))
            // Explicit LAN media is supported; loopback/link-local/multicast literals are not.
            // This is URL validation, not a network sandbox (DNS and redirects belong to the player).
            if (':' in host) {
                val address = java.net.InetAddress.getByName(host)
                require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress)
            } else if (host.all { it.isDigit() || it == '.' }) {
                val parts = host.split('.')
                require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && (it == "0" || !it.startsWith('0')) })
                val octets = parts.map(String::toInt)
                require(octets[0] !in setOf(0, 127) && octets[0] < 224 && !(octets[0] == 169 && octets[1] == 254))
            } else {
                require(host.contains('.') || host.matches(Regex("[a-z][a-z0-9-]*")))
                require(!host.startsWith("0x"))
            }
            require(uri.path.none { it.isISOControl() || it == '\\' })
            return uri to host
        }
    }
}

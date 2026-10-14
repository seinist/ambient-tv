package com.ambient.tv.data.files

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element

/**
 * Read-only WebDAV (RFC 4918): PROPFIND for listings, ranged GET for playback, so every seek is
 * one request and reads may run in parallel. Basic or Digest auth (Digest preferred); plain HTTP
 * with Basic sends the password readable, which the editor points out. A certificate the system does not trust (self-signed NAS) is
 * accepted only after its fingerprint was confirmed in the editor; there is no trust-all mode.
 */
class WebDavAccess(private val base: OkHttpClient) {
    private val clients = ConcurrentHashMap<String, Pair<String, OkHttpClient>>()

    private fun client(source: FileSource): OkHttpClient {
        val config = listOf(source.secure, source.serverKey).joinToString("|")
        clients[source.id]?.takeIf { it.first == config }?.let { return it.second }
        val builder = base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false)
            .retryOnConnectionFailure(true)
        if (source.secure) {
            val system = systemTrustManager()
            val trust = PinningTrustManager(system, source.serverKey)
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            builder.sslSocketFactory(tls.socketFactory, trust)
                // A confirmed self-signed certificate usually names no IP/host; its fingerprint is the identity.
                .hostnameVerifier { host, session ->
                    defaultVerifier.verify(host, session) || source.serverKey.isNotEmpty() &&
                        runCatching { sha256Fingerprint(session.peerCertificates.first().encoded) == source.serverKey }.getOrDefault(false)
                }
        }
        return builder.build().also { clients[source.id] = config to it }
    }

    fun list(source: FileSource, relative: String, password: String, limit: Int, accept: (FileEntry) -> Boolean = FileEntry::playable): FileListing {
        val dir = FilePaths.relative(relative)
        val url = url(source, dir, directory = true)
        val body = propfind(source, url, password, depth = 1)
        val entries = mutableListOf<FileEntry>(); var more = false
        val items = parseMultistatus(body)
        val names = webDavChildNames(source, dir, items.map { it.href })
        for ((index, item) in items.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val name = names[index] ?: continue
            val entry = FileEntry(source.id, FilePaths.child(dir, name), name, item.directory, item.size, item.modified,
                item.contentType.takeIf { it.isNotBlank() && !item.directory } ?: FilePaths.mime(name))
            if (!entry.directory && !accept(entry)) continue
            if (entries.size >= limit) { more = true; break }
            entries += entry
        }
        return FileListing(entries, more)
    }

    fun stat(source: FileSource, relative: String, password: String): FileEntry {
        val path = FilePaths.relative(relative)
        val item = parseMultistatus(propfind(source, url(source, path, directory = false), password, depth = 0)).firstOrNull()
            ?: throw java.io.FileNotFoundException(path)
        return FileEntry(source.id, path, path.substringAfterLast('/').ifEmpty { source.name }, item.directory, item.size, item.modified, FilePaths.mime(path))
    }

    fun open(source: FileSource, relative: String, password: String): SeekableMedia {
        val path = FilePaths.relative(relative)
        val entry = stat(source, path, password)
        if (entry.directory) throw java.io.FileNotFoundException(path)
        val url = url(source, path, directory = false)
        return object : SeekableMedia {
            override val size = entry.size
            override val parallelReads = true
            override fun read(position: Long, buffer: ByteArray, count: Int): Int {
                if (count <= 0) return 0
                if (position >= size) return -1
                val end = minOf(position + count, size) - 1
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").build()
                execute(source, password, request).use { response ->
                    checkStatus(response)
                    val stream = response.body.byteStream()
                    // A server ignoring Range answers 200 with the whole file: skip to the position.
                    if (response.code == 200 && position > 0) {
                        var skipped = 0L
                        while (skipped < position) { val n = stream.skip(position - skipped); if (n <= 0) return -1; skipped += n }
                    }
                    var total = 0
                    val wanted = (end - position + 1).toInt()
                    while (total < wanted) {
                        val n = stream.read(buffer, total, wanted - total)
                        if (n < 0) break
                        total += n
                    }
                    return if (total == 0) -1 else total
                }
            }
            override fun close() = Unit
        }
    }

    /** Connects and lists the start folder; used by the editor's "Test". */
    fun test(source: FileSource, password: String) { list(source, "", password, 1) }

    private fun propfind(source: FileSource, url: HttpUrl, password: String, depth: Int): String {
        val request = Request.Builder().url(url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", depth.toString())
            .build()
        execute(source, password, request).use { response ->
            checkStatus(response)
            val length = response.body.contentLength()
            if (length > MAX_LISTING_BYTES) throw IOException("Listing too large")
            val bytes = response.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk); if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_LISTING_BYTES) throw IOException("Listing too large")
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun checkStatus(response: Response) {
        when {
            response.code == 401 || response.code == 403 -> throw SecurityException("HTTP ${response.code}")
            response.code == 404 -> throw java.io.FileNotFoundException("HTTP 404")
            !response.isSuccessful -> throw IOException("HTTP ${response.code}")
        }
    }

    /**
     * Credentials are only sent once the server asked for them: the first request goes without, a
     * 401 names the scheme (Digest preferred over Basic, so a Digest server never sees the password),
     * and later requests of the source authenticate up front with the remembered scheme.
     */
    private val authSchemes = ConcurrentHashMap<String, Pair<String, WebDavAuth>>()

    private fun execute(source: FileSource, password: String, request: Request): Response {
        val accountKey = listOf(source.host, source.port, source.secure, source.username).joinToString("|")
        val known = authSchemes[source.id]?.takeIf { it.first == accountKey }?.second
        val first = client(source).newCall(authorize(request, source, password, known)).execute()
        if (first.code != 401 || source.username.isBlank()) return first
        val challenge = chooseAuthChallenge(first.headers("WWW-Authenticate"))
        first.close()
        if (challenge == null) throw SecurityException("HTTP 401")
        // A stale Digest nonce or a first contact: retry exactly once with the server's challenge.
        authSchemes[source.id] = accountKey to challenge
        return client(source).newCall(authorize(request, source, password, challenge)).execute()
    }

    private fun authorize(request: Request, source: FileSource, password: String, auth: WebDavAuth?): Request {
        if (auth == null || source.username.isBlank()) return request
        val header = when (auth) {
            is WebDavAuth.Basic -> Credentials.basic(source.username, password, Charsets.UTF_8)
            is WebDavAuth.Digest -> digestAuthorization(auth, source.username, password, request.method,
                request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""),
                cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16), nc = auth.nextCount())
        }
        return request.newBuilder().header("Authorization", header).build()
    }

    private fun url(source: FileSource, relative: String, directory: Boolean): HttpUrl = webDavUrl(source, relative, directory)


    private class PinningTrustManager(private val system: X509TrustManager, private val pinned: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                val leaf = chain.firstOrNull() ?: throw error
                val fingerprint = sha256Fingerprint(leaf.encoded)
                when {
                    // The confirmed fingerprint is the identity; NAS self-signed certificates often expire unnoticed.
                    pinned == fingerprint -> Unit
                    pinned.isEmpty() -> throw CertificateException(UnverifiedServerKeyException(fingerprint, certificate = true))
                    else -> throw CertificateException(ServerKeyChangedException(fingerprint, certificate = true))
                }
            }
        }
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private companion object {
        const val MAX_LISTING_BYTES = 8L * 1024 * 1024
        val XML = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/></d:prop></d:propfind>"""
    }
}

/** A remembered WWW-Authenticate scheme of a WebDAV server. */
internal sealed interface WebDavAuth {
    object Basic : WebDavAuth
    class Digest(val realm: String, val nonce: String, val opaque: String?, val qopAuth: Boolean, val algorithm: String) : WebDavAuth {
        private val count = java.util.concurrent.atomic.AtomicInteger(0)
        fun nextCount(): Int = count.incrementAndGet()
    }
}

/** Digest (MD5 or SHA-256, qop=auth) is preferred over Basic; anything else is unsupported (null). */
internal fun chooseAuthChallenge(headers: List<String>): WebDavAuth? {
    val challenges = headers.flatMap(::splitChallenges)
    val digests = challenges.filter { it.first.equals("Digest", true) }.mapNotNull { (_, params) ->
        val realm = params["realm"] ?: return@mapNotNull null
        val nonce = params["nonce"] ?: return@mapNotNull null
        val algorithm = (params["algorithm"] ?: "MD5").uppercase()
        if (algorithm !in setOf("MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS")) return@mapNotNull null
        val qop = params["qop"]?.split(',')?.map { it.trim().lowercase() }
        // qop=auth-int alone would need a body hash; not offered by WebDAV servers in practice.
        if (qop != null && "auth" !in qop) return@mapNotNull null
        WebDavAuth.Digest(realm, nonce, params["opaque"], qop != null, algorithm)
    }
    return digests.firstOrNull { it.algorithm.startsWith("SHA-256") } ?: digests.firstOrNull()
        ?: if (challenges.any { it.first.equals("Basic", true) }) WebDavAuth.Basic else null
}

/** Splits one header into its challenges ("Digest a=1, b=2, Basic realm=x" is two). */
private fun splitChallenges(header: String): List<Pair<String, Map<String, String>>> {
    val result = mutableListOf<Pair<String, MutableMap<String, String>>>()
    val token = Regex("""\s*(?:,\s*)?([A-Za-z][A-Za-z0-9!#$%&'*+.^_`|~-]*)(?:\s*=\s*("(?:[^"\\]|\\.)*"|[^,\s]*))?""")
    var index = 0
    while (index < header.length) {
        val match = token.find(header, index)?.takeIf { it.range.first == index } ?: break
        if (match.value.isEmpty()) break
        index = match.range.last + 1
        val name = match.groupValues[1]; val raw = match.groupValues[2]
        if (match.groups[2] == null) {
            result += name to mutableMapOf() // a new scheme
        } else {
            val value = if (raw.startsWith('"')) raw.substring(1, raw.length - 1).replace(Regex("""\\(.)"""), "$1") else raw
            result.lastOrNull()?.second?.put(name.lowercase(), value)
        }
    }
    return result
}

/** RFC 7616 Authorization header value; [cnonce] and [nc] are passed in so it can be tested. */
internal fun digestAuthorization(auth: WebDavAuth.Digest, username: String, password: String, method: String, uri: String, cnonce: String, nc: Int): String {
    val sha = auth.algorithm.startsWith("SHA-256")
    fun h(value: String): String = java.security.MessageDigest.getInstance(if (sha) "SHA-256" else "MD5")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val count = "%08x".format(nc)
    var ha1 = h("$username:${auth.realm}:$password")
    if (auth.algorithm.endsWith("-SESS")) ha1 = h("$ha1:${auth.nonce}:$cnonce")
    val ha2 = h("$method:$uri")
    val response = if (auth.qopAuth) h("$ha1:${auth.nonce}:$count:$cnonce:auth:$ha2") else h("$ha1:${auth.nonce}:$ha2")
    fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return buildString {
        append("Digest username=").append(quote(username)).append(", realm=").append(quote(auth.realm))
        append(", nonce=").append(quote(auth.nonce)).append(", uri=").append(quote(uri))
        append(", algorithm=").append(auth.algorithm)
        append(", response=").append(quote(response))
        auth.opaque?.let { append(", opaque=").append(quote(it)) }
        if (auth.qopAuth) append(", qop=auth, nc=").append(count).append(", cnonce=").append(quote(cnonce))
    }
}

internal data class WebDavItem(val href: String, val directory: Boolean, val size: Long, val modified: Long, val contentType: String)

/** Builds the URL of [relative] below the source's base path; every segment is percent-encoded. */
internal fun webDavUrl(source: FileSource, relative: String, directory: Boolean): HttpUrl {
    val segments = (FilePaths.relative(source.share) + "/" + FilePaths.relative(relative)).split('/').filter(String::isNotEmpty)
    val builder = HttpUrl.Builder().scheme(if (source.secure) "https" else "http").host(source.host).port(source.port)
    segments.forEach(builder::addPathSegment)
    // Collections end in "/": avoids a redirect round trip on most servers.
    if (directory) builder.addPathSegment("")
    return builder.build()
}

/**
 * Child names of a Depth-1 listing, by index (null = the folder itself or not a direct child). Hrefs
 * normally carry the requested path; a reverse proxy that rewrites the prefix reports another one,
 * then the folder is the entry every other href starts with.
 */
internal fun webDavChildNames(source: FileSource, dir: String, hrefs: List<String>): List<String?> {
    val direct = hrefs.map { webDavChildName(source, dir, it) }
    if (direct.any { it != null } || hrefs.size < 2) return direct
    val segments = hrefs.map(::hrefSegments)
    val self = segments.filterNotNull().minByOrNull { it.size } ?: return direct
    if (segments.count { it == self } != 1) return direct
    return segments.map { item ->
        if (item == null || item.size != self.size + 1 || item.subList(0, self.size) != self) null
        else item.last().takeUnless { name -> name == "." || name == ".." || name.any { it.code < 32 || it == '\\' } }
    }
}

private fun hrefSegments(href: String): List<String>? =
    (runCatching { URI(href).path }.getOrNull() ?: runCatching { URI(null, null, href, null).path }.getOrNull())
        ?.split('/')?.filter(String::isNotEmpty)

/** Name of [href] when it is a direct child of folder [dir]; null for the folder itself or anything else. */
internal fun webDavChildName(source: FileSource, dir: String, href: String): String? {
    val path = runCatching { URI(href).path }.getOrNull() ?: runCatching { URI(null, null, href, null).path }.getOrNull() ?: return null
    val itemSegments = path.split('/').filter(String::isNotEmpty)
    val dirSegments = (FilePaths.relative(source.share) + "/" + dir).split('/').filter(String::isNotEmpty)
    if (itemSegments.size != dirSegments.size + 1) return null
    if (itemSegments.subList(0, dirSegments.size) != dirSegments) return null
    val name = itemSegments.last()
    if (name == "." || name == ".." || name.any { it.code < 32 || it == '\\' }) return null
    return name
}

/** Parses a DAV: multistatus document; entity expansion and DOCTYPEs are refused (XXE). */
internal fun parseMultistatus(xml: String): List<WebDavItem> {
    if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) throw IOException("DOCTYPE not allowed")
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    }
    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    val responses = document.getElementsByTagNameNS("DAV:", "response")
    return (0 until responses.length).mapNotNull { index ->
        val response = responses.item(index) as Element
        val href = response.firstDav("href")?.textContent?.trim().orEmpty()
        if (href.isEmpty()) return@mapNotNull null
        // Only properties from a 2xx propstat count; servers report missing ones as 404 separately.
        val props = response.childrenDav("propstat").filter { stat ->
            stat.firstDav("status")?.textContent.orEmpty().let { it.isBlank() || Regex("""\s2\d\d\s""").containsMatchIn(" $it ") }
        }.mapNotNull { it.firstDav("prop") }
        val directory = props.any { prop -> prop.firstDav("resourcetype")?.firstDav("collection") != null }
        val size = props.firstNotNullOfOrNull { it.firstDav("getcontentlength")?.textContent?.trim()?.toLongOrNull() } ?: 0L
        val modified = props.firstNotNullOfOrNull { it.firstDav("getlastmodified")?.textContent?.trim() }
            ?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        val type = props.firstNotNullOfOrNull { it.firstDav("getcontenttype")?.textContent?.trim() }.orEmpty().substringBefore(';')
        WebDavItem(href, directory, size, modified, type)
    }
}

private fun Element.childrenDav(name: String): List<Element> {
    val nodes = getElementsByTagNameNS("DAV:", name)
    return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.parentNode === this }
}

private fun Element.firstDav(name: String): Element? = childrenDav(name).firstOrNull()

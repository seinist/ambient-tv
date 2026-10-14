package com.ambient.tv.data.dlna

import android.content.Context
import android.net.wifi.WifiManager
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.util.LocalNetworkAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URI
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Small control-point discovery implementation kept separate from media browsing. */
@Singleton
class DlnaDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    preferences: AppPreferences,
) {
    private val client = dlnaHttpClient(preferences).newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun discover(timeoutMs: Int = 2_500): List<DlnaServer> = withContext(Dispatchers.IO) {
        require(timeoutMs in 500..15_000)
        check(LocalNetworkAccess.isGranted(context)) { context.getString(R.string.local_network_missing) }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("ambient-tv-dlna")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }
        try {
            val locations = LinkedHashSet<String>()
            MulticastSocket().use { socket ->
                socket.timeToLive = 2
                socket.soTimeout = 250
                val request = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                        "USER-AGENT: Android UPnP/1.1 ${com.ambient.tv.branding.AppBrand.name}/${BuildConfig.VERSION_NAME}\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                val address = InetAddress.getByName(SSDP_HOST)
                repeat(2) { socket.send(DatagramPacket(request, request.size, address, SSDP_PORT)) }
                val until = System.currentTimeMillis() + timeoutMs
                val response = ByteArray(8 * 1024)
                while (System.currentTimeMillis() < until) {
                    try {
                        val packet = DatagramPacket(response, response.size)
                        socket.receive(packet)
                        val senderHost = packet.address?.hostAddress
                        parseSsdpLocation(
                            String(packet.data, 0, packet.length, Charsets.US_ASCII),
                            senderHost,
                        )?.let(locations::add)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Timeout is only a polling interval until the discovery deadline.
                    }
                }
            }
            coroutineScope {
                locations.take(MAX_DISCOVERY_RESPONSES)
                    .map { location -> async { readDescription(location) } }
                    .awaitAll()
                    .filterNotNull()
                    .distinctBy { it.udn }
            }
        } finally {
            if (lock?.isHeld == true) runCatching { lock.release() }
        }
    }

    private fun readDescription(location: String): DlnaServer? = runCatching {
        val request = Request.Builder().url(location).get().build()
        val xml = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "DLNA description failed (${response.code})" }
            response.limitedUtf8(MAX_DESCRIPTION_BYTES)
        }
        parseDlnaDeviceDescription(location, xml)
    }.getOrNull()

    private companion object {
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val MAX_DISCOVERY_RESPONSES = 24
        const val MAX_DESCRIPTION_BYTES = 1024 * 1024
    }
}

/** SSDP description URL. The LOCATION host must be the packet sender; a response must
 * not redirect discovery at a different host. Non-http(s) URLs are rejected. LAN-only mode
 * is enforced again when the description is fetched. */
internal fun parseSsdpLocation(value: String, senderHost: String?): String? {
    val lines = value.lineSequence().toList()
    if (!lines.firstOrNull().orEmpty().trim().startsWith("HTTP/1.1 200", ignoreCase = true)) return null
    val location = lines.drop(1)
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }
        .firstOrNull { it.first == "location" }
        ?.second
        ?: return null
    val uri = runCatching { URI(location) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val locationHost = uri.host ?: return null
    val sender = senderHost?.takeIf { it.isNotBlank() } ?: return null
    if (!ssdpHostsMatch(locationHost, sender)) return null
    return location
}

/** True when both values name the same host. Hostnames are not resolved. */
internal fun ssdpHostsMatch(locationHost: String, senderHost: String): Boolean {
    val left = canonicalSsdpHost(locationHost) ?: return false
    val right = canonicalSsdpHost(senderHost) ?: return false
    if (left.equals(right, ignoreCase = true)) return true
    val leftBytes = numericHostBytes(left) ?: return false
    val rightBytes = numericHostBytes(right) ?: return false
    return sameNumericHost(leftBytes, rightBytes)
}

private fun canonicalSsdpHost(value: String): String? {
    var host = value.trim()
    if (host.startsWith("[")) {
        val end = host.indexOf(']')
        if (end <= 1) return null
        host = host.substring(1, end)
    }
    host = host.substringBefore('%').trim()
    return host.takeIf { it.isNotEmpty() }
}

private fun numericHostBytes(host: String): ByteArray? {
    val ipv4 = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }
    val ipv6 = host.contains(':') && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    if (!ipv4 && !ipv6) return null
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    val literal = address.hostAddress?.substringBefore('%') ?: return null
    if (!ipv6 && !literal.equals(host, ignoreCase = true)) return null
    return address.address
}

private fun sameNumericHost(left: ByteArray, right: ByteArray): Boolean {
    if (left.contentEquals(right)) return true
    val (v4, v6) = when {
        left.size == 4 && right.size == 16 -> left to right
        left.size == 16 && right.size == 4 -> right to left
        else -> return false
    }
    if (v6.size != 16) return false
    for (index in 0 until 10) if (v6[index] != 0.toByte()) return false
    if (v6[10] != 0xff.toByte() || v6[11] != 0xff.toByte()) return false
    return v6.copyOfRange(12, 16).contentEquals(v4)
}

/** Parses the matching MediaServer device, including an embedded MediaServer in a root device. */
internal fun parseDlnaDeviceDescription(location: String, xml: String): DlnaServer? = runCatching {
    val root = parseXmlDocument(xml).documentElement
    val candidates = mutableListOf<DeviceBuilder>()
    root.directChild("device")?.collectMediaServers(depth = 1, candidates)
    val device = candidates.minByOrNull { it.depth } ?: return null
    val contentDirectory = device.contentDirectories.maxByOrNull { it.version() } ?: return null
    val base = root.directChildText("urlbase")?.takeIf { it.isNotBlank() } ?: location
    val control = contentDirectory.controlUrl?.takeIf { it.isNotBlank() }?.let { URI(base).resolve(it).toString() } ?: return null
    // URLBase/controlURL come from the device's own XML: they may only point back to the host that
    // answered at LOCATION, never redirect browsing (and LAN-only checks) to another machine.
    val locationHost = URI(location).host?.lowercase()?.trimEnd('.') ?: return null
    val controlUri = URI(control)
    if (controlUri.scheme?.lowercase() !in setOf("http", "https")) return null
    if (controlUri.host?.lowercase()?.trimEnd('.') != locationHost) return null
    val serverUdn = device.udn?.takeIf { it.isNotBlank() } ?: return null
    DlnaServer(
        id = UUID.nameUUIDFromBytes(serverUdn.toByteArray(Charsets.UTF_8)).toString(),
        udn = serverUdn,
        name = device.name?.takeIf { it.isNotBlank() } ?: URI(location).host.orEmpty(),
        descriptionUrl = location,
        contentDirectoryUrl = control,
        contentDirectoryType = contentDirectory.type ?: return null,
    ).validated()
}.getOrNull()

private data class DeviceBuilder(
    val depth: Int,
    val deviceType: String?,
    val name: String?,
    val udn: String?,
    val contentDirectories: List<ServiceBuilder>,
)

private data class ServiceBuilder(var type: String? = null, var controlUrl: String? = null) {
    fun isContentDirectory() = type?.matches(Regex("urn:schemas-upnp-org:service:ContentDirectory:[1-9][0-9]*", RegexOption.IGNORE_CASE)) == true
    fun version() = type?.substringAfterLast(':')?.toIntOrNull() ?: 0
}

private fun Element.collectMediaServers(depth: Int, candidates: MutableList<DeviceBuilder>) {
    val services = directChild("servicelist")?.childElements("service").orEmpty().map { service ->
        ServiceBuilder(
            type = service.directChildText("servicetype"),
            controlUrl = service.directChildText("controlurl"),
        )
    }.filter { it.isContentDirectory() }
    val device = DeviceBuilder(
        depth = depth,
        deviceType = directChildText("devicetype"),
        name = directChildText("friendlyname"),
        udn = directChildText("udn"),
        contentDirectories = services,
    )
    if (device.deviceType?.contains(":device:MediaServer:", ignoreCase = true) == true && services.isNotEmpty()) {
        candidates += device
    }
    directChild("devicelist")?.childElements("device").orEmpty().forEach { it.collectMediaServers(depth + 1, candidates) }
}

internal fun parseXmlDocument(value: String) = DocumentBuilderFactory.newInstance().run {
    require(!value.contains("<!DOCTYPE", ignoreCase = true)) { "DLNA XML document types are not supported" }
    isNamespaceAware = true
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    runCatching { isXIncludeAware = false }
    isExpandEntityReferences = false
    newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }.parse(InputSource(StringReader(value)))
}

internal fun Element.localTag(): String = (localName ?: tagName.substringAfter(':')).lowercase()

internal fun Element.childElements(name: String? = null): List<Element> = buildList {
    val children = childNodes
    for (index in 0 until children.length) {
        val element = children.item(index) as? Element ?: continue
        if (name == null || element.localTag() == name.lowercase()) add(element)
    }
}

internal fun Element.directChild(name: String): Element? = childElements(name).firstOrNull()

internal fun Element.directChildText(name: String): String? = directChild(name)?.textContent?.trim()

internal fun Element.firstDescendantText(name: String): String? {
    if (localTag() == name.lowercase()) return textContent?.trim()
    childElements().forEach { child -> child.firstDescendantText(name)?.let { return it } }
    return null
}

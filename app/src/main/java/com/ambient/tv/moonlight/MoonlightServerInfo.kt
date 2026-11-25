package com.ambient.tv.moonlight

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Unauthenticated discovery metadata only; not proof of pairing or trusted identity. */
data class MoonlightServerInfo(
    val name: String,
    val uniqueId: String,
    val appVersion: String,
    val httpsPort: Int,
    val state: String,
)

/** Parse a bounded response; never resolve DTDs, external entities or remote references. */
fun parseMoonlightServerInfo(xml: ByteArray): MoonlightServerInfo {
    if (xml.size > 64 * 1024) throw IOException("Moonlight serverinfo too large")
    val builder = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isExpandEntityReferences = false
        isXIncludeAware = false
    }.newDocumentBuilder()
    val root = try { builder.parse(ByteArrayInputStream(xml)).documentElement }
               catch (error: Exception) { throw IOException("Malformed Moonlight serverinfo", error) }
    if (root == null || root.tagName != "root" || root.getAttribute("status_code") != "200")
        throw IOException("Invalid Moonlight server response")
    fun field(name: String): String =
        root.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
            .take(128).filterNot { it.isISOControl() }
    val version = field("appversion")
    val id = field("uniqueid")
    if (version.isBlank() || id.isBlank()) throw IOException("Missing Moonlight server identity")
    return MoonlightServerInfo(
        name = field("hostname").ifBlank { id },
        uniqueId = id,
        appVersion = version,
        httpsPort = field("HttpsPort").toIntOrNull()?.takeIf { it in 1..65535 }
            ?: MoonlightHostAddress.DEFAULT_HTTPS_PORT,
        state = field("state"),
    )
}

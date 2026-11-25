package com.ambient.tv.moonlight

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import org.w3c.dom.Element

/** Metadata obtained only after HTTPS client authentication and host-certificate pinning. */
@Serializable
data class MoonlightApp(val id: Int, val title: String, val hdrSupported: Boolean)
data class MoonlightAppCatalog(val apps: List<MoonlightApp>)

/**
 * GameStream /applist XML -> bounded catalog. Does NOT authenticate the XML or its sender.
 * A caller must have established mutually authenticated TLS and pinned host identity.
 */
fun parseMoonlightAppList(xml: ByteArray): MoonlightAppCatalog {
    if (xml.size > MAX_APPLIST_BYTES) throw IOException("Moonlight app list too large")
    val input = xml.toString(Charsets.UTF_8)
    if (input.contains("<!DOCTYPE", ignoreCase = true) || input.contains("<!ENTITY", ignoreCase = true))
        throw IOException("XML document declarations not allowed")
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isExpandEntityReferences = false
        isXIncludeAware = false
    }
    val root = try { factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement }
               catch (error: Exception) { throw IOException("Malformed Moonlight app list", error) }
    if (root == null || root.tagName != "root" || root.getAttribute("status_code") != "200")
        throw IOException("Moonlight app list rejected")
    val children = root.childNodes
    val result = LinkedHashMap<Int, MoonlightApp>()
    var count = 0
    for (i in 0 until children.length) {
        val app = children.item(i) as? Element ?: continue
        if (app.tagName != "App") continue
        if (++count > MAX_APPS) throw IOException("Too many Moonlight apps")
        fun text(tag: String): String {
            val matches = app.getElementsByTagName(tag)
            if (matches.length != 1) return ""
            return matches.item(0)?.textContent?.trim().orEmpty()
        }
        val id = text("ID").toIntOrNull()?.takeIf { it > 0 } ?: continue
        val title = text("AppTitle")
        if (title.isBlank() || title.length > 240 || title.any { it.isISOControl() }) continue
        if (id !in result) result[id] = MoonlightApp(id, title, text("IsHdrSupported") == "1")
    }
    return MoonlightAppCatalog(result.values.toList())
}
private const val MAX_APPLIST_BYTES = 1024 * 1024
private const val MAX_APPS = 4096

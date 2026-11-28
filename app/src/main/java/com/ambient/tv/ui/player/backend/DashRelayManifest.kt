package com.ambient.tv.ui.player.backend

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val DASH_SLOT_PREFIX = "ambientdashslot"
private val dashIdentifier = Regex("[A-Za-z0-9_.-]{1,128}")
private val dashToken = Regex("""\$(RepresentationID|Bandwidth|Number|Time)(%0([1-9][0-9]?)d)?\$""")

internal data class DashRelaySlot(val kind: String, val format: String, val marker: String)

/** Only numeric substitutions are accepted from the local client. The path/host are fixed. */
internal data class DashRelayTemplate(val pattern: String, val slots: List<DashRelaySlot>) {
    fun suffix(): String = slots.joinToString("/") { "$" + it.kind + it.format + "$" }

    fun resolve(values: List<String>): HttpUrl? {
        if (values.size != slots.size) return null
        var url = pattern
        val seen = mutableMapOf<String, Long>()
        for ((i, slot) in slots.withIndex()) {
            val value = values[i]
            if (value.length !in 1..19 || !value.all { it in '0'..'9' }) return null
            val numeric = value.toLongOrNull() ?: return null
            if (seen.put(slot.kind, numeric)?.let { it != numeric } == true) return null
            val width = slot.format.removePrefix("%0").removeSuffix("d").toIntOrNull() ?: 0
            url = url.replace(slot.marker, numeric.toString().padStart(width, '0'))
        }
        return url.toHttpUrlOrNull()
    }
}

/** Resolve RepresentationID/Bandwidth now; leave only Number/Time as local capabilities. */
internal fun compileDashRelayTemplate(
    base: HttpUrl, raw: String, representationId: String, bandwidth: String,
): DashRelayTemplate {
    if (raw.isBlank() || raw.length > 4096 || DASH_SLOT_PREFIX in raw || DASH_SLOT_PREFIX in base.toString())
        throw IOException("Invalid DASH template")
    val slots = mutableListOf<DashRelaySlot>()
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        if (raw[i] != '$') {
            out.append(raw[i++])
            continue
        }
        if (raw.getOrNull(i + 1) == '$') {
            out.append('$')
            i += 2
            continue
        }
        val token = dashToken.matchAt(raw, i) ?: throw IOException("Unsupported DASH template token")
        val kind = token.groupValues[1]
        val format = token.groupValues[2]
        val width = token.groupValues[3].toIntOrNull() ?: 0
        if (width > 18) throw IOException("DASH template width too large")
        when (kind) {
            "RepresentationID" -> {
                if (width != 0 || !dashIdentifier.matches(representationId) || representationId in setOf(".", ".."))
                    throw IOException("Invalid DASH representation")
                out.append(representationId)
            }
            "Bandwidth" -> {
                val value = bandwidth.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid DASH bandwidth")
                out.append(value.toString().padStart(width, '0'))
            }
            else -> {
                if (slots.size >= 8) throw IOException("Too many DASH substitutions")
                val marker = DASH_SLOT_PREFIX + slots.size + "end"
                slots += DashRelaySlot(kind, format, marker)
                out.append(marker)
            }
        }
        i = token.range.last + 1
    }
    val pattern = base.resolve(out.toString())?.toString() ?: throw IOException("Invalid DASH URL")
    val result = DashRelayTemplate(pattern, slots)
    val sample = result.resolve(List(slots.size) { "0" }) ?: throw IOException("Invalid DASH template URL")
    // Dynamic placeholders may occur in paths and queries, never in the authority.
    if (DASH_SLOT_PREFIX in sample.host || DASH_SLOT_PREFIX in pattern.substringBefore("://") ||
        DASH_SLOT_PREFIX in pattern.substringAfter("://").substringBefore('/').substringBefore('?'))
        throw IOException("DASH template authority unsupported")
    return result
}

private fun Element.elements(): List<Element> = buildList {
    for (i in 0 until childNodes.length) (childNodes.item(i) as? Element)?.let(::add)
}
private fun Element.tag(): String = localName ?: tagName

/**
 * Flatten inherited addressing onto each representation and rewrite only known media references.
 * SegmentTemplate, SegmentList and SegmentBase are supported, including range-addressed files.
 * XLink, MPD patches, external XML entities and unsupported addressing are rejected.
 */
internal fun rewriteVlcDashManifest(
    text: String,
    base: HttpUrl,
    localUri: (HttpUrl, Boolean) -> String,
    localTemplate: (DashRelayTemplate) -> String,
): String {
    if ("<!DOCTYPE" in text || "<!ENTITY" in text) throw IOException("DASH DTD forbidden")
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    val document = try {
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entity forbidden") }
        }.parse(InputSource(StringReader(text)))
    } catch (e: Exception) { throw IOException("Invalid DASH manifest", e) }
    val root = document.documentElement
    if (root.tag() != "MPD") throw IOException("Invalid DASH root")
    var nodes = 0
    var references = 0
    fun uri(url: HttpUrl, manifest: Boolean = false): String {
        if (++references > 4096) throw IOException("Too many DASH references")
        return localUri(url, manifest)
    }
    fun resolved(url: HttpUrl, value: String): HttpUrl =
        url.resolve(value.trim()) ?: throw IOException("Invalid DASH URL")
    val descriptorNames = setOf("SegmentTemplate", "SegmentList", "SegmentBase")
    fun merge(parent: Element?, own: Element?): Element? {
        if (own == null) return parent?.cloneNode(true) as? Element
        if (parent == null || parent.tag() != own.tag()) return own.cloneNode(true) as Element
        val result = parent.cloneNode(true) as Element
        for (i in 0 until own.attributes.length) {
            val a = own.attributes.item(i)
            result.setAttributeNS(a.namespaceURI, a.nodeName, a.nodeValue)
        }
        val replacements = own.elements()
        val replaced = replacements.map { it.tag() }.toSet()
        result.elements().filter { it.tag() in replaced }.forEach { result.removeChild(it) }
        replacements.forEach { result.appendChild(it.cloneNode(true)) }
        return result
    }

    fun inspect(element: Element, depth: Int) {
        if (++nodes > 20_000 || depth > 64) throw IOException("DASH manifest too complex")
        if (element.hasAttributeNS("http://www.w3.org/1999/xlink", "href") ||
            element.hasAttributeNS("http://www.w3.org/XML/1998/namespace", "base") ||
            element.tag() in setOf("PatchLocation", "XLink", "ContentSteering", "RemotePeriod"))
            throw IOException("Unsupported external DASH addressing")
        element.elements().forEach { inspect(it, depth + 1) }
    }
    inspect(root, 0)

    fun rewriteDescriptor(descriptor: Element, url: HttpUrl, representation: Element): Boolean {
        var needsFile = descriptor.tag() == "SegmentBase"
        if (descriptor.tag() == "SegmentTemplate" && !descriptor.hasAttribute("media"))
            throw IOException("DASH template without media")
        val id = representation.getAttribute("id")
        val bandwidth = representation.getAttribute("bandwidth")
        for (name in listOf("media", "initialization", "index", "bitstreamSwitching")) {
            if (!descriptor.hasAttribute(name)) continue
            val value = descriptor.getAttribute(name)
            if (descriptor.tag() == "SegmentTemplate") {
                if (++references > 4096) throw IOException("Too many DASH references")
                val compiled = compileDashRelayTemplate(url, value, id, bandwidth)
                descriptor.setAttribute(name, localTemplate(compiled))
            } else descriptor.setAttribute(name, uri(resolved(url, value)))
        }
        for (child in descriptor.elements()) {
            when (child.tag()) {
                "SegmentURL" -> {
                    if (!child.hasAttribute("media")) needsFile = true
                    for (name in listOf("media", "index")) if (child.hasAttribute(name))
                        child.setAttribute(name, uri(resolved(url, child.getAttribute(name))))
                }
                "Initialization", "RepresentationIndex", "BitstreamSwitching" -> {
                    if (child.hasAttribute("sourceURL"))
                        child.setAttribute("sourceURL", uri(resolved(url, child.getAttribute("sourceURL"))))
                    else needsFile = true
                }
            }
        }
        return needsFile
    }

    var representations = 0
    fun visit(element: Element, parentBase: HttpUrl, inherited: Element?) {
        val children = element.elements()
        val bases = children.filter { it.tag() == "BaseURL" }
        // Keep one deterministic base rather than exposing unconstrained alternative endpoints.
        if (bases.any { it.hasAttribute("availabilityTimeOffset") || it.hasAttribute("availabilityTimeComplete") })
            throw IOException("DASH BaseURL low-latency attributes unsupported")
        val url = bases.firstOrNull()?.let { resolved(parentBase, it.textContent) } ?: parentBase
        bases.forEach { element.removeChild(it) }
        val descriptors = children.filter { it.tag() in descriptorNames }
        if (descriptors.size > 1) throw IOException("Ambiguous DASH segment addressing")
        val descriptor = merge(inherited, descriptors.singleOrNull())
        descriptors.forEach { element.removeChild(it) }
        if (element.tag() == "Representation") {
            if (++representations > 1024) throw IOException("Too many DASH representations")
            val needsFile = descriptor == null || rewriteDescriptor(descriptor, url, element)
            if (needsFile) {
                if (url == base || url.encodedPath.endsWith('/')) throw IOException("Missing DASH representation file")
                val local = document.createElementNS(element.namespaceURI,
                    element.prefix?.let { it + ":BaseURL" } ?: "BaseURL")
                local.textContent = uri(url)
                element.insertBefore(local, element.firstChild)
            }
            if (descriptor != null) element.appendChild(descriptor)
            return
        }
        for (child in element.elements()) when (child.tag()) {
            "Period", "AdaptationSet", "Representation" -> visit(child, url, descriptor)
            "Location" -> child.textContent = uri(resolved(url, child.textContent), true)
            "UTCTiming" -> {
                val scheme = child.getAttribute("schemeIdUri")
                if (":http-" in scheme) child.setAttribute("value", uri(resolved(url, child.getAttribute("value"))))
            }
        }
    }
    visit(root, base, null)
    if (representations == 0) throw IOException("DASH manifest without representations")
    return try {
        StringWriter().also {
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it))
        }.toString()
    } catch (e: Exception) { throw IOException("Could not serialize DASH manifest", e) }
}

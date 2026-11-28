package com.ambient.tv.moonlight

import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/**
 * Bounded XML for GameStream, on both Android and the host JVM.
 * Reject declarations before giving a strictly decoded character stream to SAX.
 * External resolution is independently denied; no optional Xerces/JAXP features
 * that Android's parser does not implement are required.
 */
internal fun parseMoonlightXml(
    bytes: ByteArray,
    maxBytes: Int,
    maxElements: Int,
    onStart: (List<String>, Attributes) -> Unit,
    onEnd: (List<String>, String) -> Unit,
) {
    if (bytes.isEmpty() || bytes.size > maxBytes) throw IOException("Invalid Moonlight XML size")
    try {
        val xml = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        if (xml.contains("<!DOCTYPE", ignoreCase = true) ||
            xml.contains("<!ENTITY", ignoreCase = true))
            throw IOException("XML document declarations not allowed")
        val path = ArrayList<String>(8)
        val values = ArrayList<StringBuilder>(8)
        var elements = 0
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                throw SAXException("External XML resolution denied")
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                if (++elements > maxElements || path.size >= 16 || attributes.length > 32)
                    throw SAXException("Moonlight XML complexity limit exceeded")
                path.add(qName)
                values.add(StringBuilder())
                onStart(path, attributes)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                val value = values.lastOrNull() ?: return
                if (value.length + length > 16_384) throw SAXException("Moonlight XML field too large")
                value.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                onEnd(path, values.removeAt(values.lastIndex).toString().trim())
                path.removeAt(path.lastIndex)
            }
        }
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
            .newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        reader.parse(InputSource(StringReader(xml)))
    } catch (error: IOException) {
        throw error
    } catch (error: Exception) {
        throw IOException("Malformed Moonlight XML", error)
    }
}

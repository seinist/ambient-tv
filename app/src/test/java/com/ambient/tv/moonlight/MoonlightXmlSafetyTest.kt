package com.ambient.tv.moonlight

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MoonlightXmlSafetyTest {
    @Test fun acceptsEscapedTitles() {
        val xml = """<root status_code="200"><App><ID>1</ID><AppTitle>A &amp; B</AppTitle></App></root>"""
        assertEquals("A & B", parseMoonlightAppList(xml.toByteArray()).apps.single().title)
    }
    @Test fun rejectsInvalidEncodingAndDeepDocuments() {
        assertThrows(IOException::class.java) { parseMoonlightAppList(byteArrayOf(0xc0.toByte(), 0x80.toByte())) }
        val deep = """<root status_code="200">""" + "<a>".repeat(20) + "</a>".repeat(20) + "</root>"
        assertThrows(IOException::class.java) { parseMoonlightAppList(deep.toByteArray()) }
    }
    @Test fun rejectsAmbiguousServerIdentityAndIgnoresNestedFields() {
        val duplicate = """<root status_code="200"><uniqueid>a</uniqueid><uniqueid>b</uniqueid><appversion>7</appversion></root>"""
        assertThrows(IOException::class.java) { parseMoonlightServerInfo(duplicate.toByteArray()) }
        val nested = """<root status_code="200"><App><other><ID>1</ID></other><AppTitle>Title</AppTitle></App></root>"""
        assertTrue(parseMoonlightAppList(nested.toByteArray()).apps.isEmpty())
    }
}

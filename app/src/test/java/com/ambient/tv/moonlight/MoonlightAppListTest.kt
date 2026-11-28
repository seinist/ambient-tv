package com.ambient.tv.moonlight

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MoonlightAppListTest {
    @Test fun parsesUpstreamFieldsAndDeduplicatesByStableId() {
        val xml = """<root status_code="200">
        <App><AppTitle>Desktop</AppTitle><ID>1</ID><IsHdrSupported>1</IsHdrSupported></App>
        <App><ID>2</ID><AppTitle>Steam</AppTitle><IsHdrSupported>0</IsHdrSupported></App>
        <App><AppTitle>Duplicate</AppTitle><ID>2</ID></App>
        <App><ID>malformed</ID><AppTitle>Invalid</AppTitle></App>
        </root>"""
        val apps = parseMoonlightAppList(xml.toByteArray()).apps
        assertEquals(2, apps.size)
        assertEquals(MoonlightApp(1, "Desktop", true), apps[0])
        assertEquals(MoonlightApp(2, "Steam", false), apps[1])
    }

    @Test fun refusesFailedResponseAndEntityExpansion() {
        assertThrows(IOException::class.java) {
            parseMoonlightAppList("""<root status_code="401"><App><AppTitle>Secret</AppTitle><ID>1</ID></App></root>""".toByteArray())
        }
        assertThrows(IOException::class.java) {
            parseMoonlightAppList("""<!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root status_code="200"><App><AppTitle>&x;</AppTitle><ID>1</ID></App></root>""".toByteArray())
        }
        assertThrows(IOException::class.java) { parseMoonlightAppList(ByteArray(1024 * 1024 + 1)) }
    }

    @Test fun ignoresMalformedAppsAndAcceptsEmptyCatalog() {
        assertTrue(parseMoonlightAppList("""<root status_code="200"></root>""".toByteArray()).apps.isEmpty())
        assertTrue(parseMoonlightAppList("""<root status_code="200"><App><ID>-1</ID><AppTitle>Invalid</AppTitle></App></root>""".toByteArray()).apps.isEmpty())
    }
}

package com.ambient.tv.moonlight

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MoonlightHostTest {
    @Test fun addressesAcceptHostnamesAndIpv6WithoutResolvingDns() {
        assertEquals("gaming-pc.local", MoonlightHostAddress.normalize("  GAMING-PC.local. "))
        assertEquals("192.168.1.12", MoonlightHostAddress.normalize("192.168.1.12"))
        assertEquals("::1", MoonlightHostAddress.normalize("[::1]"))
        assertNull(MoonlightHostAddress.normalize("computer:47989"))
        assertNull(MoonlightHostAddress.normalize("http://computer"))
        assertNull(MoonlightHostAddress.normalize("user@computer"))
        assertNull(MoonlightHostAddress.normalize("computer/path"))
        assertNull(MoonlightHostAddress.normalize("computer", 65536))
        assertNull(MoonlightHostAddress.normalize("a..b"))
        assertNull(MoonlightHostAddress.normalize("[not-ipv6]"))
    }

    @Test fun serverInfoRejectsEntityDeclarationsAndInvalidIdentity() {
        val xml = """<root status_code="200"><hostname>Gaming PC</hostname><uniqueid>abc</uniqueid><appversion>7.1.1</appversion><HttpsPort>47984</HttpsPort></root>"""
        val info = parseMoonlightServerInfo(xml.toByteArray())
        assertEquals("Gaming PC", info.name)
        assertEquals("abc", info.uniqueId)
        assertEquals(47984, info.httpsPort)
        assertThrows(IOException::class.java) {
            parseMoonlightServerInfo("""<!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root status_code="200"><uniqueid>&x;</uniqueid><appversion>7.1</appversion></root>""".toByteArray())
        }
        assertThrows(IOException::class.java) {
            parseMoonlightServerInfo("""<root status_code="200"><hostname>Someone</hostname></root>""".toByteArray())
        }
    }

    @Test fun hostMetadataValidationNeverAcceptsCredentials() {
        assertEquals("test.local", MoonlightHost(id = "id-1", host = "TEST.local", name = "PC").validated().host)
        assertThrows(IllegalArgumentException::class.java) {
            MoonlightHost(id = "id-1", host = "user:pass@host").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoonlightHost(id = "id-2", host = "pc.local", macAddress = "not-a-mac").validated()
        }
    }
}

package com.ambient.tv.ui.files

import org.junit.Assert.*
import org.junit.Test

class WebDavAddressTest {
    @Test fun pastedUrlFillsTheForm() {
        assertEquals(WebDavAddress("nas.lan", 5006, true, "/dav/media"), parseWebDavAddress("https://nas.lan:5006/dav/media/"))
        assertEquals(WebDavAddress("10.0.0.2", 80, false, ""), parseWebDavAddress("http://10.0.0.2"))
        assertNull(parseWebDavAddress("nas.lan"))
        assertNull(parseWebDavAddress("smb://nas.lan/share"))
    }

    @Test fun hostWithPortIsSplitButIpv6StaysWhole() {
        assertEquals("nas" to 2222, splitHostPort("nas:2222"))
        assertEquals("nas" to 22, splitHostPort("sftp://nas:22/"))
        assertEquals("fe80::1" to 8443, splitHostPort("[fe80::1]:8443"))
        assertNull(splitHostPort("fe80::1"))
        assertNull(splitHostPort("nas"))
        assertNull(splitHostPort("nas:99999"))
    }
}

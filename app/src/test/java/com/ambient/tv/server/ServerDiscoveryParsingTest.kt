package com.ambient.tv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDiscoveryParsingTest {
    @Test
    fun `gdm reply yields name port and identifier`() {
        val raw = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nName: Wohnzimmer\r\nPort: 32401\r\n" +
            "Resource-Identifier: abc123\r\nVersion: 1.40.0\r\n\r\n"
        val server = parsePlexGdmResponse("192.168.1.10", raw)
        assertEquals("Wohnzimmer", server.name)
        assertEquals(32401, server.port)
        assertEquals("abc123", server.machineIdentifier)
        assertEquals("1.40.0", server.version)
    }

    @Test
    fun `gdm reply without headers falls back to host and default port`() {
        val server = parsePlexGdmResponse("10.0.0.5", "HTTP/1.0 200 OK\r\nName: \r\nPort: x\r\n")
        assertEquals("10.0.0.5", server.name)
        assertEquals(32400, server.port)
        assertNull(server.machineIdentifier)
    }

    @Test
    fun `media browser reply keeps scheme port and base path`() {
        val reply = parseMediaBrowserDiscovery(
            """{"Address":"https://nas.local:8920/jellyfin/","Id":"f00","Name":"NAS","EndpointAddress":null}""", "192.168.1.2",
        )!!
        assertEquals("nas.local", reply.host)
        assertEquals(8920, reply.port)
        assertTrue(reply.https)
        assertEquals("/jellyfin", reply.basePath)
        assertEquals("f00", reply.key)
        assertEquals("NAS", reply.name)
    }

    @Test
    fun `media browser reply defaults ports and falls back to host for name and key`() {
        val http = parseMediaBrowserDiscovery("""{"Address":"http://192.168.1.3","Name":""}""", null)!!
        assertEquals(8096, http.port)
        assertEquals("", http.basePath)
        assertEquals("192.168.1.3", http.name)
        assertEquals("192.168.1.3:8096", http.key)
        assertEquals(443, parseMediaBrowserDiscovery("""{"Address":"https://emby.example"}""", null)!!.port)
    }

    @Test
    fun `unrelated datagrams are ignored`() {
        assertNull(parseMediaBrowserDiscovery("Who is JellyfinServer?", "10.0.0.1"))
        assertNull(parseMediaBrowserDiscovery("""["Address"]""", "10.0.0.1"))
        assertNull(parseMediaBrowserDiscovery("""{"Name":"x"}""", "10.0.0.1"))
        assertNull(parseMediaBrowserDiscovery("""{"Address":{"nested":1}}""", "10.0.0.1"))
    }

    @Test
    fun `connections are reused only for the same endpoint inside the window`() {
        assertTrue(connectionReusable(true, "a", "a", lastCheckMs = 1_000, nowMs = 31_000, windowMs = 30_000))
        assertFalse(connectionReusable(true, "a", "a", lastCheckMs = 1_000, nowMs = 31_001, windowMs = 30_000))
        assertFalse(connectionReusable(true, "a", "b", lastCheckMs = 1_000, nowMs = 2_000, windowMs = 30_000))
        assertFalse(connectionReusable(false, "a", "a", lastCheckMs = 1_000, nowMs = 2_000, windowMs = 30_000))
        assertFalse(connectionReusable(true, null, "a", lastCheckMs = 1_000, nowMs = 2_000, windowMs = 30_000))
    }
}

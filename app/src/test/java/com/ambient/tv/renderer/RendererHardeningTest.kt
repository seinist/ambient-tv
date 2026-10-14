package com.ambient.tv.renderer

import com.ambient.tv.data.shared.SharedMediaLink
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile input from the LAN: every case here is something any device on the network can send
 * to the UPnP receiver without pairing. Complements [RendererProtocolTest] (the happy paths).
 */
class RendererHardeningTest {

    private fun read(raw: String, clock: () -> Long = { 0L }) =
        readRendererRequest(ByteArrayInputStream(raw.toByteArray()), deadlineMs = 10_000L, clock = clock)

    private fun assertRejected(raw: String) {
        assertThrows(IOException::class.java) { read(raw) }
    }

    // --- HTTP request reader ---------------------------------------------------------------

    @Test
    fun `malformed request lines are refused`() {
        assertRejected("GET /\r\n\r\n")
        assertRejected("GET / FTP/1.0\r\n\r\n")
        assertRejected("GET  / HTTP/1.1\r\n\r\n")
        assertRejected("")
    }

    @Test
    fun `oversized lines and header floods are refused`() {
        assertRejected("GET /" + "a".repeat(8_200) + " HTTP/1.1\r\n\r\n")
        assertRejected("GET / HTTP/1.1\r\nX-Big: " + "b".repeat(8_200) + "\r\n\r\n")
        val flood = (1..65).joinToString("") { "X-H$it: v\r\n" }
        assertRejected("GET / HTTP/1.1\r\n$flood\r\n")
    }

    @Test
    fun `broken headers and bodies are refused`() {
        assertRejected("GET / HTTP/1.1\r\nNoColonHere\r\n\r\n")
        assertRejected("GET / HTTP/1.1\r\n: empty-name\r\n\r\n")
        assertRejected("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n")
        assertRejected("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")
        assertRejected("POST / HTTP/1.1\r\nTRANSFER-ENCODING: identity\r\n\r\n")
        // A request cut off before the blank line never reaches the body.
        assertRejected("GET / HTTP/1.1\r\nHost: x\r\n")
    }

    @Test
    fun `a client that trickles bytes past the deadline is cut off`() {
        var now = 0L
        val slow = { now += 1_000L; now }
        assertThrows(IOException::class.java) {
            read("GET / HTTP/1.1\r\nHost: tv\r\n\r\n", clock = slow)
        }
    }

    @Test
    fun `request parts are normalised`() {
        val request = read("post /ctl/AVTransport?debug=1 HTTP/1.0\r\n  SoapAction :  \"a#b\"  \r\n\r\n")
        assertEquals("POST", request.method)
        assertEquals("/ctl/AVTransport", request.path)
        assertEquals("\"a#b\"", request.headers["soapaction"])
        assertEquals("", request.body)
    }

    // --- SOAP / DIDL ---------------------------------------------------------------------

    @Test
    fun `soap action headers outside the expected shape are ignored`() {
        assertNull(parseSoapAction(null))
        assertNull(parseSoapAction(""))
        assertNull(parseSoapAction("\"#Play\""))
        assertNull(parseSoapAction("\"$AV_TRANSPORT#\""))
        assertNull(parseSoapAction("\"$AV_TRANSPORT#Play;reboot\""))
        assertNull(parseSoapAction("\"$AV_TRANSPORT#1Play\""))
        assertNull(parseSoapAction("\"$AV_TRANSPORT#" + "A".repeat(65) + "\""))
        assertEquals(AV_TRANSPORT to "Play", parseSoapAction("$AV_TRANSPORT#Play"))
    }

    @Test
    fun `soap arguments of another action or without a body are empty`() {
        val envelope = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:Stop xmlns:u="$AV_TRANSPORT"><InstanceID>0</InstanceID></u:Stop></s:Body></s:Envelope>"""
        assertTrue(parseSoapArguments(envelope, "Play").isEmpty())
        assertTrue(parseSoapArguments("<s:Envelope xmlns:s=\"x\"/>", "Play").isEmpty())
    }

    @Test
    fun `an external entity in a soap body is never resolved`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/hosts">]>
            <s:Envelope xmlns:s="x"><s:Body><u:Play xmlns:u="y"><Speed>&e;</Speed></u:Play></s:Body></s:Envelope>"""
        val parsed = runCatching { parseSoapArguments(xxe, "Play") }
        assertTrue(parsed.isFailure || parsed.getOrNull()?.get("Speed").isNullOrEmpty())
    }

    @Test
    fun `didl metadata that is too large or broken is dropped`() {
        assertNull(parseDidl("<DIDL-Lite>" + "x".repeat(64 * 1024) + "</DIDL-Lite>"))
        assertNull(parseDidl("<DIDL-Lite><item>"))
        assertNull(parseDidl("   "))
        val wildcard = parseDidl("""<DIDL-Lite><item><res protocolInfo="http-get:*:*:*">u</res></item></DIDL-Lite>""")
        assertNotNull(wildcard)
        assertNull(wildcard!!.mimeType)
    }

    // --- UPnP / GENA / SSDP values -------------------------------------------------------

    @Test
    fun `upnp times outside the clock range are malformed`() {
        assertNull(parseUpnpTime("1:60:00"))
        assertNull(parseUpnpTime("1:00:60"))
        assertNull(parseUpnpTime("100000:00:00"))
        assertNull(parseUpnpTime("NOT_IMPLEMENTED"))
        assertNull(parseUpnpTime(null))
        assertEquals(1_500L, parseUpnpTime("+0:00:01.5"))
        assertEquals(1_000L, parseUpnpTime("0:00:01.1/2"))
    }

    @Test
    fun `subscription timeouts and callbacks stay bounded`() {
        assertEquals(1800, parseTimeoutSeconds(null))
        assertEquals(1800, parseTimeoutSeconds("Second-abc"))
        assertEquals(1800, parseTimeoutSeconds("Second-99999999999"))
        assertEquals(60, parseTimeoutSeconds("Second--5"))
        assertEquals(3600, parseTimeoutSeconds("Second-86400"))
        val many = (1..10).joinToString("") { "<http://192.168.1.5:$it/>" }
        assertEquals(4, parseCallbacks(many).size)
        assertTrue(parseCallbacks("<" + "a".repeat(600) + ">").isEmpty())
        assertTrue(parseCallbacks("http://192.168.1.5/no-brackets").isEmpty())
    }

    @Test
    fun `m-search requests outside the spec are ignored and mx is clamped`() {
        fun search(man: String?, st: String?, mx: String?) = buildString {
            append("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n")
            if (man != null) append("MAN: $man\r\n")
            if (st != null) append("ST: $st\r\n")
            if (mx != null) append("MX: $mx\r\n")
            append("\r\n")
        }
        assertNull(parseMSearch(search(null, "ssdp:all", "1")))
        assertNull(parseMSearch(search("\"ssdp:alive\"", "ssdp:all", "1")))
        assertNull(parseMSearch(search("\"ssdp:discover\"", null, "1")))
        assertNull(parseMSearch(search("\"ssdp:discover\"", "x".repeat(257), "1")))
        assertNull(parseMSearch("NOTIFY * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\nST: ssdp:all\r\n\r\n"))
        assertEquals("ssdp:all" to 5, parseMSearch(search("\"ssdp:discover\"", "ssdp:all", "120")))
        assertEquals("ssdp:all" to 0, parseMSearch(search("\"ssdp:discover\"", "ssdp:all", "-3")))
    }

    // --- URLs a control point hands to the receiver --------------------------------------

    private fun rendered(url: String) = SharedMediaLink.parseRendered(url, "video/mp4", null, "t")

    @Test
    fun `loopback in any disguise is refused`() {
        listOf(
            "http://127.0.0.1/a.mp4",
            "http://127.1.2.3:8080/a.mp4",
            "http://localhost/a.mp4",
            "http://LOCALHOST./a.mp4",
            "http://media.localhost/a.mp4",
            "http://0177.0.0.1/a.mp4",
            "http://127.1/a.mp4",
            "http://2130706433/a.mp4",
            "http://0x7f.0.0.1/a.mp4",
            "http://0X7F000001/a.mp4",
            "http://[::1]/a.mp4",
            "http://[::ffff:127.0.0.1]/a.mp4",
            "http://[::]/a.mp4",
            "http://0.0.0.0/a.mp4",
        ).forEach { assertNull(it, rendered(it)) }
    }

    @Test
    fun `link-local multicast and broadcast targets are refused`() {
        listOf(
            "http://169.254.169.254/latest/a.mp4",
            "http://[fe80::1]/a.mp4",
            "http://224.0.0.1/a.mp4",
            "http://239.255.255.250:1900/a.mp4",
            "http://[ff02::1]/a.mp4",
            "http://255.255.255.255/a.mp4",
        ).forEach { assertNull(it, rendered(it)) }
    }

    @Test
    fun `other schemes credentials and malformed urls are refused`() {
        listOf(
            "file:///sdcard/Movies/a.mp4",
            "content://media/external/video/1",
            "rtsp://192.168.1.5/a.mp4",
            "javascript:alert(1)",
            "http://user:pw@192.168.1.5/a.mp4",
            "http://192.168.1.5/a.mp4#frag",
            "http://192.168.1.5:0/a.mp4",
            "http://192.168.1.5:70000/a.mp4",
            "http://192.168.1.5/a\\b.mp4",
            "http://192.168.1.5/" + "a".repeat(4_100) + ".mp4",
            "http://192.168.1.5/a%0d%0aX-Injected:1.mp4\r\n",
            "",
        ).forEach { assertNull(it, rendered(it)) }
    }

    @Test
    fun `ordinary lan and internet media stays playable`() {
        listOf(
            "http://192.168.1.5:8200/MediaItems/1.mkv",
            "http://10.0.0.7/stream/abc",
            "https://nas.local/videos/a.mp4",
            "http://[fd00::5]:8096/a.mp4",
            "http://plex-server/a.mp4",
        ).forEach { assertNotNull(it, rendered(it)) }
    }
}

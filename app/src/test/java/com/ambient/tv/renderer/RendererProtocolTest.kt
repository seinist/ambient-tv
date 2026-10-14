package com.ambient.tv.renderer

import com.ambient.tv.data.shared.SharedMediaLink
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererProtocolTest {
    private val udn = "uuid:11111111-2222-3333-4444-555555555555"

    @Test
    fun `a sender is its app and its address, so a reused address is not trusted by itself`() {
        val bubble = rendererSender("Linux/5.10 UPnP/1.0 BubbleUPnP/4.2.1", "192.168.1.23")
        assertEquals("BubbleUPnP (192.168.1.23)", bubble)
        assertEquals(bubble, rendererSender("Linux/5.10 UPnP/1.0 BubbleUPnP/4.2.1", "192.168.1.23"))
        // Another app that later gets the same address by DHCP is a different sender.
        assertFalse(bubble == rendererSender("Dalvik/2.1.0 Linux/6.1 Hi-Fi_Cast/3.0", "192.168.1.23"))
        assertFalse(bubble == rendererSender("Linux/5.10 UPnP/1.0 BubbleUPnP/4.2.1", "192.168.1.24"))
        // Without a product token only the address is left (as in older versions).
        assertEquals("192.168.1.23", rendererSender(null, "192.168.1.23"))
        assertEquals("Evil (192.168.1.23)", rendererSender("Ev<il>/1 (x)", "192.168.1.23"))
    }

    @Test
    fun `soap action header gives service and action`() {
        assertEquals(AV_TRANSPORT to "Play", parseSoapAction("\"$AV_TRANSPORT#Play\""))
        assertEquals(RENDERING_CONTROL to "SetVolume", parseSoapAction("$RENDERING_CONTROL#SetVolume"))
        assertNull(parseSoapAction("\"$AV_TRANSPORT#\""))
        assertNull(parseSoapAction("\"$AV_TRANSPORT#Play<x>\""))
        assertNull(parseSoapAction(null))
    }

    @Test
    fun `soap arguments are read from the action element`() {
        val didl = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;A &amp;amp; B&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT"><InstanceID>0</InstanceID>
            <CurrentURI>http://192.168.1.5:8080/v.mp4</CurrentURI><CurrentURIMetaData>$didl</CurrentURIMetaData>
            </u:SetAVTransportURI></s:Body></s:Envelope>"""
        val args = parseSoapArguments(body, "SetAVTransportURI")
        assertEquals("0", args["InstanceID"])
        assertEquals("http://192.168.1.5:8080/v.mp4", args["CurrentURI"])
        assertEquals("<DIDL-Lite><item><dc:title>A &amp; B</dc:title></item></DIDL-Lite>", args["CurrentURIMetaData"])
    }

    @Test
    fun `soap bodies with a doctype are refused`() {
        val body = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Play xmlns:u="$AV_TRANSPORT"><Speed>&e;</Speed></u:Play></s:Body></s:Envelope>"""
        assertThrows(Exception::class.java) { parseSoapArguments(body, "Play") }
    }

    @Test
    fun `didl metadata gives title class and mime`() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="1" parentID="0" restricted="1"><dc:title>Trailer</dc:title><upnp:class>object.item.videoItem.movie</upnp:class>
            <res protocolInfo="http-get:*:video/x-matroska:*">http://x/1</res></item></DIDL-Lite>"""
        assertEquals(DidlItem("Trailer", "object.item.videoItem.movie", "video/x-matroska"), parseDidl(didl))
        assertNull(parseDidl(""))
        assertNull(parseDidl("not xml"))
    }

    @Test
    fun `upnp times round trip`() {
        assertEquals("0:00:00", formatUpnpTime(0))
        assertEquals("1:02:03", formatUpnpTime(3_723_999))
        assertEquals(3_723_000L, parseUpnpTime("01:02:03"))
        assertEquals(3_723_500L, parseUpnpTime("1:02:03.5"))
        assertEquals(3_723_000L, parseUpnpTime("1:02:03.1/3"))
        assertNull(parseUpnpTime("1:61:00"))
        assertNull(parseUpnpTime("abc"))
    }

    @Test
    fun `responses and faults escape their values`() {
        val response = soapResponse(AV_TRANSPORT, "GetMediaInfo", listOf("CurrentURI" to "http://x/?a=1&b=<2>"))
        assertTrue(response.contains("<CurrentURI>http://x/?a=1&amp;b=&lt;2&gt;</CurrentURI>"))
        assertTrue(response.contains("GetMediaInfoResponse"))
        assertTrue(soapFault(701, "Transition not available").contains("<errorCode>701</errorCode>"))
    }

    @Test
    fun `last change values are escaped twice`() {
        val event = lastChangeEvent(RendererService.AVTRANSPORT, listOf("AVTransportURI" to "http://x/?a=1&b=2"))
        assertTrue(event.contains("&lt;Event"))
        assertTrue(event.contains("a=1&amp;amp;b=2"))
    }

    @Test
    fun `device description lists the three services`() {
        val xml = deviceDescription(udn, "TV <Wohnzimmer>", "Ambient", "1.0")
        assertTrue(xml.contains(MEDIA_RENDERER))
        RendererService.entries.forEach { assertTrue(xml.contains(it.controlPath)) }
        assertTrue(xml.contains("TV &lt;Wohnzimmer&gt;"))
        assertTrue(AV_TRANSPORT_SCPD.contains("<name>SetAVTransportURI</name>"))
    }

    @Test
    fun `m-search parsing and matching`() {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $MEDIA_RENDERER\r\n\r\n"
        assertEquals(MEDIA_RENDERER to 2, parseMSearch(search))
        assertNull(parseMSearch("NOTIFY * HTTP/1.1\r\nNT: x\r\n\r\n"))
        assertNull(parseMSearch("M-SEARCH * HTTP/1.1\r\nMAN: \"other\"\r\nST: ssdp:all\r\n\r\n"))
        assertEquals(ssdpTargets(udn).size, matchingTargets("ssdp:all", udn).size)
        assertEquals(listOf(MEDIA_RENDERER to "$udn::$MEDIA_RENDERER"), matchingTargets(MEDIA_RENDERER, udn))
        assertEquals(listOf(udn to udn), matchingTargets(udn, udn))
        assertTrue(matchingTargets("urn:schemas-upnp-org:device:MediaServer:1", udn).isEmpty())
    }

    @Test
    fun `callbacks and timeouts are bounded`() {
        assertEquals(listOf("http://192.168.1.5:5000/evt", "http://192.168.1.5:5001/"), parseCallbacks("<http://192.168.1.5:5000/evt><http://192.168.1.5:5001/>"))
        assertEquals(1800, parseTimeoutSeconds("Second-1800"))
        assertEquals(3600, parseTimeoutSeconds("infinite"))
        assertEquals(60, parseTimeoutSeconds("Second-5"))
    }

    @Test
    fun `subnet check`() {
        val local = InetAddress.getByName("192.168.1.20")
        assertTrue(sameSubnet(local, InetAddress.getByName("192.168.1.200"), 24))
        assertFalse(sameSubnet(local, InetAddress.getByName("192.168.2.1"), 24))
        assertTrue(sameSubnet(local, InetAddress.getByName("192.168.3.1"), 16))
        assertFalse(sameSubnet(local, InetAddress.getByName("10.0.0.1"), 8))
    }

    @Test
    fun `requests are read with bounded body`() {
        val body = "<x/>"
        val raw = "POST /ctl/AVTransport HTTP/1.1\r\nSOAPACTION: \"a#b\"\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val request = readRendererRequest(ByteArrayInputStream(raw.toByteArray()))
        assertEquals("POST", request.method)
        assertEquals("/ctl/AVTransport", request.path)
        assertEquals("\"a#b\"", request.headers["soapaction"])
        assertEquals(body, request.body)
        val huge = "POST / HTTP/1.1\r\nContent-Length: ${MAX_RENDERER_BODY + 1}\r\n\r\n"
        assertThrows(IOException::class.java) { readRendererRequest(ByteArrayInputStream(huge.toByteArray())) }
        val chunked = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
        assertThrows(IOException::class.java) { readRendererRequest(ByteArrayInputStream(chunked.toByteArray())) }
    }

    @Test
    fun `rendered links accept extensionless media but not images`() {
        val proxied = SharedMediaLink.parseRendered("http://192.168.1.5:8192/stream/abc", "video/mp4", null, "Film")
        assertNotNull(proxied)
        assertEquals("Film", proxied!!.title)
        assertFalse(proxied.audioOnly)
        val song = SharedMediaLink.parseRendered("http://192.168.1.5/track", null, "object.item.audioItem.musicTrack", null)
        assertTrue(song!!.audioOnly)
        assertNull(SharedMediaLink.parseRendered("http://192.168.1.5/pic", "image/jpeg", "object.item.imageItem.photo", null))
        assertNull(SharedMediaLink.parseRendered("http://192.168.1.5/unknown", null, null, null))
        assertNull(SharedMediaLink.parseRendered("http://127.0.0.1/v.mp4", "video/mp4", null, null))
        assertNull(SharedMediaLink.parseRendered("file:///sdcard/v.mp4", "video/mp4", null, null))
        // Shared links from the phone keep requiring a known extension.
        assertNull(SharedMediaLink.parse("http://192.168.1.5:8192/stream/abc"))
    }
}

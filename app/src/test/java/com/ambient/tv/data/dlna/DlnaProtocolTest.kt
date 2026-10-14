package com.ambient.tv.data.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {
    @Test fun descriptionUsesTheMediaServerIdentityAndHandlesServiceFieldOrder() {
        val server = parseDlnaDeviceDescription(
            "http://192.168.1.20:8200/rootDesc.xml",
            """
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <URLBase>http://192.168.1.20:8200/base/</URLBase>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:2</deviceType>
                    <friendlyName>Living Room Library</friendlyName>
                    <UDN>uuid:root-server</UDN>
                    <serviceList>
                      <service>
                        <controlURL>content/control</controlURL>
                        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:3</serviceType>
                      </service>
                    </serviceList>
                    <deviceList>
                      <device>
                        <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
                        <friendlyName>Embedded Device</friendlyName>
                        <UDN>uuid:embedded-device</UDN>
                      </device>
                    </deviceList>
                  </device>
                </root>
            """.trimIndent(),
        )

        requireNotNull(server)
        assertEquals("Living Room Library", server.name)
        assertEquals("uuid:root-server", server.udn)
        assertEquals("http://192.168.1.20:8200/base/content/control", server.contentDirectoryUrl)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:3", server.contentDirectoryType)
    }

    @Test fun descriptionFindsAnEmbeddedMediaServer() {
        val server = parseDlnaDeviceDescription(
            "http://192.168.1.30/device.xml",
            """
                <root><device>
                  <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
                  <friendlyName>Root</friendlyName><UDN>uuid:root</UDN>
                  <deviceList><device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>Embedded Media</friendlyName><UDN>uuid:media</UDN>
                    <serviceList><service>
                      <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                      <controlURL>/cds</controlURL>
                    </service></serviceList>
                  </device></deviceList>
                </device></root>
            """.trimIndent(),
        )

        requireNotNull(server)
        assertEquals("Embedded Media", server.name)
        assertEquals("uuid:media", server.udn)
        assertEquals("http://192.168.1.30/cds", server.contentDirectoryUrl)
    }

    @Test fun didlReadsResourceAttributesBeforeConsumingItsText() {
        val nodes = parseDlnaDidl(
            """
                <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                  <item id="video-1" parentID="0">
                    <dc:title>Example</dc:title>
                    <upnp:class>object.item.videoItem</upnp:class>
                    <upnp:albumArtURI>   </upnp:albumArtURI>
                    <res protocolInfo="http-get:*:video/mp4:*" duration="01:02:03.250"
                        size="123456" resolution="1920x1080">/media/example.mp4</res>
                  </item>
                </DIDL-Lite>
            """.trimIndent(),
            server(),
            "server-1",
        )

        val node = nodes.single()
        val resource = node.resources.single()
        assertNull(node.artworkUrl)
        assertEquals("http-get:*:video/mp4:*", resource.protocolInfo)
        assertEquals(3_723_000L, resource.durationMs)
        assertEquals(123_456L, resource.size)
        assertEquals(1920, resource.width)
        assertEquals(1080, resource.height)
        assertEquals("http://192.168.1.20:8200/media/example.mp4", resource.url)
    }

    @Test fun subtitleResourcesBecomeTracksNotVersions() {
        val node = parseDlnaDidl(
            """
                <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
                    xmlns:sec="http://www.sec.co.kr/">
                  <item id="video-1" parentID="0">
                    <dc:title>Example</dc:title>
                    <upnp:class>object.item.videoItem</upnp:class>
                    <sec:CaptionInfoEx sec:type="srt">/media/Example.de.srt</sec:CaptionInfoEx>
                    <res protocolInfo="http-get:*:video/x-matroska:*">/media/Example.mkv</res>
                    <res protocolInfo="http-get:*:text/srt:*">/media/Example.en.srt</res>
                    <res protocolInfo="http-get:*:smi/caption:*">/media/Example.smi</res>
                  </item>
                </DIDL-Lite>
            """.trimIndent(),
            server(),
            "server-1",
        ).single()
        assertEquals(listOf("http://192.168.1.20:8200/media/Example.mkv"), node.resources.map { it.url })
        val tracks = node.toMediaItem().mediaVersions.single().tracks
        assertEquals(listOf("de", "en"), tracks.map { it.languageCode })
        assertTrue(tracks.all { it.externalUrl != null && it.codec == "srt" })
    }

    @Test fun soapFaultIsNotReportedAsAnEmptyFolder() {
        val error = assertThrows(IllegalStateException::class.java) {
            parseDlnaBrowseResponse(
                """
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                      <s:Body><s:Fault><faultstring>UPnPError</faultstring><detail>
                        <errorCode>701</errorCode><errorDescription>No Such Object</errorDescription>
                      </detail></s:Fault></s:Body>
                    </s:Envelope>
                """.trimIndent(),
                server(),
                "server-1",
            )
        }

        assertTrue(error.message.orEmpty().contains("701"))
        assertTrue(error.message.orEmpty().contains("No Such Object"))
    }

    @Test fun soapFaultDetailsSurviveAnHttp500Response() {
        val payload = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body><s:Fault><faultstring>UPnPError</faultstring><detail>
                <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                  <errorCode>501</errorCode><errorDescription>Action failed</errorDescription>
                </UPnPError>
              </detail></s:Fault></s:Body>
            </s:Envelope>
        """.trimIndent()

        assertEquals("DLNA error 501: Action failed", dlnaSoapFaultMessage(payload))
    }

    @Test fun soapEnvelopeIncludesEncodingStyleRequiredByPlexMediaServer() {
        val envelope = dlnaSoapEnvelope(
            action = "Browse",
            serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1",
            values = linkedMapOf("ObjectID" to "0", "SortCriteria" to ""),
        )

        assertTrue(envelope.contains("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\""))
        assertTrue(envelope.contains("<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"))
        assertTrue(envelope.contains("<ObjectID>0</ObjectID>"))
    }

    @Test fun ssdpParserRejectsNonResponsesAndMismatchedLocations() {
        val response = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.2/device.xml\r\n\r\n"
        assertNull(parseSsdpLocation("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.2/device.xml\r\n\r\n", "192.168.1.2"))
        assertEquals("http://192.168.1.2/device.xml", parseSsdpLocation(response, "192.168.1.2"))
        assertEquals(
            "https://192.168.1.2:8443/device.xml",
            parseSsdpLocation("HTTP/1.1 200 OK\r\nLOCATION: https://192.168.1.2:8443/device.xml\r\n\r\n", "192.168.1.2"),
        )
        assertNull(parseSsdpLocation("HTTP/1.1 200 OK\r\nLOCATION: http://10.0.0.5/device.xml\r\n\r\n", "192.168.1.2"))
        assertNull(parseSsdpLocation(response, null))
        assertNull(parseSsdpLocation(response, ""))
        assertNull(parseSsdpLocation("HTTP/1.1 200 OK\r\nLOCATION: ftp://192.168.1.2/device.xml\r\n\r\n", "192.168.1.2"))
        assertNull(parseSsdpLocation("HTTP/1.1 200 OK\r\nLOCATION: http://nas.local/device.xml\r\n\r\n", "192.168.1.2"))
        assertEquals(
            "http://[fe80::1]/device.xml",
            parseSsdpLocation("HTTP/1.1 200 OK\r\nLOCATION: http://[fe80::1]/device.xml\r\n\r\n", "fe80::1%wlan0"),
        )
        assertEquals(
            "http://[fe80::1]/device.xml",
            parseSsdpLocation("HTTP/1.1 200 OK\r\nLOCATION: http://[fe80::1]/device.xml\r\n\r\n", "fe80:0:0:0:0:0:0:1"),
        )
        assertTrue(ssdpHostsMatch("::ffff:192.168.1.2", "192.168.1.2"))
    }

    @Test fun xmlDocumentTypesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseXmlDocument("<!DOCTYPE root [<!ENTITY x 'value'>]><root>&x;</root>")
        }
    }

    private fun server() = DlnaServer(
        id = "server-1",
        udn = "uuid:server-1",
        name = "Media Server",
        descriptionUrl = "http://192.168.1.20:8200/rootDesc.xml",
        contentDirectoryUrl = "http://192.168.1.20:8200/ctl/ContentDir",
    )
}

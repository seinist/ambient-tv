package com.ambient.tv.ui.player.backend

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.io.IOException

class VlcAdaptiveRelayTest {
    private val source = "https://media.example.org/live/master.mpd?ticket=abc".toHttpUrl()
    private val routes = HlsRelayRoutes(source, "/secret")
    private val dollar = '$'

    @Test fun inheritedDashTemplatesResolveOnlyDeclaredNumericSlots() {
        val xml = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period><AdaptationSet>
                <SegmentTemplate initialization="init-${dollar}RepresentationID${dollar}.mp4"
                  media="video-${dollar}RepresentationID${dollar}-${dollar}Number%05d${dollar}.m4s" startNumber="1"/>
                <Representation id="h264" bandwidth="1000000"><BaseURL>variant/</BaseURL></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val rewritten = rewriteVlcDashManifest(xml, source, { url, manifest -> routes.register(url, manifest) },
            { routes.registerTemplate(it) })
        val media = Regex("""media="([^"]+)"""").find(rewritten)!!.groupValues[1]
        val init = Regex("""initialization="([^"]+)"""").find(rewritten)!!.groupValues[1]
        assertEquals("https://media.example.org/live/variant/init-h264.mp4", routes.resolve(init)?.url.toString())
        val requested = media.replace(dollar + "Number%05d" + dollar, "00012")
        assertEquals("https://media.example.org/live/variant/video-h264-00012.m4s", routes.resolve(requested)?.url.toString())
        assertNull(routes.resolve(media.replace(dollar + "Number%05d" + dollar, "../private")))
        assertNull(routes.resolve(requested + "?ticket=other"))
    }

    @Test fun segmentListAndSingleFileRangesUseRegisteredUrls() {
        val xml = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet>
                <Representation id="a" bandwidth="1">
                    <SegmentList><Initialization sourceURL="init.mp4"/>
                        <SegmentURL media="one.m4s" mediaRange="10-20"/>
                    </SegmentList>
                </Representation>
                <Representation id="b" bandwidth="2">
                    <BaseURL>whole.mp4</BaseURL><SegmentBase indexRange="5-9"><Initialization range="0-4"/></SegmentBase>
                </Representation>
            </AdaptationSet></Period></MPD>
        """.trimIndent()
        val rewritten = rewriteVlcDashManifest(xml, source, { url, manifest -> routes.register(url, manifest) },
            { routes.registerTemplate(it) })
        assertTrue(rewritten.contains("mediaRange=\"10-20\""))
        assertTrue(rewritten.contains("indexRange=\"5-9\""))
        assertTrue(rewritten.contains("range=\"0-4\""))
        val local = Regex("""/secret/\d+""").findAll(rewritten).map { routes.resolve(it.value)?.url?.encodedPath }.toSet()
        assertTrue(local.containsAll(listOf("/live/init.mp4", "/live/one.m4s", "/live/whole.mp4")))
    }

    @Test fun dashDtdAndDynamicAuthorityAreRejected() {
        assertThrows(IOException::class.java) {
            rewriteVlcDashManifest("<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><MPD>&x;</MPD>",
                source, { _, _ -> error("no URL") }, { error("no template") })
        }
        assertThrows(IOException::class.java) {
            compileDashRelayTemplate(source, "https://${dollar}Number${dollar}.example.org/seg", "a", "1")
        }
    }

    @Test fun publicCdnCapabilitiesNeverReacquireParentCredentials() {
        val cdn = "https://cdn.example.net/variant.m3u8".toHttpUrl()
        val child = routes.register(cdn, true)
        assertFalse(routes.resolve(child)!!.credentialed)
        val sameOriginAgain = routes.register(source, true, parentCredentialed = false)
        assertFalse(routes.resolve(sameOriginAgain)!!.credentialed)
        assertTrue(routes.resolve(routes.register(source, true))!!.credentialed)
        assertThrows(IOException::class.java) {
            routes.register("http://127.0.0.1:8080/private".toHttpUrl(), false, parentCredentialed = false)
        }
    }

    @Test fun foreignDnsRejectsPrivateAndSpecialAddresses() {
        for (host in listOf("127.0.0.1", "10.1.2.3", "100.64.0.1", "169.254.1.1",
            "172.16.0.1", "192.168.1.1", "198.18.0.1", "::1", "fc00::1", "fe80::1",
            "2001:db8::1", "2002:7f00:1::1", "::ffff:127.0.0.1")) {
            assertFalse(host, vlcRelayPublicAddress(InetAddress.getByName(host)))
        }
        assertFalse(vlcRelayCdnUrlAllowed("https://127.0.0.1/private".toHttpUrl()))
        assertFalse(vlcRelayCdnUrlAllowed("https://[::1]/private".toHttpUrl()))
        assertFalse(vlcRelayCdnUrlAllowed("https://192.168.1.1/private".toHttpUrl()))
        assertTrue(vlcRelayPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(vlcRelayPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun hlsVariablesAreExpandedAndImportsAreScopedToTheParent() {
        val master = "#EXTM3U\n#EXT-X-DEFINE:NAME=\"dir\",VALUE=\"variant\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1\n{\$dir}/child.m3u8\n"
        var inherited = emptyMap<String, String>()
        rewriteVlcHlsManifest(master, source, emptyMap()) { url, playlist, vars ->
            assertEquals("/live/variant/child.m3u8", url.encodedPath)
            assertTrue(playlist)
            inherited = vars
            routes.register(url, playlist, variables = vars)
        }
        val child = "#EXTM3U\n#EXT-X-DEFINE:IMPORT=\"dir\"\n#EXT-X-DEFINE:QUERYPARAM=\"ticket\"\n" +
            "#EXTINF:4,\n{\$dir}/seg.m4s?token={\$ticket}\n"
        val rewritten = rewriteVlcHlsManifest(child, source, inherited) { url, playlist, _ ->
            assertEquals("abc", url.queryParameter("token"))
            assertEquals("/live/variant/seg.m4s", url.encodedPath)
            routes.register(url, playlist)
        }
        assertFalse(rewritten.contains("#EXT-X-DEFINE:"))
        assertThrows(IOException::class.java) {
            rewriteVlcHlsManifest(child, source, emptyMap()) { _, _, _ -> error("missing import") }
        }
    }
}

package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvHbbtvApp
import com.ambient.tv.livetv.liveTvHbbtvMenuApps
import java.io.DataInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvheadendHtspHbbtvTest {
    @Test fun htspMessageRoundTripPreservesStringsIntegersAndChallenge() {
        val challenge = byteArrayOf(1, 2, 3, -1)
        val encoded = tvhHtspEncode(mapOf(
            "method" to "hello", "seq" to 1337L, "challenge" to challenge,
        ))
        val decoded = tvhHtspDecode(DataInputStream(encoded.inputStream()))
        assertEquals("hello", decoded["method"])
        assertEquals(1337L, decoded["seq"])
        assertArrayEquals(challenge, decoded["challenge"] as ByteArray)
    }

    @Test fun htspChannelServicesRevealEveryHbbtvApplication() {
        val first = mapOf("title" to listOf(mapOf("name" to "Startseite")),
            "url" to "https://one.example/app", "visibility" to "all")
        val second = mapOf("title" to listOf(mapOf("name" to "Mediathek")),
            "url" to "https://two.example/app", "visibility" to "apps")
        val channel = mapOf<String, Any>("channelIdStr" to "channel-1",
            "services" to listOf(
                mapOf("hbbtv" to mapOf("0" to listOf(first), "1" to listOf(second))),
                mapOf("hbbtv" to mapOf("0" to listOf(first))),
            ))
        val apps = tvhHtspChannelApps(channel)
        assertEquals(listOf("Startseite", "Mediathek"), apps.map { it.name })
        assertEquals(2, apps.size)
    }

    @Test fun httpAndHtspApplicationsMergeWithoutDiscardingExtraApps() {
        val service = Json.parseToJsonElement("""{"hbbtv":{"0":[
            {"url":"https://one.example/app","title":[{"name":"One"}],"visibility":"all"}]}}""").jsonObject
        val channel = Json.parseToJsonElement("""{"services":["abc"]}""").jsonObject
        val metadata = tvhChannelMetadata(channel, emptyMap(), emptyMap(), mapOf("abc" to service),
            listOf(
                TvhHbbtvApplication("https://one.example/app", "Duplicate", "all"),
                TvhHbbtvApplication("https://two.example/app", "Two", "apps"),
            ))
        assertEquals("https://one.example/app", metadata.hbbtvUrl)
        assertEquals(2, metadata.hbbtvApps.size)
        assertEquals("Two", metadata.hbbtvApps[1].name)
    }

    @Test fun onlyServerReportedVisibleAppsBecomeBrowseEntries() {
        val channel = LiveTvChannel("one", "ARD", 1, null,
            hbbtvApps = listOf(
                LiveTvHbbtvApp("https://visible.example", "Mediathek", "all"),
                LiveTvHbbtvApp("https://reserved.example", null, "reserved"),
            ))
        val invisible = LiveTvChannel("two", "ZDF", 2, null,
            hbbtvApps = listOf(LiveTvHbbtvApp("https://hidden.example", null, "none")))
        assertEquals(1, liveTvHbbtvMenuApps(channel).size)
        assertTrue(liveTvHbbtvMenuApps(invisible).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedHtspFrameIsRejected() {
        val invalid = byteArrayOf(0, 0, 0, 6, 3, 0, 0, 0, 1, 0)
        tvhHtspDecode(DataInputStream(invalid.inputStream()))
    }
}

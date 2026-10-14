package com.ambient.tv.livetv

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class LiveTvPlaybackProtocolTest {
    private val profile = LiveTvAudioProfile(listOf("aac", "ac3", "dts"), 8)
    private fun http(reply: (Request) -> String) = LiveTvHttp("http://localhost/".toHttpUrl(), emptyMap(),
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("test").body(reply(chain.request()).toResponseBody()).build()
        }.build()) { false }

    @Test fun browserUsesDirectPlaybackAndReportsActualMethodWithoutStereoOverride() = runBlocking {
        for (method in listOf("DirectStream", "DirectPlay", "Transcode")) {
            val posts = CopyOnWriteArrayList<Pair<String, JsonObject>>()
            val transport = http { request ->
                request.body?.let { body ->
                    val buffer = Buffer(); body.writeTo(buffer)
                    posts += request.url.encodedPath to Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                }
                if (request.url.encodedPath.endsWith("PlaybackInfo")) buildJsonObject {
                    put("PlaySessionId", "session")
                    putJsonArray("MediaSources") { addJsonObject {
                        put("Id", "media"); put("LiveStreamId", "live")
                        put("SupportsDirectPlay", method == "DirectPlay")
                        put("SupportsDirectStream", method == "DirectStream")
                        put("DirectStreamUrl", "/direct.ts"); put("TranscodingUrl", "/transcode.m3u8")
                    } }
                }.toString() else "{}"
            }
            val repository = MediaBrowserLiveTvRepository(LiveTvSource("test", "jellyfin", "Test"), transport, "user", "device") { profile }
            val source = repository.liveStream(LiveTvChannel("channel", "TV", 1, null))
            val url = source.url.toHttpUrl()
            assertEquals(when (method) { "DirectStream" -> "/direct.ts"; "DirectPlay" -> "/Videos/channel/stream"; else -> "/transcode.m3u8" }, url.encodedPath)
            if (method == "DirectPlay") assertEquals("live", url.queryParameter("LiveStreamId"))
            source.session!!.report(1_000, true, false)
            val request = posts.first { it.first.endsWith("PlaybackInfo") }.second
            assertEquals(true, request["EnableDirectPlay"]!!.jsonPrimitive.boolean)
            val device = request["DeviceProfile"]!!.jsonObject
            assertEquals("aac,ac3,dts", device["DirectPlayProfiles"]!!.jsonArray.single().jsonObject["AudioCodec"]!!.jsonPrimitive.content)
            assertEquals("8", device["TranscodingProfiles"]!!.jsonArray.single().jsonObject["MaxAudioChannels"]!!.jsonPrimitive.content)
            assertTrue(posts.filter { it.first.startsWith("/Sessions/") }.all { it.second["PlayMethod"]!!.jsonPrimitive.content == method })
            repository.channelEpgEvents("channel", 1_700_000_000, 8)
        }
    }

    @Test fun nowNextQueriesOnlyTheSelectedChannelForBothProtocols() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val transport = http { request ->
            requests += request
            when (request.url.encodedPath) {
                "/media/providers" -> """{"MediaContainer":{"MediaProvider":[{"identifier":"tv.plex.providers.epg.test","parentID":"dvr"}]}}"""
                "/livetv/dvrs" -> """{"MediaContainer":{"DVR":[{"key":"dvr"}]}}"""
                "/tv.plex.providers.epg.test/lineups/dvr/channels" -> """{"MediaContainer":{"Channel":[{"id":"a","vcn":"1","gridKey":"grid-a"},{"id":"b","vcn":"2","gridKey":"grid-b"}]}}"""
                "/livetv/dvrs/dvr/channels/1/tune" -> """{"MediaContainer":{"Metadata":[{"Media":[{"uuid":"live"}]}]}}"""
                else -> "{}"
            }
        }
        val plex = PlexLiveTvRepository(LiveTvSource("test", "plex", "Test"), transport) { profile }
        val channel = plex.channels().first()
        val source = plex.liveStream(channel)
        assertEquals("aac,ac3,dca", source.url.toHttpUrl().queryParameter("audioCodec"))
        assertEquals("8", source.url.toHttpUrl().queryParameter("maxAudioChannels"))
        assertEquals("1", source.url.toHttpUrl().queryParameter("directPlay"))
        assertFalse(requests.any { it.url.encodedPath.endsWith("/grid") })
        plex.channelEpgEvents(channel.uuid, 1_700_000_000, 8)
        val grids = requests.filter { it.url.encodedPath.endsWith("/grid") }
        assertTrue(grids.isNotEmpty())
        assertTrue(grids.all { it.url.queryParameter("channelGridKey") == "grid-a" })

        val browser = MediaBrowserLiveTvRepository(LiveTvSource("browser", "emby", "Test"), transport, "user", "device") { profile }
        browser.channelEpgEvents("selected", 1_700_000_000, 8)
        assertEquals("selected", requests.last().url.queryParameter("ChannelIds"))
    }
}

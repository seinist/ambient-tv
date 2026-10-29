package com.ambient.tv.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class YouTubePlaybackPreparationTest {
    @Test fun progressiveFallbackMustContainAnAudioCodec() {
        assertTrue(youtubeFormatHasAudioAndVideo("video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""))
        assertTrue(youtubeFormatHasAudioAndVideo("video/webm; codecs=\"vp9, opus\""))
        assertFalse(youtubeFormatHasAudioAndVideo("video/mp4; codecs=\"avc1.640028\""))
        assertFalse(youtubeFormatHasAudioAndVideo("audio/mp4; codecs=\"mp4a.40.2\""))
        assertFalse(youtubeFormatHasAudioAndVideo(null))
    }
    private fun stream(live: Boolean = false) = YouTubePlayback("video", "SmartTube", uri = "https://fixture.invalid/video.mpd", isLive = live)
    private fun metadata(id: String = "video") = YouTubeMetadata(id, "Title", durationMs = 120_000, percentWatched = 25)

    @Test fun cachedDetailStartsWithoutAnotherMetadataRequestAndRetainsResume() = runBlocking {
        val result = prepareYouTubePlayback(metadata(), { error("Duplicate metadata request") }, { stream() })
        assertEquals("Title", result.title); assertEquals(30_000L, result.resumeMs)
    }
    @Test fun uncachedMetadataAndStreamResolveConcurrently() = runBlocking {
        val streamEntered = CompletableDeferred<Unit>(); val metadataEntered = CompletableDeferred<Unit>()
        val releaseMetadata = CompletableDeferred<Unit>()
        val published = CompletableDeferred<YouTubePlayback>()
        launch {
            val result = prepareYouTubePlayback(null,
                { metadataEntered.complete(Unit); streamEntered.await(); releaseMetadata.await(); metadata() },
                { streamEntered.complete(Unit); metadataEntered.await(); stream() },
            )
            published.complete(result)
        }
        val result = withTimeout(2000) { published.await() }
        assertEquals(0L, result.resumeMs)
        assertEquals("https://fixture.invalid/video.mpd", result.uri)
        releaseMetadata.complete(Unit)
        Unit
    }
    @Test fun metadataReadyBeforeTheStreamIsPublishedWithTheResume() = runBlocking {
        val result = prepareYouTubePlayback(null, { metadata() }, { stream() })
        assertEquals("Title", result.title)
        assertEquals(30_000L, result.resumeMs)
    }
    @Test fun lateResumeSeeksOnlyFromAnUnstartedPosition() {
        assertEquals(30_000L, lateYouTubeResumeTarget(isLive = false, initialStartMs = 0, resumeMs = 30_000, userSeeked = false))
        assertNull(lateYouTubeResumeTarget(isLive = false, initialStartMs = 30_000, resumeMs = 30_000, userSeeked = false))
        assertNull(lateYouTubeResumeTarget(isLive = true, initialStartMs = 0, resumeMs = 30_000, userSeeked = false))
        assertNull(lateYouTubeResumeTarget(isLive = false, initialStartMs = 0, resumeMs = 0, userSeeked = false))
        assertNull(lateYouTubeResumeTarget(isLive = false, initialStartMs = 0, resumeMs = 30_000, userSeeked = true))
        assertNull(lateYouTubeResumeTarget(isLive = false, initialStartMs = 5_000, resumeMs = 30_000, userSeeked = false))
        assertNull(lateYouTubeResumeTarget(isLive = false, initialStartMs = 0, resumeMs = 30_000,
            userSeeked = false, resumeAlreadyApplied = true))
    }
    @Test fun missingMetadataDoesNotDiscardAPlayableStream() = runBlocking {
        val result = prepareYouTubePlayback(null, { null }, { stream().copy(title = "Stream title") })
        assertEquals("Stream title", result.title); assertEquals(0L, result.resumeMs)
    }
    @Test fun liveStreamsNeverApplyHistoricalVodPosition() = runBlocking {
        assertEquals(0L, prepareYouTubePlayback(metadata(), { error("unused") }, { stream(live = true) }).resumeMs)
    }
    @Test fun metadataCacheExpiresAndRejectsPreLogoutResponses() {
        var now = 0L; val cache = YouTubePlaybackMetadataCache { now }
        val generation = cache.generation
        cache.put(metadata(), generation); assertNotNull(cache.get("video"))
        now = 120_001; assertNull(cache.get("video"))
        cache.clear(); cache.put(metadata(), generation); assertNull(cache.get("video"))
    }

    @Test fun reconnectRefreshInvalidatesOnlyItsDetailAndRejectsOlderResponses() {
        val cache = YouTubePlaybackMetadataCache { 0 }
        val previousGeneration = cache.generation
        cache.put(metadata("video"), previousGeneration)
        cache.put(metadata("other"), previousGeneration)

        cache.invalidate("video")
        assertNull(cache.get("video"))
        assertNotNull(cache.get("other"))
        cache.put(metadata("video").copy(title = "Stale response"), previousGeneration)
        assertNull(cache.get("video"))
        cache.put(metadata("video").copy(title = "Fresh response"), cache.generation)
        assertEquals("Fresh response", cache.get("video")!!.title)
    }
    @Test fun recentProgressUpdatesCachedResumeAndCacheIsBounded() {
        val cache = YouTubePlaybackMetadataCache { 0 }
        cache.put(metadata(), cache.generation); cache.updatePosition("video", 60_000)
        assertEquals(50, cache.get("video")!!.percentWatched)
        repeat(25) { cache.put(metadata("video-$it"), cache.generation) }
        assertNull(cache.get("video")); assertNotNull(cache.get("video-24"))
    }
    @Test fun failedStreamCancelsOutstandingMetadataWork() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var cancelled = false
        try {
            withTimeout(2000) {
                prepareYouTubePlayback(null,
                    { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } },
                    { entered.await(); throw IllegalStateException("unplayable") },
                )
            }
            fail("Expected stream failure")
        } catch (error: IllegalStateException) { assertEquals("unplayable", error.message) }
        assertTrue(cancelled)
    }

    @Test fun physicalDevicesUseGeneratedDashBeforeMuxedUrl() {
        assertEquals(YouTubeVodStreamPolicy.DASH_FIRST, youtubeVodStreamPolicy(isEmulator = false))
        val uri = selectYouTubePlaybackUri(
            isLive = false,
            vodPolicy = youtubeVodStreamPolicy(isEmulator = false),
            hls = "https://fixture.invalid/video.m3u8",
            generatedDash = { "file:///fixture/video.mpd" },
            direct = { "https://fixture.invalid/video.mp4" },
        )
        assertEquals("file:///fixture/video.mpd", uri)
    }

    @Test fun generatedDashBeatsMuxedOnTelevisionAndPhone() {
        val uri = selectYouTubePlaybackUri(
            isLive = false,
            vodPolicy = YouTubeVodStreamPolicy.DASH_FIRST,
            hls = "https://fixture.invalid/video.m3u8",
            generatedDash = { "file:///fixture/video.mpd" },
            direct = { "https://fixture.invalid/video.mp4" },
        )
        assertEquals("file:///fixture/video.mpd", uri)
    }

    @Test fun emptyGeneratedDashFallsBackToMuxedNotRemoteSabrUrl() {
        val uri = selectYouTubePlaybackUri(
            isLive = false,
            vodPolicy = YouTubeVodStreamPolicy.DASH_FIRST,
            hls = null,
            generatedDash = { null },
            direct = { "https://fixture.invalid/video.mp4" },
        )
        assertEquals("https://fixture.invalid/video.mp4", uri)
    }

    @Test fun emulatorRetainsProgressiveFirstWorkaround() {
        assertEquals(YouTubeVodStreamPolicy.PROGRESSIVE_FIRST, youtubeVodStreamPolicy(isEmulator = true))
        val uri = selectYouTubePlaybackUri(
            isLive = false,
            vodPolicy = YouTubeVodStreamPolicy.PROGRESSIVE_FIRST,
            hls = "https://fixture.invalid/video.m3u8",
            generatedDash = { "file:///fixture/video.mpd" },
            direct = { "https://fixture.invalid/video.mp4" },
        )
        assertEquals("https://fixture.invalid/video.mp4", uri)
    }

    @Test fun livePlaybackStillPrefersHlsRegardlessOfVodPolicy() {
        val uri = selectYouTubePlaybackUri(
            isLive = true,
            vodPolicy = YouTubeVodStreamPolicy.DASH_FIRST,
            hls = "https://fixture.invalid/live.m3u8",
            generatedDash = { error("Generated DASH should not be needed") },
            direct = { error("Direct should not be needed") },
        )
        assertEquals("https://fixture.invalid/live.m3u8", uri)
    }

    @Test fun generatedDashMustContainGooglevideoSegments() {
        assertTrue(
            youtubeGeneratedDashIsPlayable(
                """<MPD xmlns="http://www.w3.org/2001/XMLSchema-instance"><Period><AdaptationSet><Representation><BaseURL>https://rr1.googlevideo.com/videoplayback</BaseURL></Representation></AdaptationSet></Period></MPD>""",
            ),
        )
        assertFalse(youtubeGeneratedDashIsPlayable("<MPD xmlns=\"http://www.w3.org/2001/XMLSchema-instance\"><AdaptationSet></AdaptationSet></MPD>"))
        assertFalse(youtubeGeneratedDashIsPlayable("<MPD></MPD>"))
    }

    @Test fun muxedUrlFormatsAreAcceptedWithoutCodecsInMime() {
        assertEquals(
            "https://rr1.googlevideo.com/videoplayback?itag=18",
            youtubeMuxedProgressiveUrl(
                urlFormats = listOf("video/mp4" to "https://rr1.googlevideo.com/videoplayback?itag=18"),
                extraUrls = emptyList(),
            ),
        )
        assertEquals(
            "https://rr1.googlevideo.com/videoplayback?itag=18-av",
            youtubeMuxedProgressiveUrl(
                urlFormats = listOf(
                    "video/mp4" to "https://rr1.googlevideo.com/videoplayback?itag=18",
                    "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"" to "https://rr1.googlevideo.com/videoplayback?itag=18-av",
                ),
                extraUrls = emptyList(),
            ),
        )
        assertNull(
            youtubeMuxedProgressiveUrl(
                urlFormats = listOf("application/dash+xml" to "https://fixture.invalid/video.mpd"),
                extraUrls = listOf("https://fixture.invalid/video.m3u8"),
            ),
        )
    }

    @Test fun vlcUsesMuxedUrlInsteadOfYouTubeDash() {
        assertEquals(
            "https://googlevideo.example/videoplayback",
            youtubeVlcPlaybackUri(
                uri = "https://fixture.invalid/video.mpd",
                mimeType = "application/dash+xml",
                progressiveUri = "https://googlevideo.example/videoplayback",
            ),
        )
        assertNull(
            youtubeVlcPlaybackUri(
                uri = "https://fixture.invalid/video.mpd",
                mimeType = "application/dash+xml",
                progressiveUri = null,
            ),
        )
        assertEquals(
            "https://googlevideo.example/videoplayback",
            youtubeVlcPlaybackUri("https://googlevideo.example/videoplayback", null, null),
        )
    }

    @Test fun mpvGetsTheBestFittingVideoAndOriginalAudio() {
        val formats = listOf(
            YouTubeAdaptiveFormat("video/mp4; codecs=\"avc1.640028\"", "https://v/1080avc", 1080, 4_000_000),
            YouTubeAdaptiveFormat("video/webm; codecs=\"vp9\"", "https://v/1080vp9", 1080, 3_000_000),
            YouTubeAdaptiveFormat("video/webm; codecs=\"vp9\"", "https://v/2160vp9", 2160, 20_000_000),
            YouTubeAdaptiveFormat("video/mp4; codecs=\"av01.0.08M.08\"", "https://v/1080av1", 1080, 2_000_000),
            YouTubeAdaptiveFormat("video/mp4; codecs=\"avc1\"", "https://v/otf", 720, isOtf = true),
            YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://a/dub", bitrate = 256_000, xtags = "lang=de;acont=dubbed"),
            YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://a/orig", bitrate = 128_000, xtags = "lang=en;acont=original"),
            YouTubeAdaptiveFormat("audio/webm; codecs=\"opus\"", "https://a/opus", bitrate = 160_000),
        )
        // The target's codec wins at the same quality, as in SmartTube.
        assertEquals("https://v/1080avc" to "https://a/orig", youtubeAdaptivePair(formats, YouTubeVideoPreset(1080, 30, "avc")))
        assertEquals("https://v/1080vp9" to "https://a/orig", youtubeAdaptivePair(formats, YouTubeVideoPreset(1080, 60, "vp9")))
        assertEquals("https://v/2160vp9" to "https://a/orig", youtubeAdaptivePair(formats, YouTubeVideoPreset(2160, 60, "vp9")))
        assertNull(youtubeAdaptivePair(formats.filter { it.mimeType!!.startsWith("video") }, YouTubeVideoPreset(1080, 60, "vp9")))
        assertEquals("https://v/1080av1" to "https://a/opus",
            youtubeAdaptivePair(listOf(formats[3], formats[7]), YouTubeVideoPreset(1080, 60, "vp9")))
    }

    @Test fun mpvEdlEscapesUrlsByLength() {
        val video = "https://v.example/videoplayback?a=1,2;b=3"
        val edl = youtubeMpvEdl(video, "https://a/x")
        assertEquals("edl://!new_stream;!no_clip;!no_chapters;%${video.length}%$video;!new_stream;!no_clip;!no_chapters;%11%https://a/x", edl)
    }
    @Test fun externalPlayersGetRelayedStreamsInsteadOfTheLocalMpd() {
        val relay: (String) -> String = { "http://127.0.0.1:1/" + it.substringAfterLast('/') }
        val vod = YouTubePlayback(
            "id", "t", uri = "file:///cache/youtube_mpd/id.mpd", mimeType = "application/dash+xml",
            adaptiveVideoUri = "https://g/v", adaptiveAudioUri = "https://g/a",
            headers = mapOf("User-Agent" to "x"),
        )
        val mpv = youtubeMpvSource(vod, relay)!!
        assertEquals(youtubeMpvEdl("http://127.0.0.1:1/v", "http://127.0.0.1:1/a"), mpv.uri)
        assertTrue(mpv.relayed)
        val vlc = youtubeVlcSource(vod, relay)!!
        assertEquals("http://127.0.0.1:1/v", vlc.uri)
        assertEquals("http://127.0.0.1:1/a", vlc.audioUri)

        // No adaptive pair: the muxed file, relayed.
        val muxedOnly = vod.copy(adaptiveVideoUri = null, adaptiveAudioUri = null, progressiveUri = "https://g/m")
        assertEquals("http://127.0.0.1:1/m", youtubeMpvSource(muxedOnly, relay)!!.uri)
        assertEquals(YouTubeExternalSource("http://127.0.0.1:1/m"), youtubeVlcSource(muxedOnly, relay))

        // Nothing but the local MPD: VLC has no source (falls back), MPV is never handed file://.
        val mpdOnly = vod.copy(adaptiveVideoUri = null, adaptiveAudioUri = null)
        assertNull(youtubeVlcSource(mpdOnly, relay))
        assertNull(youtubeMpvSource(mpdOnly, relay))

        // Live HLS stays with MPV's own HLS client and its headers; VLC never gets a manifest.
        val live = YouTubePlayback("id", "t", uri = "https://m/index.m3u8", mimeType = "application/x-mpegURL", isLive = true)
        assertEquals(YouTubeExternalSource("https://m/index.m3u8", relayed = false), youtubeMpvSource(live, relay))
        assertNull(youtubeVlcSource(live, relay))
    }
    @Test fun vlcHdrMetadataBelongsToTheOpenedVideoNotAnUnusedAdaptiveTrack() {
        val hdrMime = "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.01\""
        val adaptive = YouTubePlayback("id", "t", uri = "https://m/index.mpd", mimeType = "application/dash+xml",
            progressiveUri = "https://g/sdr", adaptiveVideoUri = "https://g/hdr", adaptiveAudioUri = "https://g/audio",
            adaptiveFormats = listOf(YouTubeAdaptiveFormat(url = "https://g/hdr", mimeType = hdrMime)))
        assertEquals("https://g/hdr", youtubeVlcVideoUri(adaptive))
        assertEquals(hdrMime, youtubeVlcVideoMimeType(adaptive))
        val noAudio = adaptive.copy(adaptiveAudioUri = null)
        assertEquals("https://g/sdr", youtubeVlcVideoUri(noAudio))
        assertNull(youtubeVlcVideoMimeType(noAudio))
        assertEquals("https://g/sdr", youtubeVlcSource(noAudio) { it }!!.uri)
        assertNull(youtubeVlcVideoMimeType(adaptive.copy(isLive = true, progressiveUri = null)))
    }

}

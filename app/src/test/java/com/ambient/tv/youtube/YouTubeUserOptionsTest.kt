package com.ambient.tv.youtube

import org.junit.Assert.*
import org.junit.Test

class YouTubeUserOptionsTest {
    private val short = YouTubeItem("s", "S", isShorts = true)
    private val live = YouTubeItem("l", "L", isLive = true)
    private val video = YouTubeItem("v", "V")

    @Test fun shortsAreHiddenEverywhereExceptTheShortsSection() {
        val rows = listOf(YouTubeRow("r", listOf(short, video)))
        assertEquals(listOf("v"), youtubeFilterRows(rows, "feed:home", true, false).single().items.map { it.videoId })
        assertEquals(listOf("s", "v"), youtubeFilterRows(rows, "feed:shorts", true, false).single().items.map { it.videoId })
        assertSame(rows, youtubeFilterRows(rows, "feed:home", false, false))
    }

    @Test fun liveIsHiddenOnlyInSubscriptionsAndEmptiedRowsDisappear() {
        val rows = listOf(YouTubeRow("live", listOf(live)), YouTubeRow("r", listOf(video)))
        assertEquals(listOf("r"), youtubeFilterRows(rows, "feed:subscriptions", false, true).map { it.id })
        assertEquals(2, youtubeFilterRows(rows, "feed:home", false, true).size)
    }

    @Test fun automaticCaptionSetsAreRemovedFromTheManifest() {
        val mpd = """<MPD><AdaptationSet id="1" mimeType="video/mp4"><Representation/></AdaptationSet>""" +
            """<AdaptationSet id="2"><Role value="subtitle"/><Representation><BaseURL>https://x/a?v=1&amp;kind=asr</BaseURL></Representation></AdaptationSet>""" +
            """<AdaptationSet id="3"><Role value="subtitle"/><Representation><BaseURL>https://x/b</BaseURL></Representation></AdaptationSet></MPD>"""
        val result = youtubeMpdWithoutCaptions(mpd, listOf("https://x/a?v=1&kind=asr"))
        assertFalse(result.contains("id=\"2\""))
        assertTrue(result.contains("id=\"1\"") && result.contains("id=\"3\""))
    }

    @Test fun sameLanguageComparesBaseTags() {
        assertTrue(youtubeSameLanguage("de-DE", "de"))
        assertFalse(youtubeSameLanguage("en", "de"))
        assertFalse(youtubeSameLanguage(null, "de"))
    }
}

class YouTubePreferredAudioTest {
    @org.junit.Test fun originalKeepsTheOriginalTrack() {
        org.junit.Assert.assertNull(youtubePreferredAudioLanguage("original"))
        org.junit.Assert.assertEquals("de", youtubePreferredAudioLanguage("de"))
    }

    @org.junit.Test fun startupLanguageChoosesOriginalOrRequestedNonMachineDub() {
        val formats = listOf(
            YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://a/de-auto",
                xtags = "lang=de;acont=dubbed-auto"),
            YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://a/en",
                xtags = "lang=en;acont=original"),
            YouTubeAdaptiveFormat("audio/mp4; codecs=\"mp4a.40.2\"", "https://a/fr",
                xtags = "lang=fr;acont=dubbed"),
        )
        org.junit.Assert.assertEquals("en", youtubeStartupAudioLanguage(formats, "original"))
        org.junit.Assert.assertEquals("fr", youtubeStartupAudioLanguage(formats, "fr"))
        // A requested language offered only as a machine dub falls back to original.
        org.junit.Assert.assertEquals("en", youtubeStartupAudioLanguage(formats, "de"))
    }
}

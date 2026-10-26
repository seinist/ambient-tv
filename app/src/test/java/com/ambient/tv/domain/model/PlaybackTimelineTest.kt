package com.ambient.tv.domain.model

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun chapterBoundariesAndInvalidEntriesAreNormalized() {
        val timeline = PlaybackTimeline(listOf(VideoChapter(20_000, "B"), VideoChapter(-1), VideoChapter(0, "A", endMs = 20_000), VideoChapter(20_000), VideoChapter(40_000))).normalized(40_000)
        assertEquals(listOf(0L, 20_000L), timeline.chapters.map { it.startMs })
        assertEquals("A", timeline.chapterAt(19_999)?.title); assertEquals("B", timeline.chapterAt(20_000)?.title)
    }
    @Test fun chapterGapsDoNotClaimAnUnrelatedChapter() {
        val timeline = PlaybackTimeline(listOf(VideoChapter(5_000, endMs = 10_000), VideoChapter(20_000)))
        assertNull(timeline.chapterAt(0)); assertNull(timeline.chapterAt(15_000))
    }
    @Test fun verticalStepsUseAdjacentChaptersAndStayBounded() {
        val timeline = PlaybackTimeline(listOf(VideoChapter(0), VideoChapter(20_000), VideoChapter(50_000))).normalized()
        assertEquals(25_000L, timeline.chapterStepDelta(25_000, 1))
        assertEquals(-25_000L, timeline.chapterStepDelta(25_000, -1))
        assertEquals(30_000L, timeline.chapterStepDelta(20_000, 1))
        assertEquals(-20_000L, timeline.chapterStepDelta(20_000, -1))
        assertEquals(0L, timeline.chapterStepDelta(55_000, 1))
        assertEquals(-5_000L, timeline.chapterStepDelta(5_000, -1))
        assertEquals(0L, timeline.chapterStepDelta(0, -1))
        assertNull(PlaybackTimeline().chapterStepDelta(25_000, 1))
        val lateFirst = PlaybackTimeline(listOf(VideoChapter(10_000)))
        assertEquals(5_000L, lateFirst.chapterStepDelta(5_000, 1))
        assertEquals(0L, lateFirst.chapterStepDelta(5_000, -1))
    }

    @Test fun plexPreviewUsesMillisecondsAndRetainsQuery() {
        val preview = TimelinePreview.Plex("https://server/library/parts/9/indexes/sd/__TIME__?X-Plex-Token=test")
        assertTrue(preview.frameAt(123_456).url.contains("/122000?")); assertTrue(preview.frameAt(-5).url.contains("/0?"))
        assertTrue(preview.frameAt(123_456).url.endsWith("Token=test"))
    }
    private fun sprite(count: Long = 17) = TimelinePreview.Sprite(160, 90, 3, 2, 2_000, count, urlTemplate = "https://server/__INDEX__.jpg?token=test")
    @Test fun spriteAdvancesAcrossColumnsRowsAndSheets() {
        assertEquals(PreviewFrame("https://server/0.jpg?token=test", 0, 0, 160, 90, 480, 180), sprite().frameAt(0))
        assertEquals(320, sprite().frameAt(4_000)?.x); assertEquals(90, sprite().frameAt(6_000)?.y)
        assertEquals("https://server/1.jpg?token=test", sprite().frameAt(12_000)?.url)
        assertEquals(0, sprite().frameAt(12_000)?.x)
    }
    @Test fun lastIncompleteSheetClampsToLastRealThumbnail() {
        assertEquals(sprite().frameAt(32_000), sprite().frameAt(Long.MAX_VALUE))
        assertEquals(160, sprite().frameAt(Long.MAX_VALUE)?.x); assertEquals(90, sprite().frameAt(Long.MAX_VALUE)?.y)
    }
    @Test fun invalidOrExcessivelyLargeSpritesAreIgnored() {
        assertNull(sprite().copy(intervalMs = 0).frameAt(0)); assertNull(sprite().copy(count = 0).frameAt(0))
        assertNull(sprite().copy(width = 1024, height = 1024, columns = 20, rows = 20).frameAt(0))
    }
    @Test fun storyboardTemplateIsBoundedByVideoDuration() {
        val timeline = PlaybackTimeline(preview = sprite(Long.MAX_VALUE)).normalized(9_001)
        assertEquals(5L, (timeline.preview as TimelinePreview.Sprite).count)
        assertEquals(sprite().frameAt(8_000), timeline.preview.frameAt(9_001))
    }
    @Test fun missingSheetHasNoInventedFallbackImage() {
        assertNull(sprite().copy(urlTemplate = null, urls = listOf("one.jpg")).frameAt(14_000))
    }
    @Test fun scrubPreviewUsesTrickplayThenChapterImage() {
        val chapter = VideoChapter(4_000, "Arrival", "https://server/chapter.jpg")
        val withSprite = PlaybackTimeline(listOf(chapter), sprite())
        assertEquals(sprite().frameAt(5_000), withSprite.previewFrameAt(5_000))
        assertEquals(PreviewFrame("https://server/chapter.jpg"), PlaybackTimeline(listOf(chapter)).previewFrameAt(5_000))
        assertNull(PlaybackTimeline(listOf(VideoChapter(4_000, "Arrival", "  "))).previewFrameAt(5_000))
    }
    @Test fun chapterCardsPreferSeekPreviewOverTheirOwnImage() {
        val chapter = VideoChapter(4_000, "Arrival", "https://server/chapter.jpg")
        assertEquals(sprite().frameAt(4_000), PlaybackTimeline(listOf(chapter), sprite()).chapterFrame(chapter))
        assertEquals(PreviewFrame("https://server/chapter.jpg"), PlaybackTimeline(listOf(chapter)).chapterFrame(chapter))
        val blank = VideoChapter(4_000, "Arrival", "")
        assertEquals(sprite().frameAt(4_000), PlaybackTimeline(listOf(blank), sprite()).chapterFrame(blank))
    }
    @Test fun generatedChapterNamesAreNotShownAsTitles() {
        listOf("Chapter 3", "kapitel 03", "Ch. 2", "12", "00:12:00.000", "1:05", " ", null)
            .forEach { assertNull(it, VideoChapter(0, it).meaningfulTitle) }
        assertEquals("Chapter 3: The Heist", VideoChapter(0, " Chapter 3: The Heist ").meaningfulTitle)
        assertEquals("Intro", VideoChapter(0, "Intro").meaningfulTitle)
    }
}

package com.ambient.tv.data.subtitles

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTimingTest {
    /** A speech-like cue timeline: 1–5 s lines with 0.3–8 s gaps over about 40 minutes. */
    private fun dialogue(seed: Int): List<CueSpan> {
        val random = Random(seed)
        var t = 5_000L
        return List(400) {
            val start = t + random.nextLong(300, 8_000)
            val end = start + random.nextLong(1_000, 5_000)
            t = end
            CueSpan(start, end)
        }
    }

    @Test fun findsAPlainShift() {
        val reference = dialogue(1)
        val target = reference.map { CueSpan(it.startMs + 3_200, it.endMs + 3_200) }
        val sync = findSubtitleSync(reference, target)!!
        assertEquals(1.0, sync.scale, 0.0)
        assertTrue("offset ${sync.offsetMs}", abs(sync.offsetMs + 3_200) <= 20)
    }

    @Test fun findsAFrameRateMismatch() {
        val reference = dialogue(2)
        val factor = 23.976 / 25.0
        val target = reference.map { CueSpan((it.startMs * factor).roundToLong() - 1_500, (it.endMs * factor).roundToLong() - 1_500) }
        val sync = findSubtitleSync(reference, target)!!
        assertEquals(25.0 / 23.976, sync.scale, 1e-9)
        assertTrue("offset ${sync.offsetMs}", abs(sync.offsetMs - (1_500 * 25.0 / 23.976).roundToLong()) <= 30)
    }

    @Test fun unrelatedSubtitlesDoNotMatch() {
        assertNull(findSubtitleSync(dialogue(3), dialogue(4)))
    }

    @Test fun parsesAndRetimesSrtVttAndAss() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello\n\n2\n00:01:00,000 --> 00:01:01,000\nBye\n"
        assertEquals(listOf(CueSpan(1_000, 2_500), CueSpan(60_000, 61_000)), parseCueSpans(srt))
        val moved = retimeSubtitle(srt, SubtitleSync(offsetMs = -500, scale = 1.0, score = 1.0))
        assertTrue(moved, moved.contains("00:00:00,500 --> 00:00:02,000"))
        assertTrue(moved, moved.contains("00:00:59,500 --> 00:01:00,500"))

        val vtt = "WEBVTT\n\n00:05.000 --> 00:06.000 line:90%\nHi\n"
        assertEquals(listOf(CueSpan(5_000, 6_000)), parseCueSpans(vtt))
        val vttMoved = retimeSubtitle(vtt, SubtitleSync(1_000, 1.0, 1.0))
        assertTrue(vttMoved, vttMoved.contains("00:00:06.000 --> 00:00:07.000 line:90%"))

        val ass = "[Events]\nDialogue: 0,0:00:10.50,0:00:12.00,Default,,0,0,0,,Text, with comma\n"
        assertEquals(listOf(CueSpan(10_500, 12_000)), parseCueSpans(ass))
        val assMoved = retimeSubtitle(ass, SubtitleSync(-10_600, 1.0, 1.0))
        assertTrue(assMoved, assMoved.contains("Dialogue: 0,0:00:00.00,0:00:01.40,Default,,0,0,0,,Text, with comma"))
    }

    @Test fun overlappingCuesAreMerged() {
        assertEquals(listOf(CueSpan(0, 3_000), CueSpan(4_000, 5_000)),
            mergeSpans(listOf(CueSpan(1_000, 3_000), CueSpan(0, 2_000), CueSpan(4_000, 5_000))))
    }
}

package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekBarConfirmTest {
    @Test
    fun idleOkTogglesPlayback() {
        val confirm = confirmSeekBarOk(pendingTargetMs = null, previewMs = null, positionMs = 40_000)
        assertTrue(confirm.togglePlayback)
        assertFalse(confirm.resumePlayback)
        assertNull(confirm.seekDeltaMs)
    }

    @Test
    fun previewAfterSeekResumesWithoutAnotherJump() {
        val confirm = confirmSeekBarOk(pendingTargetMs = null, previewMs = 90_000, positionMs = 90_000)
        assertTrue(confirm.resumePlayback)
        assertFalse(confirm.togglePlayback)
        assertNull(confirm.seekDeltaMs)
    }

    @Test
    fun pendingScrubCommitsThatPositionAndResumes() {
        val confirm = confirmSeekBarOk(pendingTargetMs = 75_000, previewMs = 75_000, positionMs = 40_000)
        assertEquals(35_000L, confirm.seekDeltaMs)
        assertTrue(confirm.resumePlayback)
        assertFalse(confirm.togglePlayback)
    }

    @Test
    fun pointerPositionMapsAndClampsToTimeline() {
        assertEquals(0L, seekPositionFromPointer(-10f, 100, 120_000L))
        assertEquals(60_000L, seekPositionFromPointer(50f, 100, 120_000L))
        assertEquals(120_000L, seekPositionFromPointer(150f, 100, 120_000L))
        assertEquals(0L, seekPositionFromPointer(50f, 0, 120_000L))
    }
}

package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaVersionSelectionTest {
    @Test fun selectsLargestVersionThatFitsDisplay() {
        val versions = listOf(version("720p", 1280, 720, 4_000), version("1080p", 1920, 1080, 8_000), version("4k", 3840, 2160, 25_000))
        assertEquals(1, MediaVersionSelection.selectIndex(versions, 0, 1920, 1080))
        assertEquals(2, MediaVersionSelection.selectIndex(versions, 0, 3840, 2160))
    }

    @Test fun selectsSmallestOversizeVersionWhenNothingFits() {
        val versions = listOf(version("1080p", 1920, 1080, 8_000), version("4k", 3840, 2160, 25_000))
        assertEquals(0, MediaVersionSelection.selectIndex(versions, 1, 1280, 720))
    }

    @Test fun usesResolutionLabelWhenDimensionsAreMissing() {
        val versions = listOf(version("720p", null, null, 4_000), version("1080p", null, null, 8_000))
        assertEquals(1, MediaVersionSelection.selectIndex(versions, 0, 1920, 1080))
    }

    @Test fun unknownVersionsFallBackToRequestedIndex() {
        val versions = listOf(version(null, null, null, null), version(null, null, null, null))
        assertEquals(1, MediaVersionSelection.selectIndex(versions, 1, 1920, 1080))
    }

    @Test fun explicitChoiceCanPlayEitherFileWithIdenticalResolutionDespiteAuto() {
        val versions = listOf(
            version("1080p", 1920, 1080, 6_000).copy(
                streamUrl = "https://example.invalid/first", sourceRatingKey = "movie", sourceMediaIndex = 0,
            ),
            version("1080p", 1920, 1080, 12_000).copy(
                streamUrl = "https://example.invalid/second", sourceRatingKey = "movie", sourceMediaIndex = 1,
            ),
        )
        // Ordinary Play still selects the best display-matching file.
        assertEquals(1, MediaVersionSelection.selectIndex(versions, 0, 1920, 1080))
        // Explicit source selection is independent of resolution and auto preference.
        assertEquals(0, MediaVersionSelection.explicitIndex(versions, 0, "movie", "movie", 0, null))
        assertEquals(1, MediaVersionSelection.explicitIndex(versions, 1, "movie", "movie", 1, null))
    }

    @Test fun explicitSourceIdentitySurvivesInsertionAndNeverFallsBackToAnotherFile() {
        val first = version("1080p", 1920, 1080, 5_000).copy(
            streamUrl = "https://example.invalid/a", sourceRatingKey = "movie", sourceMediaIndex = 0, sourceId = "a",
        )
        val second = first.copy(streamUrl = "https://example.invalid/b", sourceMediaIndex = 1, sourceId = "b")
        val inserted = first.copy(streamUrl = "https://example.invalid/new", sourceMediaIndex = 2, sourceId = "new")
        val refreshed = listOf(inserted, second, first)
        assertEquals(2, MediaVersionSelection.explicitIndex(refreshed, 0, "movie", "movie", 0, "a"))
        assertEquals(1, MediaVersionSelection.explicitIndex(refreshed, 1, "movie", "movie", 1, "b"))
        assertEquals(-1, MediaVersionSelection.explicitIndex(listOf(second), 0, "movie", "movie", 0, "a"))
    }

    private fun version(resolution: String?, width: Int?, height: Int?, bitrate: Int?) = MediaVersion(
        streamUrl = null,
        resolution = resolution,
        videoCodec = null,
        audioCodec = null,
        container = null,
        bitrateKbps = bitrate,
        width = width,
        height = height,
    )
}

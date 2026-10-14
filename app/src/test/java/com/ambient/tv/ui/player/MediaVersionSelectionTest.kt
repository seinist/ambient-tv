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

package com.ambient.tv.ui.detail

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class EditVersionsTest {
    @Test fun everyLinkedItemIsOneVersionWithItsOwnFiles() {
        val item = MediaItem(
            ratingKey = "1", title = "Dune", type = "movie", index = null, thumbUrl = null, artUrl = null,
            year = 2021, summary = null, tagline = null, durationMs = null, viewOffsetMs = null,
            parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
            contentRating = null, rating = null, studio = null,
            linkedRatingKeys = listOf("1", "2", "3"),
            mediaVersions = listOf(
                MediaVersion(null, "4k", null, null, null, fileName = "Dune.2021.mkv"),
                MediaVersion(null, "1080", null, null, null, sourceRatingKey = "2", fileName = "Dune.1984.mkv"),
            ),
        )
        val versions = editVersions(item)
        assertEquals(listOf("1", "2", "3"), versions.map { it.ratingKey })
        assertEquals(listOf("Dune.2021.mkv", "Dune.1984.mkv", null), versions.map { it.fileName })
        assertEquals(null, versions[2].details)
    }
}

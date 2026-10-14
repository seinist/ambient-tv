package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.CastMember
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDeduplicatorTest {
    @Test fun mergesPhysicalVersionsAndRatingKeysForSameGuid() {
        val hd = item("1", "imdb://tt123", 2_000L, 10_000L, "1080p")
        val uhd = item("2", "imdb://tt123", 0L, 10_000L, "4k")

        val merged = ContentDeduplicator.merge(listOf(hd, uhd)).single()

        assertEquals(setOf("1", "2"), merged.allRatingKeys.toSet())
        assertEquals(setOf("1080p", "4k"), merged.mediaVersions.mapNotNull { it.resolution }.toSet())
        assertEquals(2_000L, merged.viewOffsetMs)
    }

    @Test fun sameTitleWithDifferentStableGuidDoesNotMerge() {
        val first = item("1", "imdb://tt111", 0L, 10_000L, "1080p")
        val second = item("2", "imdb://tt222", 0L, 10_000L, "1080p")
        assertEquals(2, ContentDeduplicator.merge(listOf(first, second)).size)
    }

    @Test fun watchedDuplicateIsExposedAsWatchedWhenNoCopyIsInProgress() {
        val watched = item("1", "imdb://tt123", 10_000L, 10_000L, "1080p", viewCount = 1)
        val unwatched = item("2", "imdb://tt123", 0L, 10_000L, "4k")
        assertTrue(ContentDeduplicator.merge(listOf(watched, unwatched)).single().isWatched)
    }

    @Test fun mergedVersionsDedupeRepeatedActors() {
        val hd = item("1", "imdb://tt123", 0L, 10_000L, "1080p").copy(
            cast = listOf(CastMember("9", "Ada", "Lead", null), CastMember("9", "Ada", "Cameo", null)),
        )
        val uhd = item("2", "imdb://tt123", 0L, 10_000L, "4k").copy(
            cast = listOf(CastMember("9", "Ada", "Lead", null), CastMember("8", "Bea", "Support", null)),
        )
        assertEquals(listOf("Ada", "Bea"), ContentDeduplicator.merge(listOf(hd, uhd)).single().cast.map { it.name })
    }

    @Test fun mergedItemKeepsThemeMusicMissingFromRepresentative() {
        // A library-list copy without the theme is merged with the detail metadata that has it.
        val listed = item("1", "imdb://tt123", 2_000L, 10_000L, "1080p")
        val detailed = item("1", "imdb://tt123", 0L, 10_000L, "1080p").copy(themeMusicUrl = "http://local/theme")
        assertEquals("http://local/theme", ContentDeduplicator.mergeGroup(listOf(listed, detailed)).themeMusicUrl)
    }

    @Test fun mergedItemKeepsTitleLogoMissingFromRepresentative() {
        val listed = item("1", "imdb://tt123", 2_000L, 10_000L, "1080p")
        val detailed = item("1", "imdb://tt123", 0L, 10_000L, "1080p").copy(logoUrl = "http://local/logo.png")
        assertEquals("http://local/logo.png", ContentDeduplicator.mergeGroup(listOf(listed, detailed)).logoUrl)
    }

    @Test fun listPlaceholderVersionIsDroppedOnceDetailVersionIsMerged() {
        // Jellyfin/Emby list items carry only a resolution placeholder without a stream URL.
        val listed = item("1", "imdb://tt123", 0L, 10_000L, "1080p").copy(
            mediaVersions = listOf(MediaVersion(null, "1080p", null, null, null, width = 1920, height = 1080)),
        )
        val detailed = item("1", "imdb://tt123", 0L, 10_000L, "1080p")
        val versions = ContentDeduplicator.mergeGroup(listOf(listed, detailed)).mediaVersions
        assertEquals(listOf("http://local/1"), versions.map { it.streamUrl })
    }

    private fun item(
        ratingKey: String,
        guid: String,
        offset: Long,
        duration: Long,
        resolution: String,
        viewCount: Int = 0,
    ) = MediaItem(
        ratingKey = ratingKey,
        title = "Example",
        type = "movie",
        index = null,
        thumbUrl = null,
        artUrl = null,
        year = 2024,
        summary = null,
        tagline = null,
        durationMs = duration,
        viewOffsetMs = offset,
        parentTitle = null,
        parentRatingKey = null,
        grandparentTitle = null,
        grandparentRatingKey = null,
        contentRating = null,
        rating = null,
        studio = null,
        viewCount = viewCount,
        guid = guid,
        guids = listOf(guid),
        mediaVersions = listOf(
            MediaVersion(
                streamUrl = "http://local/$ratingKey",
                resolution = resolution,
                videoCodec = "h264",
                audioCodec = "aac",
                container = "mkv",
                sourceRatingKey = ratingKey,
            ),
        ),
    )
}

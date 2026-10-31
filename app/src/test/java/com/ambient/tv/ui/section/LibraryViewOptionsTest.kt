package com.ambient.tv.ui.section

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewOptionsTest {
    @Test fun watchFilterUsesShowEpisodeCounts() {
        val items = listOf(
            item("unwatched", leafCount = 10, viewedLeafCount = 0, type = "show"),
            item("progress", leafCount = 10, viewedLeafCount = 4, type = "show"),
            item("watched", leafCount = 10, viewedLeafCount = 10, type = "show"),
        )

        assertEquals(listOf("unwatched"), titles(items, watch = SectionFilter.UNWATCHED))
        assertEquals(listOf("progress"), titles(items, watch = SectionFilter.IN_PROGRESS))
        assertEquals(listOf("watched"), titles(items, watch = SectionFilter.WATCHED))
    }

    @Test fun movieWatchFilterUsesProgressAndViewCount() {
        val items = listOf(
            item("fresh", viewCount = 0),
            item("halfway", durationMs = 100_000, viewOffsetMs = 40_000),
            item("done", viewCount = 2),
        )

        assertEquals(listOf("fresh"), titles(items, watch = SectionFilter.UNWATCHED))
        assertEquals(listOf("halfway"), titles(items, watch = SectionFilter.IN_PROGRESS))
        assertEquals(listOf("done"), titles(items, watch = SectionFilter.WATCHED))
    }

    @Test fun qualityAndHdrFiltersMatchAnyVersion() {
        val items = listOf(
            item("uhd", versions = listOf(version(resolution = "4k", height = 2160))),
            item("hd", versions = listOf(version(resolution = "1080", height = 1080))),
            item("sd", versions = listOf(version(resolution = "480", height = 480))),
            item("hdr", versions = listOf(version(resolution = "1080", height = 1080, hdr = "HDR10"))),
        )

        assertEquals(listOf("uhd"), titles(items, quality = SectionQualityFilter.UHD))
        assertEquals(listOf("hd", "hdr"), titles(items, quality = SectionQualityFilter.HD))
        assertEquals(listOf("sd"), titles(items, quality = SectionQualityFilter.SD))
        assertEquals(listOf("hdr"), titles(items, quality = SectionQualityFilter.HDR))
    }

    @Test fun genreDecadeAndContentRatingCombine() {
        val items = listOf(
            item("keep", year = 2014, genres = listOf("Action", "Sci-Fi"), contentRating = "PG-13"),
            item("wrong-genre", year = 2015, genres = listOf("Drama"), contentRating = "PG-13"),
            item("wrong-decade", year = 2002, genres = listOf("Action"), contentRating = "PG-13"),
            item("wrong-rating", year = 2018, genres = listOf("Action"), contentRating = "R"),
        )

        assertEquals(
            listOf("keep"),
            titles(
                items,
                filters = LibraryFilters(genre = "action", decade = 2010, contentRating = "PG-13"),
            ),
        )
    }

    @Test fun titleSortHonorsDirection() {
        val items = listOf(item("Bravo"), item("alpha"), item("Charlie"))

        assertEquals(listOf("alpha", "Bravo", "Charlie"), titles(items, sort = SectionSort.TITLE, order = SectionSortOrder.ASCENDING))
        assertEquals(listOf("Charlie", "Bravo", "alpha"), titles(items, sort = SectionSort.TITLE, order = SectionSortOrder.DESCENDING))
    }

    @Test fun alphabetBarReflectsActualAscendingAndDescendingGridOrder() {
        val items = listOf(item("Zulu"), item("80s"), item("Bravo"), item("alpha"), item("Älpler"))
        val ascending = applyLibraryViewOptions(items, SectionSort.TITLE, SectionSortOrder.ASCENDING, LibraryFilters())
        val descending = applyLibraryViewOptions(items, SectionSort.TITLE, SectionSortOrder.DESCENDING, LibraryFilters())
        assertEquals(
            ascending.mapNotNull { libraryIndexLetter(it.title) }.distinct(),
            libraryAlphabetLetters(ascending),
        )
        assertEquals(
            descending.mapNotNull { libraryIndexLetter(it.title) }.distinct(),
            libraryAlphabetLetters(descending),
        )
        assertEquals('#', libraryAlphabetLetters(ascending).first())
        assertEquals('#', libraryAlphabetLetters(descending).last())
        assertEquals(libraryAlphabetLetters(ascending).reversed(), libraryAlphabetLetters(descending))
    }

    @Test fun selectedAlphabetLetterFollowsLastFocusedTileNotFirstVisibleTile() {
        val items = listOf(item("80s"), item("Bravo"), item("Zulu"))
        assertEquals('B', libraryFocusedAlphabetLetter(items, "Bravo"))
        assertEquals('Z', libraryFocusedAlphabetLetter(items, "Zulu"))
        assertEquals('#', libraryFocusedAlphabetLetter(items, "gone"))
        assertEquals(null, libraryFocusedAlphabetLetter(emptyList(), null))
    }

    @Test fun recentlyAddedAndRatingSortDescendingByDefault() {
        val items = listOf(
            item("old", addedAt = 10L, rating = 4.0),
            item("new", addedAt = 30L, rating = 8.2),
            item("mid", addedAt = 20L, audienceRating = 9.1),
        )

        assertEquals(
            listOf("new", "mid", "old"),
            titles(items, sort = SectionSort.RECENTLY_ADDED, order = defaultSortOrder(SectionSort.RECENTLY_ADDED)),
        )
        assertEquals(
            listOf("mid", "new", "old"),
            titles(items, sort = SectionSort.RATING, order = defaultSortOrder(SectionSort.RATING)),
        )
    }

    @Test fun facetsExposeOnlyPresentLibraryValues() {
        val facets = libraryFilterFacets(
            listOf(
                item("a", year = 1999, genres = listOf("Drama"), contentRating = "R", versions = listOf(version("4k", 2160, "Dolby Vision"))),
                item("b", year = 2011, genres = listOf("Action"), contentRating = "PG"),
                item("c", year = 2018, genres = listOf("Drama")),
            ),
        )

        assertEquals(listOf("Action", "Drama"), facets.genres)
        assertEquals(listOf(2010, 1990), facets.decades)
        assertEquals(listOf("PG", "R"), facets.contentRatings)
        assertTrue(SectionQualityFilter.UHD in facets.qualities)
        assertTrue(SectionQualityFilter.HDR in facets.qualities)
        assertTrue(SectionQualityFilter.HD !in facets.qualities)
    }

    @Test fun recommendationViewPreservesAllModernModesAndOnlyNormalizesLegacyAliases() {
        assertEquals(SectionViewMode.POSTER, recommendationViewMode(SectionViewMode.POSTER))
        assertEquals(SectionViewMode.LANDSCAPE, recommendationViewMode(SectionViewMode.LANDSCAPE))
        assertEquals(SectionViewMode.LIST, recommendationViewMode(SectionViewMode.LIST))
        assertEquals(SectionViewMode.INFO_LIST, recommendationViewMode(SectionViewMode.INFO_LIST))
        assertEquals(SectionViewMode.WALL, recommendationViewMode(SectionViewMode.WALL))
        assertEquals(SectionViewMode.CINEMATIC, recommendationViewMode(SectionViewMode.CINEMATIC))
        assertEquals(SectionViewMode.POSTER, recommendationViewMode(SectionViewMode.GRID))
        assertEquals(SectionViewMode.LANDSCAPE, recommendationViewMode(SectionViewMode.ROWS))
        assertEquals(SectionViewMode.POSTER, recommendationViewMode(null))
    }

    private fun titles(
        items: List<MediaItem>,
        sort: SectionSort = SectionSort.TITLE,
        order: SectionSortOrder = SectionSortOrder.ASCENDING,
        watch: SectionFilter = SectionFilter.ALL,
        quality: SectionQualityFilter = SectionQualityFilter.ALL,
        filters: LibraryFilters? = null,
    ): List<String> = applyLibraryViewOptions(
        items,
        sort,
        order,
        filters ?: LibraryFilters(watch = watch, quality = quality),
    ).map { it.title }

    private fun item(
        title: String,
        type: String = "movie",
        year: Int? = null,
        durationMs: Long? = null,
        viewOffsetMs: Long? = null,
        viewCount: Int? = null,
        leafCount: Int? = null,
        viewedLeafCount: Int? = null,
        addedAt: Long? = null,
        rating: Double? = null,
        audienceRating: Double? = null,
        genres: List<String> = emptyList(),
        contentRating: String? = null,
        versions: List<MediaVersion> = emptyList(),
    ) = MediaItem(
        ratingKey = title,
        title = title,
        type = type,
        index = null,
        thumbUrl = null,
        artUrl = null,
        year = year,
        summary = null,
        tagline = null,
        durationMs = durationMs,
        viewOffsetMs = viewOffsetMs,
        parentTitle = null,
        parentRatingKey = null,
        grandparentTitle = null,
        grandparentRatingKey = null,
        contentRating = contentRating,
        rating = rating,
        audienceRating = audienceRating,
        studio = null,
        viewCount = viewCount,
        leafCount = leafCount,
        viewedLeafCount = viewedLeafCount,
        addedAt = addedAt,
        genres = genres,
        mediaVersions = versions,
    )

    private fun version(resolution: String?, height: Int?, hdr: String? = null) = MediaVersion(
        streamUrl = null,
        resolution = resolution,
        videoCodec = "hevc",
        audioCodec = "aac",
        container = "mkv",
        height = height,
        videoDynamicRange = hdr,
    )
}

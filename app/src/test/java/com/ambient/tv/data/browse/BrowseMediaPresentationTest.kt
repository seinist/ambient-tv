package com.ambient.tv.data.browse

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowseMediaPresentationTest {
    private fun item(
        thumb: String = "https://media.local/poster?tag=1",
        progress: Long = 10_000L,
        stream: String = "https://media.local/play?token=a",
    ) = MediaItem(
        ratingKey = "42",
        title = "Title",
        type = "movie",
        index = null,
        thumbUrl = thumb,
        artUrl = "https://media.local/backdrop?tag=1",
        year = 2026,
        summary = "Summary",
        tagline = null,
        durationMs = 100_000L,
        viewOffsetMs = progress,
        parentTitle = null,
        parentRatingKey = null,
        grandparentTitle = null,
        grandparentRatingKey = null,
        contentRating = "12",
        rating = 8.0,
        studio = "Studio",
        mediaVersions = listOf(
            MediaVersion(
                streamUrl = stream,
                resolution = "1080",
                videoCodec = "h264",
                audioCodec = "aac",
                container = "mp4",
            ),
        ),
    )

    @Test fun backendOnlyChangesDoNotInvalidateBrowsePresentation() {
        val before = item(stream = "https://media.local/play?token=old")
        val after = before.copy(
            mediaVersions = listOf(
                before.mediaVersions.single().copy(
                    streamUrl = "https://media.local/play?token=new",
                    sourceId = "new-source",
                    fileName = "different-file.mkv",
                ),
            ),
            guid = "plex://new-internal-id",
        )

        assertEquals(before.browsePresentationKey(), after.browsePresentationKey())
    }

    @Test fun credentialOnlyArtworkChangeKeepsPresentation() {
        val before = item(thumb = "https://media.local/poster?tag=1")
        val after = item(thumb = "https://media.local/poster?tag=1&X-Plex-Token=fresh")

        assertEquals(before.browsePresentationKey(), after.browsePresentationKey())
    }

    @Test fun visibleMetadataProgressArtworkAndTechnicalChangesInvalidatePresentation() {
        val before = item()

        assertNotEquals(before.browsePresentationKey(), before.copy(title = "New title").browsePresentationKey())
        assertNotEquals(before.browsePresentationKey(), before.copy(viewOffsetMs = 50_000L).browsePresentationKey())
        assertNotEquals(
            before.browsePresentationKey(),
            before.copy(thumbUrl = "https://media.local/poster?tag=2").browsePresentationKey(),
        )
        assertNotEquals(
            before.browsePresentationKey(),
            before.copy(
                mediaVersions = listOf(before.mediaVersions.single().copy(resolution = "4k")),
            ).browsePresentationKey(),
        )
    }
}

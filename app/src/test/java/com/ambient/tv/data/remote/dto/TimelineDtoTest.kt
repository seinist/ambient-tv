package com.ambient.tv.data.remote.dto

import com.ambient.tv.data.jellyfin.JellyfinItem
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TimelineDtoTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    @Test fun plexChapterOffsetsAndPartIndexAvailabilityAreDecoded() {
        val response = json.decodeFromString<MetadataResponse>("""{"MediaContainer":{"Metadata":[{"ratingKey":"42","key":"/library/metadata/42","title":"Film","type":"movie","Chapter":[{"startTimeOffset":120000,"endTimeOffset":240000,"tag":"Arrival","thumb":"/library/metadata/42/chapterImages/1"}],"Media":[{"Part":[{"id":99,"indexes":"sd"}]}]}]}}""")
        val item = response.container.metadata.single()
        assertEquals(120_000L, item.chapters.single().startTimeOffset); assertEquals("sd", item.media.single().parts.single().indexes)
    }
    @Test fun jellyfinTicksAndPerSourceSpriteGeometryAreDecoded() {
        val item = json.decodeFromString<JellyfinItem>("""{"Id":"film","Chapters":[{"StartPositionTicks":1230000000,"Name":"Chapter","ImageTag":"tag"}],"Trickplay":{"source":{"160":{"Width":160,"Height":90,"TileWidth":10,"TileHeight":10,"Interval":10000,"ThumbnailCount":550}}}}""")
        assertEquals(123_000L, item.chapters.single().startTicks / 10_000L)
        val sprite = item.trickplay["source"]?.get("160")!!; assertEquals(10, sprite.columns); assertEquals(550L, sprite.count)
    }
    @Test fun serversWithoutOptionalPreviewDataStillDecode() {
        val item = json.decodeFromString<JellyfinItem>("""{"Id":"film","Chapters":null,"Trickplay":{"source":null}}""")
        assertTrue(item.chapters.isEmpty()); assertNull(item.trickplay["source"])
        val plex = json.decodeFromString<MetadataDto>("""{"ratingKey":"1","key":"k","title":"t","type":"movie"}""")
        assertTrue(plex.chapters.isEmpty())
    }

    @Test fun plexEpisodeKeepsSeriesAndSeasonThumbs() {
        val episode = json.decodeFromString<MetadataDto>(
            """{"ratingKey":"9","key":"/library/metadata/9","title":"Pilot","type":"episode","thumb":"/ep.jpg","parentThumb":"/season.jpg","grandparentThumb":"/show.jpg","grandparentTitle":"Series"}""",
        )
        assertEquals("/ep.jpg", episode.thumb)
        assertEquals("/season.jpg", episode.parentThumb)
        assertEquals("/show.jpg", episode.grandparentThumb)
    }

    @Test fun plexClearLogoPrefersImageArrayOverAttribute() {
        val item = json.decodeFromString<MetadataDto>(
            """{"ratingKey":"1","key":"k","title":"Film","type":"movie","clearLogo":"/library/metadata/1/clearLogo/old","Image":[{"alt":"Film","type":"coverPoster","url":"/poster"},{"alt":"Film","type":"clearLogo","url":"/library/metadata/1/clearLogo/new"}]}""",
        )
        assertEquals("/library/metadata/1/clearLogo/new", pickPlexClearLogo(item.clearLogo, item.images))
    }

    @Test fun plexClearLogoFallsBackToAttributeWhenImageArrayOmitsIt() {
        val item = json.decodeFromString<MetadataDto>(
            """{"ratingKey":"1","key":"k","title":"Film","type":"movie","clearLogo":"/library/metadata/1/clearLogo/1"}""",
        )
        assertEquals("/library/metadata/1/clearLogo/1", pickPlexClearLogo(item.clearLogo, item.images))
        assertNull(pickPlexClearLogo(null, emptyList()))
    }
}

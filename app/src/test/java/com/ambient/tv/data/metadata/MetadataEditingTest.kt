package com.ambient.tv.data.metadata

import com.ambient.tv.domain.model.ArtworkKind
import com.ambient.tv.domain.model.MetadataEditSupport
import kotlinx.serialization.json.Json
import com.ambient.tv.domain.model.MetadataField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MetadataEditingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun editOptionsFollowTheItemType() {
        assertEquals(MetadataEditSupport(true, setOf(ArtworkKind.POSTER, ArtworkKind.BACKDROP), fields = true), MetadataEditSupport.forType("movie"))
        assertEquals(MetadataEditSupport(false, setOf(ArtworkKind.POSTER), fields = true), MetadataEditSupport.forType("season"))
        assertEquals(MetadataEditSupport(fields = true), MetadataEditSupport.forType("episode"))
        assertFalse(MetadataEditSupport.forType("track").any)
        assertFalse(MetadataEditSupport.forType("collection").match)
        assertTrue(MetadataEditSupport.forType("collection", matchCollections = true).match)
    }

    @Test fun remoteSearchResultRoundTripsUnchangedForApply() {
        val raw = """{"Name":"Inception","ProductionYear":2010,"ImageUrl":"https://img/x.jpg","SearchProviderName":"TheMovieDb","ProviderIds":{"Tmdb":"27205"},"Extra":1}"""
        val match = requireNotNull((json.parseToJsonElement(raw) as JsonObject).toMetadataMatch())
        assertEquals("Inception", match.title)
        assertEquals(2010, match.year)
        assertEquals("TheMovieDb", match.source)
        assertEquals(json.parseToJsonElement(raw), metadataMatchBody(json, match))
    }

    @Test fun remoteSearchResultFallsBackToPremiereYearAndNeedsAName() {
        val dated = json.parseToJsonElement("""{"Name":"Dune","PremiereDate":"2021-09-15T00:00:00Z"}""") as JsonObject
        assertEquals(2021, dated.toMetadataMatch()?.year)
        assertNull((json.parseToJsonElement("""{"ProductionYear":2010}""") as JsonObject).toMetadataMatch())
    }

    @Test fun remoteImagePreviewsUseTheThumbnailButSelectTheFullImage() {
        val choice = requireNotNull(MediaBrowserRemoteImage(url = "https://full", thumbnailUrl = "https://thumb").toArtworkChoice())
        assertEquals("https://thumb", choice.previewUrl)
        assertEquals("https://full", choice.handle)
        assertNull(MediaBrowserRemoteImage(url = " ").toArtworkChoice())
    }

    @Test fun itemUpdateChangesOnlyEditedFieldsAndKeepsEverythingElse() {
        val item = json.parseToJsonElement(
            """{"Name":"Old","SortName":"old","Overview":"Text","ProductionYear":2001,"People":[{"Name":"A"}],"LockedFields":["Genres"]}""",
        ) as JsonObject
        val current = item.toMetadataFields()
        assertEquals("old", current[MetadataField.SORT_TITLE])
        assertEquals("2001", current[MetadataField.YEAR])
        val edited = current.copy(
            values = current.values + (MetadataField.TITLE to "New") + (MetadataField.YEAR to "2002"),
            locked = setOf(MetadataField.TITLE),
        )
        val updated = item.withMetadataFields(current, edited)
        assertEquals(JsonPrimitive("New"), updated["Name"])
        assertEquals(JsonPrimitive(2002), updated["ProductionYear"])
        assertEquals(item["People"], updated["People"])
        assertFalse("ForcedSortName" in updated)
        assertEquals(setOf("Genres", "Name"), (updated["LockedFields"] as JsonArray).map { (it as JsonPrimitive).content }.toSet())
    }

    @Test fun searchTypesCoverIdentifiableItemsOnly() {
        assertEquals("Series", mediaBrowserSearchType("show"))
        assertEquals("BoxSet", mediaBrowserSearchType("collection"))
        assertNull(mediaBrowserSearchType("episode"))
    }
}

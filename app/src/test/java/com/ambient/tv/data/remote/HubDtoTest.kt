package com.ambient.tv.data.remote

import com.ambient.tv.data.remote.dto.HubsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HubDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun hubKeepsLibraryItemsAndSkipsEntriesThatAreNone() {
        val response = json.decodeFromString<HubsResponse>(
            """{"MediaContainer":{"size":2,"Hub":[
                {"hubIdentifier":"movie.genre.1","title":"Top Action","type":"movie","Metadata":[
                    {"ratingKey":"7","key":"/library/metadata/7","title":"Heat","type":"movie","audienceRating":8.3},
                    {"title":"A person, not a library item","type":"person"}
                ]},
                {"hubIdentifier":"movie.empty","title":"Nothing here","type":"movie"}
            ]}}""",
        )
        val hubs = response.container.hubs
        assertEquals(listOf("movie.genre.1", "movie.empty"), hubs.map { it.hubIdentifier })
        assertEquals(listOf("Heat"), hubs[0].mediaItems().map { it.title })
        assertEquals(emptyList<String>(), hubs[1].mediaItems().map { it.title })
    }
}

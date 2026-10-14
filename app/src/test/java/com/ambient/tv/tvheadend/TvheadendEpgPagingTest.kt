package com.ambient.tv.tvheadend

import com.ambient.tv.tvheadend.data.loadTvhEpgPages
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TvheadendEpgPagingTest {
    @Test fun guideLoadsBeyondThreeThousandAndBoundsTheServerQuery() = runBlocking {
        val offsets = mutableListOf<Int>()
        val result = loadTvhEpgPages(1000, 2000, "channel-id") { params ->
            assertEquals("channel-id", params["channel"])
            val filters = Json.parseToJsonElement(params.getValue("filter")).jsonArray
            assertEquals(setOf("start", "stop"), filters.map { it.jsonObject.getValue("field").jsonPrimitive.content }.toSet())
            val offset = params.getValue("start").toInt(); offsets.add(offset)
            buildJsonObject {
                put("totalCount", 6501)
                putJsonArray("entries") { (offset until minOf(offset + 3000, 6501)).forEach { id -> addJsonObject { put("eventId", id) } } }
            }
        }
        assertEquals(listOf(0, 3000, 6000), offsets)
        assertEquals(6501, result.size)
    }
    @Test fun repeatedPagesFailInsteadOfSilentlyReturningATruncatedGuide() = runBlocking {
        val result = runCatching {
            loadTvhEpgPages(0, 100, null) {
                buildJsonObject { put("totalCount", 6001); putJsonArray("entries") { addJsonObject { put("eventId", "same") } } }
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}

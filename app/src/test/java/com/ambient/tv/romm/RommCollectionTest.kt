package com.ambient.tv.romm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommCollectionTest {
    private fun parse(text: String) = RommClient.collection(Json.parseToJsonElement(text))

    @Test fun theFavoritesCollectionIsReadAsRommSendsIt() {
        val collection = parse("""{"id":4,"name":"Favorites","is_favorite":true,"user_id":2,
            "owner_username":"anna","rom_ids":[3,7,7],"is_public":false}""")!!
        assertEquals(4L, collection.id)
        assertTrue(collection.isFavorite)
        assertEquals(2L, collection.userId)
        assertEquals("anna", collection.ownerUsername)
        assertEquals(setOf(3L, 7L), collection.romIds)
    }

    @Test fun olderServersListTheGamesUnderAnotherName() {
        val collection = parse("""{"id":"9","name":"Mine","roms":[{"id":12},5]}""")!!
        assertFalse(collection.isFavorite)
        assertEquals(setOf(12L, 5L), collection.romIds)
        assertNull(parse("""{"name":"No id"}"""))
        assertNull(parse("[]"))
    }
}

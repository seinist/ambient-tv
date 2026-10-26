package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.MetadataField
import com.ambient.tv.domain.model.MetadataFields
import org.junit.Assert.assertEquals
import org.junit.Test

class PlexFieldUpdateTest {
    private val current = MetadataFields(
        values = mapOf(MetadataField.TITLE to "Old", MetadataField.SUMMARY to "Text"),
        locked = setOf(MetadataField.SUMMARY),
        lockable = MetadataField.entries.toSet(),
    )

    @Test fun changedValuesAreSentWithTheirLock() {
        val edited = current.copy(values = current.values + (MetadataField.TITLE to " New "), locked = current.locked + MetadataField.TITLE)
        assertEquals(mapOf("title.value" to "New", "title.locked" to "1"), plexFieldUpdate(current, edited))
    }

    @Test fun anUnlockAloneIsSentWithoutAValue() {
        assertEquals(mapOf("summary.locked" to "0"), plexFieldUpdate(current, current.copy(locked = emptySet())))
        assertEquals(emptyMap<String, String>(), plexFieldUpdate(current, current))
    }
}

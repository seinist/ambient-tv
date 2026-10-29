package com.ambient.tv.ui.settings

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsIndexedListTest {
    /** Building the index must not compose rows that would normally remain off screen. */
    private class RecordingList : LazyListScope {
        val keys = mutableListOf<Any?>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            keys += key
        }

        override fun items(
            count: Int,
            key: ((Int) -> Any)?,
            contentType: (Int) -> Any?,
            itemContent: @Composable LazyItemScope.(Int) -> Unit,
        ) {
            repeat(count) { keys += key?.invoke(it) }
        }
    }

    @Test fun addedSectionsAndConditionalRowsCannotMoveSearchOffTheTarget() {
        val index = SettingsItemIndex()
        for (profilesVisible in listOf(false, true)) {
            val list = RecordingList()
            list.indexedSettingsItems(index) {
                item("intro") { error("Rows must remain lazy") }
                if (profilesVisible) item("profiles") { error("Rows must remain lazy") }
                item("hosts-heading") { error("Rows must remain lazy") }
                items(3, key = { "wake-group:$it" }) { error("Rows must remain lazy") }
                item("wake-sources") { error("Rows must remain lazy") }
            }
            val position = index.landingPosition("wake-group:2", requested = true)!!
            assertEquals("wake-group:2", list.keys[position])
            assertEquals("wake-sources", list.keys[index.indexOf("wake-sources")!!])
        }
    }

    @Test fun rebuildingDropsHiddenRowsAndUpdatesRemainingPositions() {
        val index = SettingsItemIndex()
        RecordingList().indexedSettingsItems(index) {
            item("conditional") { }
            item("renderer") { }
        }
        assertEquals(1, index.indexOf("renderer"))
        RecordingList().indexedSettingsItems(index) {
            item("renderer") { }
        }
        assertNull(index.indexOf("conditional"))
        assertEquals(0, index.indexOf("renderer"))
    }

}

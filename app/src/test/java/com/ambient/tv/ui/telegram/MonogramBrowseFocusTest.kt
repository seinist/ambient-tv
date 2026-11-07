package com.ambient.tv.ui.telegram

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonogramBrowseFocusTest {
    @Test fun focusHopsDoNotInvalidateAnObservedParentScope() {
        val memory = MonogramFocusMemory("first")
        val observer = SnapshotStateObserver { it() }
        var invalidated = false
        observer.start()
        try {
            observer.observeReads(Unit, { invalidated = true }) { assertEquals("first", memory.key) }
            repeat(100) { memory.key = "item-$it" }
            Snapshot.sendApplyNotifications()
            assertEquals("item-99", memory.key)
            assertFalse(invalidated)
        } finally { observer.stop() }
    }

    @Test fun savingUsesTheLatestSelectionEvenWithoutRecomposition() {
        val memory = MonogramFocusMemory()
        memory.key = "telegram:4:19"
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(MonogramFocusMemorySaver) { scope.save(memory) }
        assertEquals("telegram:4:19", saved)
        assertEquals(memory.key, MonogramFocusMemorySaver.restore(checkNotNull(saved))?.key)
        assertNull(MonogramFocusMemorySaver.restore("")?.key)
    }

    @Test fun rowAndFlatPositionsFollowTheDisplayedSortAndFilter() {
        val source = telegramRows("recent", listOf(
            TelegramMedia(1, 1, "A", "Older", TelegramMediaKind.VIDEO, publishedAtMs = 10L),
            TelegramMedia(1, 2, "A", "Newest", TelegramMediaKind.VIDEO, publishedAtMs = 40L),
            TelegramMedia(2, 3, "B", "Middle", TelegramMediaKind.VIDEO, publishedAtMs = 30L),
        ))
        val rows = visibleMonogramRows(source, "", null, MonogramBrowseSort.DATE, true)
        val flat = flatMonogramItems(rows, MonogramBrowseSort.DATE, true)
        val index = MonogramBrowseFocusIndex(rows, flat)
        assertEquals(listOf("telegram:1:2", "telegram:2:3", "telegram:1:1"), index.flatKeys)
        assertEquals("telegram.recent.1", index.rowForItem("telegram:1:2"))
        assertEquals(0, index.indexInRow("telegram.recent.1", "telegram:1:2"))
        assertEquals(1, index.indexInRow("telegram.recent.1", "telegram:1:1"))
        assertEquals(-1, index.indexInRow("telegram.recent.2", "telegram:1:2"))
        assertTrue(index.contains("telegram:2:3"))
        assertFalse(index.contains("removed"))
        val filtered = visibleMonogramRows(source, "", "telegram.recent.1", MonogramBrowseSort.DATE, true)
        val updated = MonogramBrowseFocusIndex(filtered, flatMonogramItems(filtered, MonogramBrowseSort.DATE, true))
        assertFalse(updated.contains("telegram:2:3"))
        assertNull(updated.rowForItem("telegram:2:3"))
    }
}

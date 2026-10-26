package com.ambient.tv.ui.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRowScrollSettleTest {
    @Test fun idleListReturnsImmediately() = runTest {
        awaitRowScrollSettled(MutableStateFlow(false))
        assertEquals(0L, currentTime)
    }

    @Test fun waitsForGlideEndPlusSettle() = runTest {
        val scrolling = MutableStateFlow(true)
        var doneAt = -1L
        launch { awaitRowScrollSettled(scrolling); doneAt = currentTime }
        advanceTimeBy(600); runCurrent()
        assertEquals(-1L, doneAt)
        scrolling.value = false
        advanceTimeBy(ROW_SCROLL_SETTLE_MS - 1); runCurrent()
        assertEquals(-1L, doneAt)
        advanceUntilIdle()
        assertEquals(600L + ROW_SCROLL_SETTLE_MS, doneAt)
    }

    @Test fun glideRestartingInsideSettleKeepsWaiting() = runTest {
        val scrolling = MutableStateFlow(true)
        var doneAt = -1L
        launch { awaitRowScrollSettled(scrolling); doneAt = currentTime }
        advanceTimeBy(300); runCurrent()
        scrolling.value = false
        advanceTimeBy(40); runCurrent()
        scrolling.value = true // next row step during the quiet time
        advanceTimeBy(400); runCurrent()
        assertEquals(-1L, doneAt)
        scrolling.value = false
        advanceUntilIdle()
        assertEquals(740L + ROW_SCROLL_SETTLE_MS, doneAt)
    }

    @Test fun endlessScrollIsCapped() = runTest {
        var doneAt = -1L
        launch { awaitRowScrollSettled(MutableStateFlow(true)); doneAt = currentTime }
        advanceUntilIdle()
        assertEquals(ROW_SCROLL_MAX_DEFER_MS, doneAt)
    }

    /** Same shape as the hero collectors: the newest focus wins, nothing stale is committed. */
    @Test fun latestFocusWinsAfterGlide() = runTest {
        val scrolling = MutableStateFlow(false)
        val focus = MutableStateFlow("row1")
        val committed = mutableListOf<String>()
        val job = launch {
            focus.collectLatest { item ->
                delay(150)
                awaitRowScrollSettled(scrolling)
                committed += item
            }
        }
        advanceTimeBy(151); runCurrent()
        assertEquals(listOf("row1"), committed)
        scrolling.value = true
        focus.value = "row2"
        advanceTimeBy(100); runCurrent()
        focus.value = "row3"
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("row1"), committed)
        scrolling.value = false
        advanceTimeBy(ROW_SCROLL_SETTLE_MS + 1); runCurrent()
        assertEquals(listOf("row1", "row3"), committed)
        job.cancel()
    }

    @Test fun neverScrollingFlowIsIdle() {
        assertFalse(RowsNeverScrolling.value)
    }
}

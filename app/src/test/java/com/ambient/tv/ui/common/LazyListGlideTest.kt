package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LazyListGlideTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = LazyListState()
    private val composedIndices = mutableSetOf<Int>()
    private lateinit var scope: CoroutineScope

    private fun mount(count: Int, reverseLayout: Boolean = false) {
        rule.setContent {
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.fillMaxWidth().height(160.dp), state, reverseLayout = reverseLayout) {
                items(count, key = { it }) { index ->
                    SideEffect { composedIndices += index }
                    Box(Modifier.fillMaxWidth().height(40.dp))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun startGlide(index: Int, animate: Boolean = true): Job = rule.runOnIdle {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { state.glideToItem(animate, index) }
    }

    private fun assertTargetVisible(index: Int) = rule.runOnIdle {
        assertTrue("Target $index must be visible", state.layoutInfo.visibleItemsInfo.any { it.index == index })
    }

    private fun assertCatalogMiddleSkipped(count: Int) = rule.runOnIdle {
        // Allow ample space for prefetch/reuse around both endpoints; never visit the middle.
        val middle = composedIndices.filter { it in 40 until count - 40 }
        assertTrue("Unexpected intermediate catalog rows: ${middle.take(12)}", middle.isEmpty())
    }

    private fun assertLargeWraps(reverseLayout: Boolean) {
        val count = 10_000
        mount(count, reverseLayout)
        val toEnd = startGlide(count - 1)
        rule.waitForIdle()
        assertTrue(toEnd.isCompleted)
        assertTargetVisible(count - 1)
        assertCatalogMiddleSkipped(count)
        rule.runOnIdle { composedIndices.clear() }
        val toStart = startGlide(0)
        rule.waitForIdle()
        assertTrue(toStart.isCompleted)
        assertTargetVisible(0)
        assertCatalogMiddleSkipped(count)
    }

    @Test fun tenThousandRowsWrapWithoutComposingTheWholeCatalog() = assertLargeWraps(reverseLayout = false)

    @Test fun reversedTenThousandRowsWrapWithoutComposingTheWholeCatalog() = assertLargeWraps(reverseLayout = true)

    @Test fun shortMoveRetainsItsContinuousAnimation() {
        mount(count = 30)
        rule.mainClock.autoAdvance = false
        val glide = startGlide(8)
        rule.runOnIdle {
            assertEquals("A short glide must not jump first", 0, state.firstVisibleItemIndex)
            assertFalse(glide.isCompleted)
        }
        rule.mainClock.advanceTimeBy(240L)
        rule.runOnIdle {
            assertTrue("The glide should pass through intermediate rows", state.firstVisibleItemIndex in 1..7)
            assertFalse(glide.isCompleted)
        }
        rule.mainClock.advanceTimeBy(1_000L)
        rule.waitForIdle()
        assertTrue(glide.isCompleted)
        assertTargetVisible(8)
    }

    @Test fun reducedMotionJumpsImmediatelyAndClampsOutOfRangeTargets() {
        mount(count = 10_000)
        rule.mainClock.autoAdvance = false
        val toEnd = startGlide(Int.MAX_VALUE, animate = false)
        rule.waitForIdle()
        assertTrue(toEnd.isCompleted)
        assertTargetVisible(9_999)
        assertCatalogMiddleSkipped(10_000)
        val toStart = startGlide(-1, animate = false)
        rule.waitForIdle()
        assertTrue(toStart.isCompleted)
        assertTargetVisible(0)
    }

    @Test fun emptyListHasNoScrollOrAnimationToSchedule() {
        mount(count = 0)
        rule.mainClock.autoAdvance = false
        assertTrue(startGlide(Int.MAX_VALUE).isCompleted)
        assertTrue(startGlide(-1, animate = false).isCompleted)
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertTrue(state.layoutInfo.visibleItemsInfo.isEmpty())
        }
    }
}

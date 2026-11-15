package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Observe the first placed viewport, before a later focus/scroll effect could hide a flash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
class BrowseStartupViewportTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Viewport(val index: Int, val offset: Int, val firstKey: Any?)

    private class Fixture(
        val vertical: Boolean,
        val initiallyClaimed: Boolean = false,
        val explicitReturn: Boolean = false,
    ) {
        val gate = BrowseStartupFocusGate().also { if (initiallyClaimed) it.claim() }
        var keys by mutableStateOf((0 until 40).map { "cached-$it" })
        var newSessionOnRestore = false
        var phase = 0
        val firstLayouts = mutableMapOf<Int, Viewport>()
        lateinit var list: LazyListState

        @Composable
        fun Content() {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                // A new UI session replaces its gate, whereas configuration recreation reuses it.
                val activeGate = remember { if (newSessionOnRestore) BrowseStartupFocusGate() else gate }
                val currentKeys = keys
                val currentPhase = phase
                val state = if (vertical) rememberBrowseRowColumnState(8, 17) else rememberBrowseRowState(8, 17)
                list = state
                BrowseStartupViewport(state, currentKeys, activeGate.followsFirst(explicitReturn))
                val modifier = Modifier.width(300.dp).height(if (vertical) 180.dp else 60.dp)
                    .testTag("startup-viewport")
                    .claimBrowseStartupFocusOnInput(activeGate)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            firstLayouts.putIfAbsent(currentPhase, Viewport(state.firstVisibleItemIndex,
                                state.firstVisibleItemScrollOffset, state.layoutInfo.visibleItemsInfo.firstOrNull()?.key))
                            placeable.place(0, 0)
                        }
                    }
                if (vertical) LazyColumn(state = state, modifier = modifier) {
                    items(currentKeys, key = { it }) { Box(Modifier.size(100.dp, 60.dp)) }
                } else LazyRow(state = state, modifier = modifier) {
                    items(currentKeys, key = { it }) { Box(Modifier.size(100.dp, 60.dp)) }
                }
            }
        }
    }

    private fun assertFirst(fixture: Fixture, phase: Int, index: Int, offset: Int, key: String) {
        // Values were captured during placement, not by this later idle assertion.
        assertEquals(Viewport(index, offset, key), fixture.firstLayouts[phase])
    }

    private fun checkRestoredViewport(vertical: Boolean, newSession: Boolean) {
        val fixture = Fixture(vertical, initiallyClaimed = true)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Content() }
        rule.waitForIdle()
        assertFirst(fixture, 0, 8, 17, "cached-8")

        // These flags do not recompose the old tree before its index/offset are saved.
        fixture.phase = 1
        fixture.newSessionOnRestore = newSession
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        if (newSession) assertFirst(fixture, 1, 0, 0, "cached-0")
        else assertFirst(fixture, 1, 8, 17, "cached-8")
    }

    @Test fun freshHomeStartsItsSavedRowColumnAtZeroBeforeFirstLayout() =
        checkRestoredViewport(vertical = true, newSession = true)

    @Test fun freshHomeStartsItsSavedHorizontalCardsAtZeroBeforeFirstLayout() =
        checkRestoredViewport(vertical = false, newSession = true)

    @Test fun configurationRecreationPreservesTheChosenRowColumn() =
        checkRestoredViewport(vertical = true, newSession = false)

    @Test fun configurationRecreationPreservesTheChosenHorizontalCard() =
        checkRestoredViewport(vertical = false, newSession = false)

    private fun checkInsertedFirstItem(vertical: Boolean, navigate: Boolean) {
        val fixture = Fixture(vertical)
        rule.setContent { fixture.Content() }
        rule.waitForIdle()
        assertFirst(fixture, 0, 0, 0, "cached-0")

        rule.runOnIdle {
            if (navigate) fixture.gate.claim()
            fixture.phase = 1
            fixture.keys = listOf("live-first") + fixture.keys
        }
        rule.waitForIdle()
        if (navigate) assertFirst(fixture, 1, 1, 0, "cached-0")
        else assertFirst(fixture, 1, 0, 0, "live-first")
    }

    @Test fun aNewLiveRowAppearsAtZeroInItsFirstLayoutBeforeAnyNavigation() =
        checkInsertedFirstItem(vertical = true, navigate = false)

    @Test fun aNewLiveCardAppearsAtZeroInItsFirstLayoutBeforeAnyNavigation() =
        checkInsertedFirstItem(vertical = false, navigate = false)

    @Test fun userNavigationReleasesThePinAndKeepsTheChosenCardDuringInserts() =
        checkInsertedFirstItem(vertical = false, navigate = true)

    @Test fun explicitPlaybackReturnKeepsTheSelectedCardFromTheFirstLayout() {
        val fixture = Fixture(vertical = false, explicitReturn = true)
        rule.setContent { fixture.Content() }
        rule.waitForIdle()
        assertFirst(fixture, 0, 8, 17, "cached-8")
    }

    @Test fun aMouseWheelScrollReleasesThePinWithoutConsumingTheScroll() {
        val fixture = Fixture(vertical = true)
        rule.setContent { fixture.Content() }
        rule.waitForIdle()
        assertFirst(fixture, 0, 0, 0, "cached-0")

        rule.onNodeWithTag("startup-viewport").performMouseInput { scroll(80f) }
        rule.waitForIdle()
        assertFalse(fixture.gate.followsFirst())
        val before = Viewport(fixture.list.firstVisibleItemIndex, fixture.list.firstVisibleItemScrollOffset,
            fixture.list.layoutInfo.visibleItemsInfo.firstOrNull()?.key)
        assertTrue("The observed wheel event must still scroll the real list", before.index > 0 || before.offset > 0)

        rule.runOnIdle {
            fixture.phase = 1
            fixture.keys = listOf("live-first") + fixture.keys
        }
        rule.waitForIdle()
        assertEquals(before.copy(index = before.index + 1), fixture.firstLayouts[1])
    }
}

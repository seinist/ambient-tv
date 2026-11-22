package com.ambient.tv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the shell's real initial-entry effect with the shared browse/chip focus tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class,
    qualifiers = "w960dp-h720dp-land-mdpi")
class InitialContentFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        var entryId by mutableStateOf("youtube-entry-1")
        var contentPending by mutableStateOf(true)
        var selected by mutableIntStateOf(0)
        var focusedChip: Int? = null
        var outsideFocused = false
        var requests = 0
        val focusVisits = mutableListOf<Int>()
        val firstChip = FocusRequester()
        val outside = FocusRequester()
        lateinit var owner: BrowseFocusReturnState
        lateinit var pending: MutableState<Boolean>
        lateinit var clearFocus: () -> Unit
    }

    @Before fun clearPendingPageReselect() {
        NavReselect.restoreTop.value = false
    }

    private fun mount(): Fixture {
        val fixture = Fixture()
        rule.setContent {
            AmbientTvTheme {
                // The shell's effect outlives individual NavHost destinations.
                val manager = LocalFocusManager.current
                val pending = rememberInitialContentFocus(
                    entryId = fixture.entryId,
                    waitsForContent = true,
                    contentPending = fixture.contentPending,
                    enabled = true,
                    hasUsableFocus = { fixture.focusedChip != null || fixture.outsideFocused },
                    requestFocus = {
                        fixture.requests++
                        fixture.owner.requestFocus()
                    },
                )
                SideEffect {
                    fixture.pending = pending
                    fixture.clearFocus = { manager.clearFocus(force = true) }
                }
                key(fixture.entryId) {
                    BrowseFocusReturnScope {
                        val owner = checkNotNull(LocalBrowseFocusReturnState.current)
                        SideEffect { fixture.owner = owner }
                        // SmartTube changes content identity and readiness in place while
                        // the back-stack entry and the chip focusRestorer remain alive.
                        BrowseFocusContentIdentity(fixture.selected, restoreInitialTarget = true)
                        BrowseFocusHoldEntry(fixture.contentPending)
                        val chain = rememberPageFocusChain()
                        Column {
                            PageChipRow(chain, selectedIndex = fixture.selected) {
                                items((0..2).toList(), key = { it }) { index ->
                                    TvChip(
                                        text = if (index == 0) "Start" else "Category $index",
                                        selected = index == fixture.selected,
                                        modifier = Modifier.testTag("initial-category-$index")
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    fixture.focusedChip = index
                                                    fixture.focusVisits += index
                                                } else if (fixture.focusedChip == index) {
                                                    fixture.focusedChip = null
                                                }
                                            }
                                            .then(if (index == 0) Modifier.focusRequester(fixture.firstChip)
                                                else Modifier)
                                            .then(if (index == fixture.selected)
                                                Modifier.focusRequester(chain.selectedChip) else Modifier),
                                        onClick = {
                                            fixture.selected = index
                                            fixture.contentPending = true
                                        },
                                    )
                                }
                            }
                            Box(Modifier.size(64.dp).focusRequester(fixture.outside)
                                .testTag("outside-chips")
                                .onFocusChanged { fixture.outsideFocused = it.isFocused }
                                .focusable())
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun rememberStartThenFocusNextCategory(fixture: Fixture) {
        rule.runOnIdle { assertTrue(fixture.firstChip.requestFocus()) }
        // Leaving the row records Start as its restorable child. Moving inside the
        // row afterward must not let a readiness handoff restore that older child.
        rule.runOnIdle { assertTrue(fixture.outside.requestFocus()) }
        rule.runOnIdle { assertTrue(fixture.firstChip.requestFocus()) }
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("initial-category-1").assertIsFocused()
        rule.runOnIdle { fixture.focusVisits.clear() }
    }

    private fun completeStartupWithoutLeafFocus(fixture: Fixture) {
        rule.runOnIdle {
            fixture.clearFocus()
            fixture.contentPending = false
        }
        rule.waitForIdle()
        rule.onNodeWithTag("initial-category-0").assertIsFocused()
    }

    @Test fun startupWithoutLeafFocusStillEntersSharedChipRow() {
        val fixture = mount()
        completeStartupWithoutLeafFocus(fixture)
        rule.runOnIdle {
            assertEquals(1, fixture.requests)
            assertFalse(fixture.pending.value)
        }
    }

    @Test fun inPlaceCategoryReadinessDoesNotRearmEntryOrVisitStart() {
        val fixture = mount()
        completeStartupWithoutLeafFocus(fixture)
        rememberStartThenFocusNextCategory(fixture)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        rule.runOnIdle {
            assertEquals(1, fixture.selected)
            assertFalse(fixture.pending.value)
            fixture.contentPending = false
        }
        rule.waitForIdle()
        rule.onNodeWithTag("initial-category-1").assertIsFocused()
        rule.runOnIdle {
            assertEquals("A category reload must not enter the page again", 1, fixture.requests)
            assertTrue("Start briefly received focus during category reload: ${fixture.focusVisits}",
                fixture.focusVisits.all { it == 1 })
        }
    }

    @Test fun firstLoadCompletionPreservesAnAlreadyFocusedCategoryWithoutTransientRestore() {
        val fixture = mount()
        rememberStartThenFocusNextCategory(fixture)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        rule.runOnIdle {
            assertTrue(fixture.pending.value)
            fixture.contentPending = false
        }
        rule.waitForIdle()
        rule.onNodeWithTag("initial-category-1").assertIsFocused()
        rule.runOnIdle {
            assertEquals("A usable chip already owns initial focus", 0, fixture.requests)
            assertFalse(fixture.pending.value)
            assertTrue("Readiness briefly restored the older Start chip: ${fixture.focusVisits}",
                fixture.focusVisits.all { it == 1 })
        }
    }

    @Test fun aNewBackStackEntryReceivesItsOwnInitialHandoff() {
        val fixture = mount()
        completeStartupWithoutLeafFocus(fixture)
        rule.runOnIdle {
            fixture.contentPending = true
            fixture.entryId = "youtube-entry-2"
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(fixture.pending.value) }
        completeStartupWithoutLeafFocus(fixture)
        rule.runOnIdle {
            assertEquals(2, fixture.requests)
            assertFalse(fixture.pending.value)
        }
    }
}

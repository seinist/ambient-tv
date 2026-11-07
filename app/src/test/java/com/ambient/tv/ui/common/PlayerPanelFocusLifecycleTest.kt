package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerPanelFocusLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun backWaitsForTheOriginToReenterCompositionAndUsesItsNewRequester() {
        val showParent = mutableStateOf(true)
        lateinit var navigation: PlayerPanelNavigation
        rule.mainClock.autoAdvance = false
        rule.setContent {
            navigation = rememberPlayerPanelNavigation()
            CompositionLocalProvider(LocalPlayerPanelNavigation provides navigation) {
                when (navigation.panel) {
                    UnifiedPlayerPanel.VISUALIZER -> if (showParent.value) {
                        Target("entry:appearance", "origin")
                    }
                    UnifiedPlayerPanel.VISUALIZER_SETTINGS -> Target("settings:control", "child")
                    else -> Unit
                }
            }
        }
        rule.runOnIdle { navigation.open(UnifiedPlayerPanel.VISUALIZER) }
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle {
            assertTrue(navigation.requestFocus("entry:appearance"))
            navigation.fromOrigin("entry:appearance", root = false) {
                navigation.open(UnifiedPlayerPanel.VISUALIZER_SETTINGS)
            }
        }
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithTag("origin").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(1, navigation.registeredFocusTargetCount)
            assertFalse(navigation.requestFocus("entry:appearance"))
            showParent.value = false
            navigation.back()
        }

        // The hand-back begins while the original row is absent, as during panel rebuilding.
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle {
            assertEquals("entry:appearance", navigation.restoreKey)
            assertEquals(0, navigation.registeredFocusTargetCount)
            showParent.value = true
        }
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("origin").assertIsFocused()
        rule.runOnIdle {
            assertNull(navigation.restoreKey)
            assertFalse(navigation.restoreFailed)
            assertEquals(1, navigation.registeredFocusTargetCount)
        }
    }

    @Test fun disposingOneOfTwoComposedCopiesDoesNotUnregisterTheOther() {
        val copies = mutableStateListOf("outgoing")
        val navigation = PlayerPanelNavigation()
        rule.setContent {
            CompositionLocalProvider(LocalPlayerPanelNavigation provides navigation) {
                Row {
                    copies.forEach { copy ->
                        key(copy) { Target("bar:Favorit", copy) }
                    }
                }
            }
        }
        rule.runOnIdle {
            assertEquals(1, navigation.registeredFocusTargetCount)
            copies.add("incoming")
        }
        rule.runOnIdle {
            assertEquals(2, navigation.registeredFocusTargetCount)
            assertTrue(navigation.requestFocus("bar:Favorit"))
        }
        rule.onNodeWithTag("incoming").assertIsFocused()
        rule.runOnIdle { copies.remove("outgoing") }
        rule.runOnIdle {
            assertEquals(1, navigation.registeredFocusTargetCount)
            assertTrue(navigation.requestFocus("bar:Favorit"))
        }
        rule.onNodeWithTag("incoming").assertIsFocused()
        rule.runOnIdle { copies.clear() }
        rule.runOnIdle {
            assertEquals(0, navigation.registeredFocusTargetCount)
            assertFalse(navigation.requestFocus("bar:Favorit"))
        }
    }

    @Composable
    private fun Target(focusKey: String, tag: String) {
        Box(Modifier.size(80.dp).testTag(tag).playerPanelFocusKey(focusKey).focusable())
    }
}

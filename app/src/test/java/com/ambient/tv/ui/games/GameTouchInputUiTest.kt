package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose pointer routing: no emulator/core is needed to catch an accidental OSD tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port-mdpi")
class GameTouchInputUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        var menuVisible by mutableStateOf(false)
        var mode by mutableStateOf(GameTouchMode.DPAD)
        var style by mutableStateOf(TouchControlStyle())
        var hiddenForGamepad by mutableStateOf(false)
        var osdTaps = 0
        val held = mutableSetOf<RetroPadButton>()
        val aTransitions = mutableListOf<Boolean>()
        fun button(button: RetroPadButton, down: Boolean) {
            if (down) held.add(button) else held.remove(button)
            if (button == RetroPadButton.A) aTransitions += down
        }
    }

    private fun mount(lifecycleOwner: LifecycleOwner? = null): Fixture {
        val fixture = Fixture()
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides true,
                LocalLifecycleOwner provides (lifecycleOwner ?: LocalLifecycleOwner.current),
            ) {
                AmbientTvTheme {
                    // Ancestor tap handling reproduces the player/interop failure if the
                    // child ever leaves a DOWN or final UP unconsumed.
                    Box(Modifier.fillMaxSize().testTag("game-touch-root").clickable { fixture.osdTaps++ }) {
                        GamePlayerTouchLayout(
                            handheld = true, directPointer = false, touchEnabled = true,
                            touchMode = fixture.mode, playing = true, menuVisible = fixture.menuVisible,
                            onMenu = { fixture.osdTaps++ }, onButton = fixture::button, onStick = { _, _, _ -> },
                            surface = { modifier -> Box(modifier) },
                            touchStyle = fixture.style, hiddenForGamepad = fixture.hiddenForGamepad,
                            onShowTouch = { fixture.hiddenForGamepad = false },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        fixture.aTransitions.clear() // Disposal/initial neutral notifications are not presses.
        return fixture
    }

    @Test fun heldDirectionAndRepeatedFacePressesNeverOpenTheOsd() {
        val fixture = mount()
        val root = rule.onNodeWithTag("game-touch-root")
        val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val dpad = rule.onNodeWithTag("game-touch-dpad", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val a = rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - rootOrigin
        val left = Offset(dpad.left + 12f, dpad.center.y) - rootOrigin
        root.performTouchInput { down(0, left); down(1, a) }
        rule.runOnIdle {
            assertTrue(RetroPadButton.LEFT in fixture.held)
            assertTrue(RetroPadButton.A in fixture.held)
        }
        root.performTouchInput { up(1) }
        rule.runOnIdle {
            assertTrue(RetroPadButton.LEFT in fixture.held)
            assertFalse(RetroPadButton.A in fixture.held)
        }
        root.performTouchInput { down(1, a); up(1); up(0) }
        rule.runOnIdle {
            assertEquals(listOf(true, false, true, false), fixture.aTransitions)
            assertTrue(fixture.held.isEmpty())
            assertEquals(0, fixture.osdTaps)
        }
        rule.onNodeWithTag("game-player-menu", useUnmergedTree = true).performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, fixture.osdTaps) }
    }

    @Test fun thumbCanRollFromAToABToBWithoutLiftingOrOpeningTheOsd() {
        val fixture = mount()
        val root = rule.onNodeWithTag("game-touch-root")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val a = rule.onNodeWithTag("game-touch-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        val b = rule.onNodeWithTag("game-touch-b", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput { down(0, a) }
        rule.runOnIdle { assertEquals(setOf(RetroPadButton.A), fixture.held) }
        root.performTouchInput { moveTo(0, (a + b) / 2f) }
        rule.runOnIdle { assertEquals(setOf(RetroPadButton.A, RetroPadButton.B), fixture.held) }
        root.performTouchInput { moveTo(0, b) }
        rule.runOnIdle { assertEquals(setOf(RetroPadButton.B), fixture.held) }
        root.performTouchInput { up(0) }
        rule.runOnIdle {
            assertTrue(fixture.held.isEmpty())
            assertEquals(listOf(true, false), fixture.aTransitions)
            assertEquals(0, fixture.osdTaps)
        }
    }

    @Test fun releasingOneOfTwoFingersOnADoesNotReleaseTheOther() {
        val fixture = mount()
        val root = rule.onNodeWithTag("game-touch-root")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val a = rule.onNodeWithTag("game-touch-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput { down(0, a); down(1, a + Offset(2f, 0f)) }
        rule.runOnIdle { assertEquals(setOf(RetroPadButton.A), fixture.held) }
        root.performTouchInput { up(0) }
        rule.runOnIdle {
            assertEquals(setOf(RetroPadButton.A), fixture.held)
            assertEquals(listOf(true), fixture.aTransitions)
        }
        root.performTouchInput { up(1) }
        rule.runOnIdle {
            assertTrue(fixture.held.isEmpty())
            assertEquals(listOf(true, false), fixture.aTransitions)
            assertEquals(0, fixture.osdTaps)
        }
    }

    @Test fun draggingOutOfTheFaceAreaReleasesInputAndCannotActivateTheMenu() {
        val fixture = mount()
        val root = rule.onNodeWithTag("game-touch-root")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val a = rule.onNodeWithTag("game-touch-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        val menu = rule.onNodeWithTag("game-player-menu", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput { down(0, a); moveTo(0, menu) }
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()) }
        root.performTouchInput { up(0) }
        rule.runOnIdle {
            assertTrue(fixture.held.isEmpty())
            assertEquals(0, fixture.osdTaps)
        }
    }

    @Test fun remotePadReceivesOneCompleteButtonSnapshotPerChangeAndNeutralOnDispose() {
        var showing by mutableStateOf(true)
        val snapshots = mutableListOf<Set<RetroPadButton>>()
        var individualCallbacks = 0
        var directionCallbacks = 0
        rule.setContent {
            AmbientTvTheme {
                Box(Modifier.fillMaxSize().testTag("remote-touch-root")) {
                    if (showing) GameTouchControls(
                        mode = GameTouchMode.DPAD,
                        onButton = { _, _ -> individualCallbacks++ },
                        onDpadDirections = { directionCallbacks++ },
                        onButtonsChanged = { snapshots += it },
                        onStick = { _, _, _ -> },
                        portrait = true,
                        applySafeInsets = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
        val root = rule.onNodeWithTag("remote-touch-root")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val dpad = rule.onNodeWithTag("game-touch-dpad", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val up = Offset(dpad.center.x, dpad.top + 8f) - origin
        val right = Offset(dpad.right - 8f, dpad.center.y) - origin
        val a = rule.onNodeWithTag("game-touch-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        val b = rule.onNodeWithTag("game-touch-b", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput { down(0, up); down(1, a) }
        rule.runOnIdle {
            assertEquals(setOf(RetroPadButton.UP, RetroPadButton.A), snapshots.last())
            snapshots.clear()
        }
        // Direct jumps may skip a sampled diagonal/combo sector. The TV must receive the
        // final mask together, without a synthetic neutral frame between release and press.
        root.performTouchInput { moveTo(0, right) }
        rule.runOnIdle {
            assertEquals(listOf(setOf(RetroPadButton.RIGHT, RetroPadButton.A)), snapshots)
            snapshots.clear()
        }
        root.performTouchInput { moveTo(1, b) }
        rule.runOnIdle {
            assertEquals(listOf(setOf(RetroPadButton.RIGHT, RetroPadButton.B)), snapshots)
            snapshots.clear()
            showing = false
        }
        rule.runOnIdle {
            assertEquals(emptySet<RetroPadButton>(), snapshots.last())
            assertEquals(0, individualCallbacks)
            assertEquals(0, directionCallbacks)
        }
        root.performTouchInput { up(1); up(0) }
    }

    @Test fun portraitButtonsStayBelowThePictureAndOpeningOsdKeepsViewportStable() {
        val fixture = mount()
        val surface = rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot()
        for (button in listOf("a", "b", "x", "y", "l", "l2", "r", "r2", "select", "start")) {
            val target = rule.onNodeWithTag("game-touch-$button", useUnmergedTree = true).assertIsDisplayed()
            assertTrue("$button covers the game picture", target.getBoundsInRoot().top >= surface.bottom)
        }
        rule.runOnIdle { fixture.menuVisible = true }
        assertEquals(surface, rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot())
    }

    @Test fun changingLayoutOrOpeningMenuReleasesHeldButtons() {
        val fixture = mount()
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).performTouchInput { down(0, center) }
        rule.runOnIdle { assertTrue(RetroPadButton.A in fixture.held); fixture.mode = GameTouchMode.DUAL }
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()) }
        rule.onNodeWithTag("game-touch-root").performTouchInput { up(0) }
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).performTouchInput { down(0, center) }
        rule.runOnIdle { assertTrue(RetroPadButton.A in fixture.held); fixture.menuVisible = true }
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()); assertEquals(0, fixture.osdTaps) }
        rule.onNodeWithTag("game-touch-root").performTouchInput { up(0) }
    }

    @Test fun opacityOnlyChangesDoNotReleaseHeldButtons() {
        val fixture = mount()
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).performTouchInput { down(0, center) }
        rule.runOnIdle { fixture.style = fixture.style.copy(opacityPercent = 25) }
        rule.runOnIdle { assertEquals(setOf(RetroPadButton.A), fixture.held) }
        rule.onNodeWithTag("game-touch-root").performTouchInput { up(0) }
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()); assertEquals(0, fixture.osdTaps) }
    }

    @Test fun hidingForHardwareReleasesTouchAndPreservesTheGameViewport() {
        val fixture = mount()
        val surface = rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot()
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).performTouchInput { down(0, center) }
        rule.runOnIdle { fixture.hiddenForGamepad = true }
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()) }
        rule.onNodeWithTag("game-touch-root").performTouchInput { up(0) }
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(surface, rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot())
        rule.onNodeWithTag("game-touch-show", useUnmergedTree = true).performTouchInput { click() }
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()); assertEquals(0, fixture.osdTaps) }
    }

    @Test fun backgroundReleasesTouchesAndResumeRequiresANewContact() {
        class Owner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lateinit var owner: Owner
        rule.runOnUiThread { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        val fixture = mount(owner)
        val root = rule.onNodeWithTag("game-touch-root")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val a = rule.onNodeWithTag("game-touch-a", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput { down(0, a) }
        rule.runOnIdle {
            assertEquals(setOf(RetroPadButton.A), fixture.held)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(fixture.held.isEmpty())
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        root.performTouchInput { moveTo(0, a + Offset(1f, 0f)) }
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()) }
        root.performTouchInput { up(0); down(1, a) }
        rule.runOnIdle { assertEquals(setOf(RetroPadButton.A), fixture.held) }
        root.performTouchInput { up(1) }
        rule.runOnIdle { assertTrue(fixture.held.isEmpty()); assertEquals(0, fixture.osdTaps) }
    }
}

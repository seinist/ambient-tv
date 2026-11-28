package com.ambient.tv.ui.games

import android.app.Application
import android.view.MotionEvent
import android.view.View
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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
        var presentation by mutableStateOf(GameTouchPresentation.Generic)
        var aspect by mutableStateOf(4f / 3f)
        var fitSurface by mutableStateOf(true)
        var hiddenForGamepad by mutableStateOf(false)
        var osdTaps = 0
        val held = mutableSetOf<RetroPadButton>()
        val aTransitions = mutableListOf<Boolean>()
        val feedback = mutableListOf<Unit>()
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
                LocalGameButtonHaptic provides { fixture.feedback += Unit },
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
                            presentation = fixture.presentation, contentAspectRatio = fixture.aspect,
                            fitSurface = fixture.fitSurface,
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

    @Test fun coreActionDescriptionsDoNotAddVisibleLabelsToConsoleButtons() {
        var presentation by mutableStateOf(gameTouchPresentation("nds"))
        val action = "Core-specific action"
        rule.setContent {
            AmbientTvTheme {
                GameTouchControls(
                    mode = GameTouchMode.DPAD,
                    onButton = { _, _ -> },
                    onStick = { _, _, _ -> },
                    portrait = true,
                    applySafeInsets = false,
                    presentation = presentation,
                    coreActions = mapOf(RetroPadButton.A to action, RetroPadButton.L to action),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        for (system in listOf("nds", "snes", "psx")) {
            val current = gameTouchPresentation(system)
            rule.runOnIdle { presentation = current }
            for (button in listOf(RetroPadButton.A, RetroPadButton.L)) {
                val label = current.labels.getValue(button)
                rule.onNodeWithTag("game-touch-${button.key}", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .assertContentDescriptionEquals("$label: $action")
                rule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            }
            rule.onNodeWithText(action, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun buttonHapticsAreOptInAndNeverFollowDpadStickOrHeldMovement() {
        val fixture = mount()
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).performTouchInput { click() }
        rule.runOnIdle { assertTrue(fixture.feedback.isEmpty()); fixture.style = fixture.style.copy(buttonHaptics = true) }
        rule.onNodeWithTag("game-touch-a", useUnmergedTree = true).performTouchInput {
            down(0, center)
            moveTo(0, center + Offset(2f, 0f))
            moveTo(0, center)
            up(0)
        }
        rule.runOnIdle {
            assertEquals(1, fixture.feedback.size)
            fixture.mode = GameTouchMode.HYBRID
        }
        rule.onNodeWithTag("game-touch-dpad", useUnmergedTree = true).performTouchInput {
            down(0, Offset(8f, center.y))
            moveTo(0, Offset(8f, 8f))
            moveTo(0, center)
            up(0)
        }
        rule.onNodeWithTag("game-touch-stick-0", useUnmergedTree = true).performTouchInput {
            down(0, center)
            moveTo(0, topLeft + Offset(8f, 8f))
            up(0)
        }
        rule.runOnIdle { assertEquals(1, fixture.feedback.size) }
        rule.onNodeWithTag("game-touch-start", useUnmergedTree = true).performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(2, fixture.feedback.size)
            fixture.style = fixture.style.copy(buttonHaptics = false)
        }
        rule.onNodeWithTag("game-touch-r", useUnmergedTree = true).performTouchInput { click() }
        rule.runOnIdle { assertEquals(2, fixture.feedback.size) }
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

    private fun assertControlsOutsidePicture(tags: List<String>) {
        val surface = rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val controls = tags.map { tag ->
            val target = rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val bounds = target.getBoundsInRoot()
            assertTrue("$tag lost its touch target", bounds.right - bounds.left >= 48.dp &&
                bounds.bottom - bounds.top >= 48.dp)
            target.fetchSemanticsNode().boundsInRoot.also { assertFalse("$tag covers the game picture", it.overlaps(surface)) }
        }
        controls.forEachIndexed { index, bounds -> controls.drop(index + 1).forEach { other ->
            assertFalse("Utility controls overlap: $bounds and $other", bounds.overlaps(other))
        } }
    }

    @Test fun portraitMenuAndPadControlsStayOutsideThePictureWithoutRewindOverlay() {
        val fixture = mount()
        rule.runOnIdle { fixture.mode = GameTouchMode.DUAL }
        rule.onNodeWithTag("game-player-rewind", useUnmergedTree = true).assertDoesNotExist()
        assertControlsOutsidePicture(listOf("game-player-menu") +
            listOf("a", "b", "x", "y", "l", "l2", "r", "r2", "l3", "r3", "select", "start")
                .map { "game-touch-$it" })
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeSnesUsesFullHeightAndKeepsUtilitiesInAmbientSideSpace() {
        val fixture = mount()
        rule.runOnIdle { fixture.presentation = gameTouchPresentation("snes") }
        val root = rule.onNodeWithTag("game-touch-root").getBoundsInRoot()
        val surface = rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(root.top, surface.top)
        assertEquals(root.bottom, surface.bottom)
        assertEquals(4f / 3f, (surface.right - surface.left).value / (surface.bottom - surface.top).value, .01f)
        rule.onNodeWithTag("game-player-rewind", useUnmergedTree = true).assertDoesNotExist()
        assertControlsOutsidePicture(listOf("game-player-menu") +
            listOf("l", "r", "select", "start").map { "game-touch-$it" })
        rule.runOnIdle { fixture.menuVisible = true }
        assertEquals(surface, rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot())
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeUtilitiesReservePictureSpaceAtCustomSizesAndKeepItWhileHidden() {
        val fixture = mount()
        rule.runOnIdle { fixture.mode = GameTouchMode.DUAL }
        for (fit in listOf(true, false)) for (scale in listOf(100, 125, 140)) {
            rule.runOnIdle { fixture.style = fixture.style.copy(scalePercent = scale); fixture.fitSurface = fit }
            val surface = rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot()
            rule.onNodeWithTag("game-player-rewind", useUnmergedTree = true).assertDoesNotExist()
            assertControlsOutsidePicture(listOf("game-player-menu") +
                listOf("l", "l2", "r", "r2", "l3", "r3", "select", "start").map { "game-touch-$it" })
            rule.runOnIdle { fixture.menuVisible = true }
            assertEquals(surface, rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot())
            rule.runOnIdle { fixture.menuVisible = false; fixture.hiddenForGamepad = true }
            assertEquals(surface, rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).getBoundsInRoot())
            assertControlsOutsidePicture(listOf("game-player-menu", "game-touch-show"))
            rule.runOnIdle { fixture.hiddenForGamepad = false }
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun nativePointerInputUsesTheFittedViewsLocalCoordinatesWithoutOpeningTheMenu() {
        val positions = mutableListOf<Offset>()
        var osdTaps = 0
        lateinit var nativeView: View
        rule.setContent {
            AmbientTvTheme {
                GamePlayerTouchLayout(
                    handheld = true, directPointer = true, touchEnabled = false,
                    touchMode = GameTouchMode.DPAD, playing = true, menuVisible = false,
                    contentAspectRatio = 3f / 4f,
                    onMenu = { osdTaps++ }, onButton = { _, _ -> }, onStick = { _, _, _ -> },
                    surface = { modifier ->
                        AndroidView(modifier = modifier, factory = { context ->
                            object : View(context) {
                                override fun onTouchEvent(event: MotionEvent): Boolean {
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN)
                                        positions += Offset(event.x, event.y)
                                    return true
                                }
                            }.also { nativeView = it }
                        })
                    },
                )
            }
        }
        rule.onNodeWithTag("game-player-surface", useUnmergedTree = true).performTouchInput {
            click(center)
            click(Offset(8f, 8f))
        }
        rule.runOnIdle {
            assertEquals(2, positions.size)
            assertEquals(nativeView.width / 2f, positions[0].x, 1f)
            assertEquals(nativeView.height / 2f, positions[0].y, 1f)
            assertEquals(Offset(8f, 8f), positions[1])
            assertEquals(0, osdTaps)
        }
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

package com.ambient.tv.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.common.PlayerPinchZoom
import com.ambient.tv.ui.common.PlayerTouchLayer
import com.ambient.tv.ui.mobile.LocalHandheldUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the actual touch layer: manual choices must supersede an earlier gesture on the same player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerPinchZoomResizeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        val playerKey = Any()
        var presentation by mutableStateOf(VideoPresentation())
        var viewport by mutableStateOf(IntSize(300, 180))
        val changes = mutableListOf<VideoPresentation>()
    }

    private fun mount(): Fixture {
        val fixture = Fixture()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                Box(Modifier.size(fixture.viewport.width.dp, fixture.viewport.height.dp)) {
                    PlayerTouchLayer(
                        modifier = Modifier.testTag("picture"),
                        onToggleControls = {},
                        onTogglePlayback = {},
                        pinchZoom = PlayerPinchZoom(
                            key = fixture.playerKey,
                            presentation = fixture.presentation,
                            sourceAspectRatio = { 16f / 9f },
                            onChanged = {
                                fixture.presentation = it
                                fixture.changes += it
                            },
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun pinchOut(fixture: Fixture) {
        rule.onNodeWithTag("picture").performTouchInput {
            val start = Offset(width * 0.15f, 0f)
            val end = Offset(width * 0.30f, 0f)
            pinch(start0 = center - start, end0 = center - end,
                start1 = center + start, end1 = center + end)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("The real two-finger gesture must change zoom", fixture.presentation.zoom > 1.2f)
            assertTrue(fixture.changes.isNotEmpty())
        }
    }

    private fun resize(fixture: Fixture, size: IntSize = IntSize(260, 220)) {
        rule.runOnIdle { fixture.viewport = size }
        rule.waitForIdle()
    }

    @Test fun gestureCreatedCropResetsOnViewportResize() {
        val fixture = mount()
        pinchOut(fixture)
        resize(fixture)
        rule.runOnIdle { assertEquals(VideoPresentation(), fixture.presentation) }
    }

    @Test fun manualAspectAfterPinchingSurvivesResizeForTheSamePlayer() {
        val fixture = mount()
        pinchOut(fixture)
        val manual = VideoPresentation(aspectRatio = 4f / 3f)
        rule.runOnIdle { fixture.presentation = manual; fixture.changes.clear() }
        rule.waitForIdle()
        resize(fixture)
        rule.runOnIdle {
            assertEquals(manual, fixture.presentation)
            assertTrue("Resize must not replace a later manual aspect choice", fixture.changes.isEmpty())
        }
    }

    @Test fun manualZoomAfterPinchingSurvivesResizeForTheSamePlayer() {
        val fixture = mount()
        pinchOut(fixture)
        val manual = VideoPresentation(zoom = 1.25f)
        rule.runOnIdle { fixture.presentation = manual; fixture.changes.clear() }
        rule.waitForIdle()
        resize(fixture)
        rule.runOnIdle {
            assertEquals(manual, fixture.presentation)
            assertTrue("Resize must not replace a later manual zoom choice", fixture.changes.isEmpty())
        }
    }

    @Test fun manualOverrideDiscardsTheOldGestureMarkerAfterResize() {
        val fixture = mount()
        pinchOut(fixture)
        val oldGesture = rule.runOnIdle { fixture.presentation }
        rule.runOnIdle { fixture.presentation = VideoPresentation(aspectRatio = 4f / 3f) }
        rule.waitForIdle()
        resize(fixture)

        // The user can later choose the same numbers without making them a gesture again.
        rule.runOnIdle { fixture.presentation = oldGesture; fixture.changes.clear() }
        rule.waitForIdle()
        resize(fixture, IntSize(300, 180))
        rule.runOnIdle {
            assertEquals(oldGesture, fixture.presentation)
            assertTrue("An expired gesture marker must not affect a later resize", fixture.changes.isEmpty())
        }
    }
}

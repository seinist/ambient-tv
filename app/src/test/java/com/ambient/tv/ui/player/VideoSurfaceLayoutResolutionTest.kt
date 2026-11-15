package com.ambient.tv.ui.player

import android.app.Activity
import android.app.Application
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import com.ambient.tv.ui.display.UiRenderViewport
import com.ambient.tv.ui.display.UiRenderViewportSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/** MPV and VLC share this host; exercise its holder policy without loading either engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w3840dp-h2160dp-land-mdpi")
class VideoSurfaceLayoutResolutionTest {
    private lateinit var activityController: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var source: UiRenderViewportSource
    private lateinit var host: VideoSurfaceLayout

    @Before fun setUp() {
        ShadowDisplayManager.setNaturallyPortrait(Display.DEFAULT_DISPLAY, false)
        activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = activityController.get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        source = UiRenderViewportSource(root)
        source.update(UiRenderViewport(1920, 1080, 3840, 2160))
        host = VideoSurfaceLayout(activity).apply { sourceAspectRatio = 16f / 9f }
        root.addView(host, FrameLayout.LayoutParams(1920, 1080))
        layout(1920, 1080)
    }

    @After fun tearDown() {
        source.close()
        activityController.close()
    }

    @Test fun panelAndAutomaticWindowKeepFourKWhileExplicitUiUsesTheFullHdLayout() {
        val originalSurface = host.videoSurface
        host.renderResolutionTarget = VideoRenderTargetMode.DISPLAY
        assertEquals(3840 to 2160, host.forcedRenderSize)
        assertEquals(1920 to 1080, host.videoSurface.width to host.videoSurface.height)

        host.renderResolutionTarget = VideoRenderTargetMode.WINDOW
        assertEquals(3840 to 2160, host.forcedRenderSize)

        host.renderResolutionTarget = VideoRenderTargetMode.UI
        assertNull(host.forcedRenderSize)
        assertEquals(1920 to 1080, host.videoSurface.width to host.videoSurface.height)
        assertSame(originalSurface, host.videoSurface)
    }

    @Test fun aSmallVideoWindowKeepsItsProportionAndDoesNotExpandToTheWholePanel() {
        host.renderResolutionTarget = VideoRenderTargetMode.DISPLAY
        host.layoutParams = FrameLayout.LayoutParams(480, 270)
        layout(1920, 1080)
        assertEquals(480 to 270, host.videoSurface.width to host.videoSurface.height)
        assertEquals(960 to 540, host.forcedRenderSize)

        host.renderResolutionTarget = VideoRenderTargetMode.WINDOW
        assertEquals(960 to 540, host.forcedRenderSize)
    }

    @Test fun viewportCallbacksResizeTheSameSurfaceAndReleaseItForDirectOutputDuringRelayout() {
        val originalSurface = host.videoSurface
        host.renderResolutionTarget = VideoRenderTargetMode.WINDOW
        assertEquals(3840 to 2160, host.forcedRenderSize)

        source.update(UiRenderViewport(3840, 2160, 3840, 2160))
        source.onPreDraw()
        assertEquals(3840 to 2160, host.forcedRenderSize)
        host.layoutParams = FrameLayout.LayoutParams(3840, 2160)
        layout(3840, 2160)
        assertNull(host.forcedRenderSize)

        host.renderResolutionTarget = VideoRenderTargetMode.DISPLAY
        assertEquals(3840 to 2160, host.forcedRenderSize)
        source.update(UiRenderViewport(1920, 1080, 3840, 2160))
        host.renderResolutionTarget = VideoRenderTargetMode.UI
        assertNull(host.forcedRenderSize)
        assertSame(originalSurface, host.videoSurface)
    }

    private fun layout(width: Int, height: Int) {
        root.layoutParams = root.layoutParams.apply { this.width = width; this.height = height }
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        source.onPreDraw()
    }
}

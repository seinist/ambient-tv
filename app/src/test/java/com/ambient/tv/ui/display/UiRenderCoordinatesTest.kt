package com.ambient.tv.ui.display

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class UiRenderCoordinatesTest {
    @Test fun resizeDoesNotExposeNewProjectionAlongsideOldChildDimensions() {
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        val child = View(root.context)
        root.addView(child, FrameLayout.LayoutParams(-1, -1))
        val source = UiRenderViewportSource(root)
        val observed = mutableListOf<UiRenderViewport?>()
        val registration = source.observe { observed += child.uiRenderViewport() }
        try {
            source.update(UiRenderViewport(3840, 2160, 3840, 2160))
            assertTrue(child.isUiRenderHosted())
            assertNull(child.uiRenderViewport())
            layout(root, 3840, 2160)
            source.onPreDraw()
            assertEquals(1, observed.size)
            assertEquals(3840, child.uiRenderViewport()?.renderWidth)

            source.update(UiRenderViewport(1920, 1080, 3840, 2160))
            assertEquals(3840, child.width)
            assertNull(child.uiRenderViewport())
            source.onPreDraw()
            assertEquals("Old dimensions cannot be notified with a new 1080p projection", 1, observed.size)
            layout(root, 1920, 1080)
            source.onPreDraw()
            assertEquals(2, observed.size)
            assertEquals(1920, child.width)
            assertEquals(UiRenderViewport(1920, 1080, 3840, 2160), child.uiRenderViewport())
            source.onPreDraw()
            assertEquals("A settled frame is not a repeating change notification", 2, observed.size)
        } finally {
            registration.close()
            source.close()
        }
        assertFalse(child.isUiRenderHosted())
        assertNull(child.uiRenderViewport())
    }

    @Test fun detachInvalidatesCoordinatesUntilTheRecreatedRootHasDrawn() {
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        val source = UiRenderViewportSource(root)
        source.update(UiRenderViewport(1920, 1080, 3840, 2160))
        layout(root, 1920, 1080)
        source.onPreDraw()
        assertEquals(1920, root.uiRenderViewport()?.renderWidth)
        source.onViewDetachedFromWindow(root)
        assertTrue(root.isUiRenderHosted())
        assertNull(root.uiRenderViewport())
        source.onPreDraw()
        assertEquals(1920, root.uiRenderViewport()?.renderWidth)
        source.close()
    }

    @Test fun displayChangeCannotRepublishStaleCoordinatesBeforeItsNewMetricsArrive() {
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        val source = UiRenderViewportSource(root)
        val initial = UiRenderViewport(1920, 1080, 3840, 2160)
        source.update(initial)
        layout(root, 1920, 1080)
        source.onPreDraw()
        source.invalidate()
        source.onPreDraw()
        assertNull(root.uiRenderViewport())
        source.update(initial) // A refresh-only display change can retain exactly the same size.
        source.onPreDraw()
        assertEquals(initial, root.uiRenderViewport())
        source.close()
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}

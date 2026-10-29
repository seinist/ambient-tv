package com.ambient.tv.ui.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Point
import android.os.Looper
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/** Exercises real display/attach callbacks; the holder records requests rather than allocating EGL buffers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1920dp-h1080dp-land-mdpi")
class Media3SurfaceResolutionControllerTest {
    private lateinit var activityController: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var container: FrameLayout
    private val controllers = mutableListOf<Media3SurfaceResolutionController>()
    private val panel = Media3SurfaceResolutionPolicy(targetAtDisplayResolution = true, fixSurfaceSize = true)

    @Before fun setUp() {
        ShadowDisplayManager.setNaturallyPortrait(Display.DEFAULT_DISPLAY, false)
        activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = activityController.get()
        container = FrameLayout(activity)
        activity.setContentView(container)
        idle()
    }

    @After fun tearDown() {
        controllers.forEach { it.release() }
        activityController.close()
    }

    @Test fun displayModeEventResizesTheBufferWithoutResizingTheLogicalView() {
        val view = newSurface()
        val display = checkNotNull(view.display)
        changePhysicalMode(display, 1920, 1080)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)
        controller.update(panel)
        targets.clear()
        view.recording.fixedRequests.clear()
        var viewResizes = 0
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) viewResizes++
        }

        changePhysicalMode(display, 3840, 2160)

        assertEquals(1920 to 1080, logicalSize(display))
        assertEquals(1920 to 1080, view.width to view.height)
        assertEquals(0, viewResizes)
        assertEquals(listOf(3840 to 2160), targets)
        assertEquals(listOf(3840 to 2160), view.recording.fixedRequests)

        changePhysicalMode(display, 1920, 1080)
        assertEquals(1920 to 1080, targets.last())
        assertEquals(1920 to 1080, view.recording.fixedRequests.last())
        assertEquals(0, viewResizes)
    }

    @Test fun disabledProcessingPublishesFourKWithoutFixingTheNativeSurface() {
        val view = newSurface()
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)
        val preview = panel.copy(fixSurfaceSize = false)

        controller.update(preview)
        assertEquals(listOf(3840 to 2160), targets)
        assertTrue(view.recording.fixedRequests.isEmpty())
        assertEquals(0, view.recording.layoutRequests)

        controller.update(panel)
        assertEquals(listOf(3840 to 2160), view.recording.fixedRequests)
        assertEquals(1, targets.size)

        controller.update(preview)
        controller.update(preview)
        assertEquals(1, view.recording.layoutRequests)
        assertEquals(3840 to 2160, targets.last())

        controller.update(Media3SurfaceResolutionPolicy(false, false))
        assertEquals(1920 to 1080, targets.last())
    }

    @Test fun detachedViewsIgnoreModeChangesAndReattachWhileReleasedControllersStayInactive() {
        val view = newSurface()
        val display = checkNotNull(view.display)
        changePhysicalMode(display, 3840, 2160)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)
        controller.update(panel)

        container.removeView(view)
        idle()
        assertFalse(view.isAttachedToWindow)
        assertNull(targets.last())
        targets.clear()
        view.recording.fixedRequests.clear()
        changePhysicalMode(display, 1920, 1080)
        assertTrue(targets.isEmpty())
        assertTrue(view.recording.fixedRequests.isEmpty())

        attach(view, 1920, 1080)
        assertEquals(1920 to 1080, targets.last())
        assertEquals(1920 to 1080, view.recording.fixedRequests.last())

        // A recreated native surface needs the same fixed size reapplied even with no mode change.
        container.removeView(view)
        idle()
        view.recording.fixedRequests.clear()
        attach(view, 1920, 1080)
        assertEquals(listOf(1920 to 1080), view.recording.fixedRequests)

        controller.release()
        assertNull(targets.last())
        assertEquals(1, view.recording.layoutRequests)
        targets.clear()
        view.recording.fixedRequests.clear()
        changePhysicalMode(display, 3840, 2160)
        controller.update(panel)
        container.removeView(view)
        attach(view, 1920, 1080)
        assertTrue(targets.isEmpty())
        assertTrue(view.recording.fixedRequests.isEmpty())
    }

    @Test fun reattachingTheOriginalViewReclaimsGeometryAfterAnotherOwner() {
        val geometry = Media3RenderGeometry()
        geometry.updateSurface(1920, 1080)
        val ownerA = Any()
        val ownerB = Any()
        val viewA = newSurface()
        changePhysicalMode(checkNotNull(viewA.display), 3840, 2160)
        val controllerA = ownedController(viewA, ownerA, geometry)
        controllerA.update(panel)
        assertEquals(3840 to 2160, geometry.requestedTarget)

        container.removeView(viewA)
        idle()
        assertNull(geometry.requestedTarget)

        val viewB = newSurface(480, 270)
        val controllerB = ownedController(viewB, ownerB, geometry)
        controllerB.update(panel)
        assertEquals(960 to 540, geometry.requestedTarget)

        container.removeView(viewB)
        idle()
        assertNull(geometry.requestedTarget)
        attach(viewA, 1920, 1080)
        assertEquals(3840 to 2160, geometry.requestedTarget)

        // A late release from the replacement view must not clear the reclaimed owner.
        controllerB.release()
        assertEquals(3840 to 2160, geometry.requestedTarget)
        assertEquals(1920 to 1080, geometry.confirmedSurface)
    }

    private fun controller(view: SurfaceView, targets: MutableList<Pair<Int, Int>?>) =
        Media3SurfaceResolutionController(view, Any(), { targets.add(it) }).also(controllers::add)

    private fun ownedController(view: SurfaceView, owner: Any, geometry: Media3RenderGeometry) =
        Media3SurfaceResolutionController(
            view = view,
            owner = owner,
            onTargetChanged = { geometry.updateTarget(owner, it) },
            onAttached = { geometry.attach(owner) },
            onDetached = { geometry.detach(owner) },
        ).also(controllers::add)

    private fun newSurface(width: Int = 1920, height: Int = 1080) =
        RecordingSurfaceView(activity).also { attach(it, width, height) }

    private fun attach(view: SurfaceView, width: Int, height: Int) {
        container.addView(view, FrameLayout.LayoutParams(width, height))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        idle()
        assertTrue(view.isAttachedToWindow)
        assertEquals(width to height, view.width to view.height)
    }

    private fun changePhysicalMode(display: Display, width: Int, height: Int) {
        // Replacing the active mode retains DisplayInfo.logicalWidth/Height. Robolectric sends
        // the real DisplayListener callback; the test never calls the controller's callback.
        val mode = ShadowDisplayManager.ModeBuilder.modeBuilder(display.mode.modeId)
            .setWidth(width).setHeight(height).setRefreshRate(60f).build()
        ShadowDisplayManager.setSupportedModes(display.displayId, mode)
        idle()
        assertEquals(width to height, display.mode.physicalWidth to display.mode.physicalHeight)
    }

    @Suppress("DEPRECATION")
    private fun logicalSize(display: Display): Pair<Int, Int> =
        Point().also(display::getRealSize).let { it.x to it.y }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Robolectric's default holder ignores sizing requests, so record this public API boundary. */
    private class RecordingSurfaceView(context: Context) : SurfaceView(context) {
        private var recordedHolder: RecordingHolder? = null
        init { recordedHolder = RecordingHolder(super.getHolder()) }
        val recording: RecordingHolder get() = checkNotNull(recordedHolder)
        override fun getHolder(): SurfaceHolder = recordedHolder ?: super.getHolder()
    }

    private class RecordingHolder(private val delegate: SurfaceHolder) : SurfaceHolder by delegate {
        val fixedRequests = mutableListOf<Pair<Int, Int>>()
        var layoutRequests = 0
        override fun setFixedSize(width: Int, height: Int) {
            fixedRequests.add(width to height)
            delegate.setFixedSize(width, height)
        }
        override fun setSizeFromLayout() {
            layoutRequests++
            delegate.setSizeFromLayout()
        }
    }
}

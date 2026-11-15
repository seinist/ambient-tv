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
import com.ambient.tv.ui.display.UiRenderViewport
import com.ambient.tv.ui.display.UiRenderViewportSource
import com.ambient.tv.ui.display.uiRenderViewport
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
    private val viewportSources = mutableListOf<UiRenderViewportSource>()
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
        viewportSources.forEach { it.close() }
        activityController.close()
    }

    @Test
    @Config(qualifiers = "w3840dp-h2160dp-land-mdpi")
    fun fullHdUiKeepsPanelAndAutomaticVideoAtTheOriginalFourKWindow() {
        val source = hostedViewport(UiRenderViewport(1920, 1080, 3840, 2160))
        val view = newSurface()
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        settleViewport(source, 1920, 1080)
        assertEquals(3840 to 2160, logicalSize(checkNotNull(view.display)))
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)

        controller.update(panel)
        assertEquals(3840 to 2160, targets.last())
        assertEquals(listOf(3840 to 2160), view.recording.fixedRequests)

        controller.update(Media3SurfaceResolutionPolicy(false, true, targetAtWindowResolution = true))
        assertEquals(3840 to 2160, targets.last())
        assertEquals(1, view.recording.fixedRequests.size)

        controller.update(Media3SurfaceResolutionPolicy(false, false))
        assertEquals(1920 to 1080, targets.last())
        assertEquals(1, view.recording.layoutRequests)
        assertEquals(1920 to 1080, view.width to view.height)
    }

    @Test
    @Config(qualifiers = "w3840dp-h2160dp-land-mdpi")
    fun removingLastEffectAfterPanelToAutoKeepsFourKBufferBehindFullHdUi() {
        val source = hostedViewport(UiRenderViewport(1920, 1080, 3840, 2160))
        val view = newSurface()
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        settleViewport(source, 1920, 1080)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)

        controller.update(outputPolicy(resolutionKey = "panel"))
        controller.update(outputPolicy(gpuPipelineInitialized = true))
        controller.update(outputPolicy(processingEnabled = false, gpuPipelineInitialized = true))

        assertEquals(listOf(3840 to 2160), view.recording.fixedRequests)
        assertEquals(0, view.recording.layoutRequests)
        assertEquals(3840 to 2160, targets.last())
        assertEquals(1920 to 1080, view.width to view.height)

        // Only the explicit UI choice allows that retained sink to use the smaller UI buffer.
        controller.update(outputPolicy(resolutionKey = "ui", processingEnabled = false,
            gpuPipelineInitialized = true))
        assertEquals(1, view.recording.layoutRequests)
        assertEquals(1920 to 1080, targets.last())
    }

    @Test fun retainedEmptySinkUsesWindowBufferWhileScalerPreviewStaysAtPanel() {
        val source = hostedViewport(UiRenderViewport(960, 540, 1920, 1080))
        val view = newSurface(960, 540)
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        settleViewport(source, 960, 540)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)

        controller.update(outputPolicy(resolutionKey = "panel", gpuPipelineInitialized = true))
        controller.update(outputPolicy(processingEnabled = false, gpuPipelineInitialized = true))

        // Auto sheds the panel workload, while compensating for the independently scaled UI.
        assertEquals(listOf(3840 to 2160, 1920 to 1080), view.recording.fixedRequests)
        assertEquals(0, view.recording.layoutRequests)
        assertEquals(listOf(3840 to 2160), targets)

        controller.update(outputPolicy(gpuPipelineInitialized = true))
        assertEquals(3840 to 2160, view.recording.fixedRequests.last())
        assertEquals(listOf(3840 to 2160), targets)
    }

    @Test
    @Config(qualifiers = "w3840dp-h2160dp-land-mdpi")
    fun reducedAndFailedEffectsKeepRetainedBufferButNeverInitializedOutputStaysDirect() {
        val source = hostedViewport(UiRenderViewport(1920, 1080, 3840, 2160))
        val view = newSurface()
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        settleViewport(source, 1920, 1080)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)

        for (blocked in listOf(
            outputPolicy(processingEnabled = false),
            outputPolicy(reducedEffects = true),
            outputPolicy(effectsFailed = true),
            outputPolicy(sdrEffectsAllowed = false),
        )) controller.update(blocked)
        assertTrue(view.recording.fixedRequests.isEmpty())
        assertEquals(0, view.recording.layoutRequests)

        controller.update(outputPolicy(gpuPipelineInitialized = true))
        controller.update(outputPolicy(reducedEffects = true, gpuPipelineInitialized = true))
        controller.update(outputPolicy(effectsFailed = true, gpuPipelineInitialized = true))
        assertEquals(listOf(3840 to 2160), view.recording.fixedRequests)
        assertEquals(0, view.recording.layoutRequests)
        assertEquals(3840 to 2160, targets.last())
    }

    @Test
    @Config(qualifiers = "w3840dp-h2160dp-land-mdpi")
    fun hostedMiniPlayerKeepsItsFractionOfTheWindowAndDirectOutputUnconstrained() {
        val source = hostedViewport(UiRenderViewport(1920, 1080, 3840, 2160))
        val view = newSurface(480, 270)
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        settleViewport(source, 1920, 1080)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)

        controller.update(panel.copy(fixSurfaceSize = false))
        assertEquals(960 to 540, targets.last())
        assertTrue(view.recording.fixedRequests.isEmpty())
        assertEquals(0, view.recording.layoutRequests)

        controller.update(panel)
        assertEquals(listOf(960 to 540), view.recording.fixedRequests)
        assertEquals(480 to 270, view.width to view.height)

        controller.update(Media3SurfaceResolutionPolicy(false, false, targetAtWindowResolution = true))
        assertEquals(960 to 540, targets.last())
        assertEquals(1, view.recording.layoutRequests)
    }

    @Test
    @Config(qualifiers = "w3840dp-h2160dp-land-mdpi")
    fun pendingUiRelayoutNeverCombinesNewScaleWithOldViewDimensions() {
        val source = hostedViewport(UiRenderViewport(3840, 2160, 3840, 2160))
        val view = newSurface(3840, 2160)
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        settleViewport(source, 3840, 2160)
        val targets = mutableListOf<Pair<Int, Int>?>()
        val controller = controller(view, targets)
        controller.update(panel)
        view.recording.fixedRequests.clear()
        targets.clear()

        source.update(UiRenderViewport(1920, 1080, 3840, 2160))
        controller.update(panel)
        source.onPreDraw()
        assertNull(view.uiRenderViewport())
        assertTrue(view.recording.fixedRequests.isEmpty())
        assertTrue(targets.isEmpty())

        // A direct decoder must release the old GPU constraint even before layout settles.
        controller.update(panel.copy(fixSurfaceSize = false))
        assertEquals(1, view.recording.layoutRequests)
        controller.update(panel)
        view.layoutParams = FrameLayout.LayoutParams(1920, 1080)
        settleViewport(source, 1920, 1080)

        assertEquals(1920 to 1080, view.width to view.height)
        assertEquals(listOf(3840 to 2160), view.recording.fixedRequests)
        assertTrue(view.recording.fixedRequests.none { it.first > 3840 || it.second > 2160 })
    }

    @Test
    // API 29 is the app's minimum SDK and the supported pre-SurfaceControlViewHost fallback.
    @Config(sdk = [29, 34])
    fun automaticWindowTargetKeepsTheUnhostedFallbackSurfaceAtItsLayoutSize() {
        val view = newSurface()
        changePhysicalMode(checkNotNull(view.display), 3840, 2160)
        val targets = mutableListOf<Pair<Int, Int>?>()
        controller(view, targets).update(
            Media3SurfaceResolutionPolicy(false, true, targetAtWindowResolution = true),
        )

        assertEquals(listOf(1920 to 1080), targets)
        assertTrue(view.recording.fixedRequests.isEmpty())
        assertEquals(0, view.recording.layoutRequests)
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

    @Test fun directOutputPublishesFourKWithoutFixingTheNativeSurface() {
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

    private fun outputPolicy(
        resolutionKey: String = "auto",
        processingEnabled: Boolean = true,
        reducedEffects: Boolean = false,
        effectsFailed: Boolean = false,
        sdrEffectsAllowed: Boolean = true,
        gpuPipelineInitialized: Boolean = false,
    ) = media3SurfaceResolutionPolicy(
        resolutionKey = resolutionKey,
        lowEndDevice = false,
        gpuEffectsSupported = true,
        sdrEffectsAllowed = sdrEffectsAllowed,
        processingEnabled = processingEnabled,
        reducedEffects = reducedEffects,
        effectsFailed = effectsFailed,
        gpuPipelineInitialized = gpuPipelineInitialized,
    )

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

    private fun hostedViewport(viewport: UiRenderViewport): UiRenderViewportSource =
        UiRenderViewportSource(container).also { source ->
            viewportSources += source
            source.update(viewport)
            settleViewport(source, viewport.renderWidth, viewport.renderHeight)
        }

    private fun settleViewport(source: UiRenderViewportSource, width: Int, height: Int) {
        container.layoutParams = container.layoutParams.apply { this.width = width; this.height = height }
        container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        container.layout(0, 0, width, height)
        source.onPreDraw()
    }

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

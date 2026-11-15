package com.ambient.tv.ui.player

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import com.ambient.tv.ui.display.observeUiRenderViewport

/** Tracks physical display changes even when Android TV keeps the same logical UI dimensions. */
internal class Media3SurfaceResolutionController(
    private val view: SurfaceView,
    val owner: Any,
    private val onTargetChanged: (Pair<Int, Int>?) -> Unit,
    private val onAttached: () -> Unit = {},
    private val onDetached: () -> Unit = {},
) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener, DisplayManager.DisplayListener {
    private val displayManager = view.context.getSystemService(DisplayManager::class.java)
    private var policy: Media3SurfaceResolutionPolicy? = null
    private var observingDisplay = false
    private var released = false
    private var fixedBufferSize: Pair<Int, Int>? = null
    private var publishedTarget: Pair<Int, Int>? = null
    private var viewportObservation: AutoCloseable? = null

    init {
        view.addOnAttachStateChangeListener(this)
        view.addOnLayoutChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun update(next: Media3SurfaceResolutionPolicy) {
        if (released) return
        policy = next
        updateRenderTarget()
    }

    override fun onViewAttachedToWindow(v: View) {
        if (released) return
        onAttached()
        observeDisplay()
        if (viewportObservation == null) viewportObservation = view.observeUiRenderViewport { updateRenderTarget() }
        updateRenderTarget(reapply = true)
    }

    override fun onViewDetachedFromWindow(v: View) {
        if (released) return
        stopObservingDisplay()
        viewportObservation?.close()
        viewportObservation = null
        publish(null)
        onDetached()
    }

    override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateRenderTarget()
    }

    override fun onDisplayAdded(displayId: Int) = updateRenderTarget()
    override fun onDisplayRemoved(displayId: Int) = updateRenderTarget()
    override fun onDisplayChanged(displayId: Int) {
        if (view.display?.displayId == displayId) updateRenderTarget()
    }

    fun release() {
        if (released) return
        released = true
        view.removeOnAttachStateChangeListener(this)
        view.removeOnLayoutChangeListener(this)
        stopObservingDisplay()
        viewportObservation?.close()
        viewportObservation = null
        restoreLayoutSize()
        publish(null)
        onDetached()
    }

    private fun observeDisplay() {
        if (observingDisplay || displayManager == null) return
        displayManager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        observingDisplay = true
    }

    private fun stopObservingDisplay() {
        if (!observingDisplay) return
        displayManager?.unregisterDisplayListener(this)
        observingDisplay = false
    }

    private fun updateRenderTarget(reapply: Boolean = false) {
        if (released || !view.isAttachedToWindow) return
        val current = policy ?: return
        // Drop a previous GPU constraint immediately when processing becomes ineligible,
        // including during an embedded UI relayout before the next target is measurable.
        if (!current.fixSurfaceSize) restoreLayoutSize()
        val target = view.videoRenderTargetSize(current.targetMode) ?: return
        val fixBuffer = current.fixesBuffer(view.hasScaledUiRenderViewport())
        if (!fixBuffer) restoreLayoutSize()
        // Advertise the intended viewport before the GPU path is enabled, without changing the
        // direct decoder surface. Actual effect sizes still come from onSurfaceSizeChanged.
        publish(target)
        if (fixBuffer && (reapply || target != fixedBufferSize)) {
            fixedBufferSize = target
            view.holder.setFixedSize(target.first, target.second)
        }
    }

    private fun restoreLayoutSize() {
        if (fixedBufferSize == null) return
        fixedBufferSize = null
        view.holder.setSizeFromLayout()
    }

    private fun publish(target: Pair<Int, Int>?) {
        if (publishedTarget == target) return
        publishedTarget = target
        onTargetChanged(target)
    }
}

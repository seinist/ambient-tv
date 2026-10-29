package com.ambient.tv.ui.player

import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.View

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
        updateRenderTarget(reapply = true)
    }

    override fun onViewDetachedFromWindow(v: View) {
        if (released) return
        stopObservingDisplay()
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

    @Suppress("DEPRECATION")
    private fun updateRenderTarget(reapply: Boolean = false) {
        if (released || !view.isAttachedToWindow) return
        val current = policy ?: return
        if (!current.fixSurfaceSize) restoreLayoutSize()
        val target = if (current.targetAtDisplayResolution) {
            val output = view.display ?: return
            val logical = Point().also(output::getRealSize)
            val mode = output.mode
            val rotated = output.rotation == Surface.ROTATION_90 || output.rotation == Surface.ROTATION_270
            physicalVideoRenderSize(view.width, view.height, logical.x, logical.y,
                if (rotated) mode.physicalHeight else mode.physicalWidth,
                if (rotated) mode.physicalWidth else mode.physicalHeight)
        } else {
            (view.width to view.height).takeIf { it.first > 0 && it.second > 0 }
        }
        // Advertise the intended viewport before the GPU path is enabled, without changing the
        // direct decoder surface. Actual effect sizes still come from onSurfaceSizeChanged.
        publish(target)
        if (current.fixSurfaceSize && target != null && (reapply || target != fixedBufferSize)) {
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

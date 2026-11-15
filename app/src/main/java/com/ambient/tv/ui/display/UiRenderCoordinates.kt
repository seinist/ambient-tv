package com.ambient.tv.ui.display

import android.view.View
import android.view.ViewTreeObserver
import com.ambient.tv.R
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import kotlin.math.roundToInt

/**
 * The embedded ViewRoot is measured in [renderWidth]/[renderHeight] pixels. Its enclosing
 * SurfaceView occupies [windowWidth]/[windowHeight] pixels in the ordinary Activity window.
 * Neither size describes a video decoder buffer or changes the selected display mode.
 */
internal data class UiRenderViewport(
    val renderWidth: Int,
    val renderHeight: Int,
    val windowWidth: Int,
    val windowHeight: Int,
) {
    init {
        require(renderWidth > 0 && renderHeight > 0 && windowWidth > 0 && windowHeight > 0)
    }
}

/** Maps the visible outer window area to the active display mode, including rotated displays. */
internal fun resolveUiRenderViewport(
    modeKey: String,
    windowWidth: Int,
    windowHeight: Int,
    displayLogicalWidth: Int,
    displayLogicalHeight: Int,
    displayPhysicalWidth: Int,
    displayPhysicalHeight: Int,
): UiRenderViewport? {
    if (windowWidth <= 0 || windowHeight <= 0) return null
    val hasDisplaySize = displayLogicalWidth > 0 && displayLogicalHeight > 0 &&
        displayPhysicalWidth > 0 && displayPhysicalHeight > 0
    val physicalWindow = if (hasDisplaySize) {
        val rotate = (displayLogicalWidth > displayLogicalHeight) !=
            (displayPhysicalWidth > displayPhysicalHeight)
        val physicalWidth = if (rotate) displayPhysicalHeight else displayPhysicalWidth
        val physicalHeight = if (rotate) displayPhysicalWidth else displayPhysicalHeight
        // This is the outer window's fraction of the display, not an assumption that every
        // embedded host (dialogs included) covers the whole panel.
        (windowWidth.toDouble() * physicalWidth / displayLogicalWidth).roundToInt()
            .coerceIn(1, physicalWidth) to
            (windowHeight.toDouble() * physicalHeight / displayLogicalHeight).roundToInt()
                .coerceIn(1, physicalHeight)
    } else windowWidth to windowHeight
    val size = UiRenderResolutionPolicy.targetSize(
        modeKey, windowWidth, windowHeight, physicalWindow.first, physicalWindow.second,
    ) ?: return null
    return UiRenderViewport(size.first, size.second, windowWidth, windowHeight)
}

/**
 * Publishes coordinates only after the embedded hierarchy has completed its matching layout.
 * Updating the target invalidates the old coordinates immediately: using a still-4K child with
 * a newly-1080p viewport could otherwise briefly request an 8K video surface.
 */
internal class UiRenderViewportSource(private val root: View) :
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener, AutoCloseable {
    private var requested: UiRenderViewport? = null
    private var settled: UiRenderViewport? = null
    private var observer: ViewTreeObserver? = null
    private val listeners = linkedSetOf<() -> Unit>()
    private var closed = false

    val viewport: UiRenderViewport?
        get() = settled?.takeIf { root.width == it.renderWidth && root.height == it.renderHeight }

    init {
        check(root.getTag(R.id.ui_render_viewport_source) == null)
        root.setTag(R.id.ui_render_viewport_source, this)
        root.addOnAttachStateChangeListener(this)
        if (root.isAttachedToWindow) listenForDraw()
    }

    fun update(viewport: UiRenderViewport) {
        check(!closed)
        if (requested == viewport) return
        requested = viewport
        settled = null
        root.invalidate()
    }

    /** Display metrics may change before the embedded ViewRoot receives its corresponding layout. */
    fun invalidate() {
        if (closed) return
        requested = null
        settled = null
    }

    fun observe(onChanged: () -> Unit): AutoCloseable {
        check(!closed)
        listeners += onChanged
        if (viewport != null) onChanged()
        return AutoCloseable { listeners -= onChanged }
    }

    override fun onPreDraw(): Boolean {
        val target = requested
        if (!closed && target != null && root.width == target.renderWidth &&
            root.height == target.renderHeight && !root.isLayoutRequested && settled != target
        ) {
            settled = target
            listeners.toList().forEach { it() }
        }
        return true
    }

    override fun onViewAttachedToWindow(view: View) = listenForDraw()

    override fun onViewDetachedFromWindow(view: View) {
        stopListeningForDraw()
        settled = null
    }

    private fun listenForDraw() {
        stopListeningForDraw()
        observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    private fun stopListeningForDraw() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
    }

    override fun close() {
        if (closed) return
        closed = true
        stopListeningForDraw()
        root.removeOnAttachStateChangeListener(this)
        if (root.getTag(R.id.ui_render_viewport_source) === this) {
            root.setTag(R.id.ui_render_viewport_source, null)
        }
        requested = null
        settled = null
        listeners.clear()
    }
}

private fun View.uiRenderViewportSource(): UiRenderViewportSource? {
    var current: View? = this
    while (current != null) {
        (current.getTag(R.id.ui_render_viewport_source) as? UiRenderViewportSource)?.let { return it }
        current = current.parent as? View
    }
    return null
}

/** Null also means that a hosted hierarchy is between two differently sized layouts. */
internal fun View.uiRenderViewport(): UiRenderViewport? = uiRenderViewportSource()?.viewport

/** Distinguishes a pending embedded layout from the ordinary system-rendered window. */
internal fun View.isUiRenderHosted(): Boolean = uiRenderViewportSource() != null

/** Rebinds when an AndroidView moves between the embedded root and the native fallback root. */
internal fun View.observeUiRenderViewport(onChanged: () -> Unit): AutoCloseable {
    val view = this
    val listener = object : View.OnAttachStateChangeListener, AutoCloseable {
        private var registration: AutoCloseable? = null
        private var closed = false

        override fun onViewAttachedToWindow(v: View) {
            if (closed) return
            registration?.close()
            val source = v.uiRenderViewportSource()
            registration = source?.observe(onChanged)
            if (source == null) onChanged()
        }

        override fun onViewDetachedFromWindow(v: View) {
            registration?.close()
            registration = null
        }

        override fun close() {
            if (closed) return
            closed = true
            registration?.close()
            registration = null
            view.removeOnAttachStateChangeListener(this)
        }
    }
    addOnAttachStateChangeListener(listener)
    if (isAttachedToWindow) listener.onViewAttachedToWindow(this)
    return listener
}

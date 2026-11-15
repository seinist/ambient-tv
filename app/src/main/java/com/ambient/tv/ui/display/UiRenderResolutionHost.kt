package com.ambient.tv.ui.display

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ambient.tv.data.local.UiRenderResolutionPolicy

/** The continuation dispatches to Compose once without re-entering the Activity's input hook. */
internal class UiRenderHostCallbacks(
    val onViewportChanged: (UiRenderViewport) -> Unit = {},
    val dispatchKeyEvent: (KeyEvent, () -> Boolean) -> Boolean = { _, dispatch -> dispatch() },
    val dispatchTouchEvent: (MotionEvent, () -> Boolean) -> Boolean = { _, dispatch -> dispatch() },
    val dispatchGenericMotionEvent: (MotionEvent, () -> Boolean) -> Boolean = { _, dispatch -> dispatch() },
    val onWindowFocusChanged: (Boolean) -> Unit = {},
    val onFailure: (Throwable) -> Unit,
)

/**
 * A normal Android ViewRoot rendered at a real, independently sized buffer, then composed into
 * the full outer viewport. The ordinary system path must not construct this host.
 *
 * Rendering uses public API-30 methods: setView(view, width, height), relayout(width, height),
 * SurfaceHolder.setFixedSize and SurfaceView.setChildSurfacePackage. The package remains a
 * child of SurfaceView's scaled layer, so nested video SurfaceViews retain independent buffers.
 * The interactive host requires API 31: API 30 embedded input channels explicitly cannot receive
 * keys or window focus. API 31 adds SurfaceView's focus transfer to the embedded window and IME.
 *
 * The public SurfaceControlViewHost API cannot select this ViewRoot's colorMode (including the
 * API-37 LayoutParams). Its Canvas must therefore be treated as SDR by the caller; the native
 * Activity's HDR window mode is not inherited. This does not change nested video surface HDR.
 *
 * Supply an unattached ComposeView before its first composition. The default parent recomposer
 * belongs to the stable native window decor, not the replaceable embedded ViewRoot. Dialogs
 * supply their remembered parent composition context to preserve composition locals.
 */
@RequiresApi(31)
internal class UiRenderResolutionHost(
    private val activity: ComponentActivity,
    modeKey: String,
    private val contentView: ComposeView,
    private val callbacks: UiRenderHostCallbacks,
    window: Window = activity.window,
    parentCompositionContext: CompositionContext? = null,
    ownerView: View? = null,
    private val interactive: Boolean = true,
) : FrameLayout(activity), AutoCloseable {
    private val modeKey = UiRenderResolutionPolicy.normalize(modeKey)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private val embeddedRoot = UiRenderInputRoot(activity, window, callbacks, interactive)
    private val coordinates = UiRenderViewportSource(embeddedRoot)
    private val surfaceView = SurfaceView(activity)
    private val contentLifecycle = (window.callback as? LifecycleOwner) ?: ownerView?.findViewTreeLifecycleOwner()
        ?: contentView.findViewTreeLifecycleOwner() ?: activity
    private var host: SurfaceControlViewHost? = null
    private var hostedDisplayId: Int? = null
    private var hostedRenderSize: Pair<Int, Int>? = null
    private var surfaceAvailable = false
    private var displayListenerRegistered = false
    private var fixedSize: Pair<Int, Int>? = null
    private var focusFrameCallback: Runnable? = null
    private var closed = false
    private var failed = false

    var viewport: UiRenderViewport? = null
        private set

    /** The live embedded input tree; its coordinates already use the render viewport. */
    val inputRoot: View?
        get() = embeddedRoot.takeIf {
            interactive && !closed && !failed && host != null && surfaceAvailable &&
                it.isAttachedToWindow && it.isShown
        }

    private val refresh = Runnable { updateViewportAndHost() }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) close()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleRefresh()
        override fun onDisplayRemoved(displayId: Int) = scheduleRefresh()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display?.displayId || displayId == hostedDisplayId) {
                coordinates.invalidate()
                scheduleRefresh()
            }
        }
    }
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceAvailable = true
            updateViewportAndHost()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            updateViewportAndHost()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceAvailable = false
            embeddedRoot.visibility = INVISIBLE
            // SurfaceView retains and reparents its child SurfacePackage when its surface comes
            // back. Keeping the ViewRoot here preserves focus and composition across backgrounding.
        }
    }

    init {
        require(this.modeKey != UiRenderResolutionPolicy.SYSTEM) { "System UI uses the native window" }
        require(contentView.parent == null) { "The UI content must be unattached" }
        setBackgroundColor(Color.TRANSPARENT)

        val viewModelOwner = ownerView?.findViewTreeViewModelStoreOwner()
            ?: contentView.findViewTreeViewModelStoreOwner() ?: activity
        val savedStateOwner = (window.callback as? SavedStateRegistryOwner) ?: ownerView?.findViewTreeSavedStateRegistryOwner()
            ?: contentView.findViewTreeSavedStateRegistryOwner() ?: activity
        val backOwner = (window.callback as? OnBackPressedDispatcherOwner) ?: ownerView?.findViewTreeOnBackPressedDispatcherOwner()
            ?: contentView.findViewTreeOnBackPressedDispatcherOwner() ?: activity
        for (view in listOf(embeddedRoot, contentView)) {
            view.setViewTreeLifecycleOwner(contentLifecycle)
            view.setViewTreeViewModelStoreOwner(viewModelOwner)
            view.setViewTreeSavedStateRegistryOwner(savedStateOwner)
            view.setViewTreeOnBackPressedDispatcherOwner(backOwner)
        }
        contentView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        contentView.setParentCompositionContext(
            parentCompositionContext ?: window.decorView.createLifecycleAwareWindowRecomposer(
                lifecycle = contentLifecycle.lifecycle,
            ),
        )
        embeddedRoot.addView(contentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        surfaceView.apply {
            // Interactive embedding requires the package above the host Surface. A passive
            // host stays below it and never requests focus. This alone is not a touch-through
            // contract: callers must use a native Popup for overlays that pass pointer input
            // through, because public SCVH layout parameters do not expose NOT_TOUCHABLE.
            setZOrderOnTop(interactive)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(surfaceCallback)
            isFocusable = interactive
            isFocusableInTouchMode = interactive
            defaultFocusHighlightEnabled = false
        }
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentLifecycle.lifecycle.addObserver(lifecycleObserver)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (closed || failed) return
        if (!displayListenerRegistered) {
            displayManager?.registerDisplayListener(displayListener, mainHandler)
            displayListenerRegistered = displayManager != null
        }
        scheduleRefresh()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(refresh)
        unregisterDisplayListener()
        surfaceAvailable = false
        releaseEmbeddedHost()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        coordinates.invalidate()
        scheduleRefresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (closed || failed) return
        // The windowless manager snapshots Configuration at construction. Public forwarding
        // exists only from API 33, so recreate its ViewRoot on every supported API instead of
        // retaining stale locale/density on API 31-32. The stable parent recomposer survives.
        coordinates.invalidate()
        // This hierarchy is not a child of the native host, so super cannot notify it. Resources
        // are already updated when the native ViewRoot dispatches this callback. A new ViewRoot
        // starts with that new Configuration and does not notify the retained AndroidComposeView,
        // whose cached LocalConfiguration/LocalDensity must also be updated before its next layout.
        embeddedRoot.dispatchConfigurationChanged(newConfig)
        releaseEmbeddedHost()
        scheduleRefresh()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        embeddedRoot.visibility = if (surfaceAvailable && visibility == VISIBLE) VISIBLE else INVISIBLE
        if (visibility == VISIBLE) scheduleRefresh()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) requestEmbeddedFocus() else cancelEmbeddedFocusRequest()
    }

    private fun scheduleRefresh() {
        if (closed || failed) return
        mainHandler.removeCallbacks(refresh)
        mainHandler.post(refresh)
    }

    private fun updateViewportAndHost() {
        if (closed || failed || !isAttachedToWindow || width <= 0 || height <= 0) return
        try {
            val display = display?.takeIf { it.isValid } ?: return
            val next = viewportFor(display) ?: return
            coordinates.update(next)
            if (next != viewport) {
                viewport = next
                // Compose must receive its density/configuration before the new pixel layout.
                // Video observers are separately notified by coordinates after matching pre-draw.
                callbacks.onViewportChanged(next)
                if (closed || failed) return
            }
            val size = next.renderWidth to next.renderHeight
            if (fixedSize != size) {
                fixedSize = size
                surfaceView.holder.setFixedSize(size.first, size.second)
            }
            if (hostedDisplayId != null && hostedDisplayId != display.displayId) releaseEmbeddedHost()
            if (!surfaceAvailable) return

            embeddedRoot.visibility = if (windowVisibility == VISIBLE) VISIBLE else INVISIBLE
            val existingHost = host
            if (existingHost == null) {
                createEmbeddedHost(display, next)
            } else if (hostedRenderSize != size) {
                // relayout changes ViewRoot's window attributes, not the View.layoutParams
                // assigned by setView. Track the requested size so A -> B -> A restores A.
                existingHost.relayout(next.renderWidth, next.renderHeight)
                hostedRenderSize = size
            }
            requestEmbeddedFocus()
        } catch (error: Exception) {
            fail(error)
        }
    }

    @Suppress("DEPRECATION") // Display coordinates are required here, not current-window UI bounds.
    private fun viewportFor(display: Display): UiRenderViewport? {
        val logicalDisplay = Point()
        display.getRealSize(logicalDisplay)
        val mode = display.mode
        return resolveUiRenderViewport(
            modeKey, width, height, logicalDisplay.x, logicalDisplay.y,
            mode.physicalWidth, mode.physicalHeight,
        )
    }

    @Suppress("DEPRECATION") // This API-30 token also works on API 35+; no hidden window APIs.
    private fun createEmbeddedHost(display: Display, viewport: UiRenderViewport) {
        val created = SurfaceControlViewHost(activity, display, surfaceView.hostToken)
        // Store before setView: even a partially attached ViewRoot must be released on failure.
        host = created
        hostedDisplayId = display.displayId
        created.setView(embeddedRoot, viewport.renderWidth, viewport.renderHeight)
        hostedRenderSize = viewport.renderWidth to viewport.renderHeight
        val surfacePackage = checkNotNull(created.surfacePackage) { "No embedded UI surface package" }
        try {
            surfaceView.setChildSurfacePackage(surfacePackage)
        } catch (error: Exception) {
            surfacePackage.release()
            throw error
        }
        // SurfaceView owns the transferred package and links both accessibility and input trees.
        // Input focus follows after an embedded frame has actually reached the compositor.
        // setView schedules attachment in the next traversal. View.post also queues correctly
        // while detached, so startup does not depend on another outer-window focus/size event.
        embeddedRoot.post { if (host === created) requestEmbeddedFocus() }
    }

    private fun requestEmbeddedFocus() {
        val expectedHost = host ?: return
        if (!interactive || closed || failed || !surfaceAvailable || !hasWindowFocus() ||
            embeddedRoot.hasWindowFocus() || !embeddedRoot.isAttachedToWindow ||
            embeddedRoot.visibility != VISIBLE || focusFrameCallback != null
        ) return

        // API 31 drops a transfer made before the native window owns focus or before the
        // embedded input surface is drawn. A committed frame supplies the same readiness
        // boundary used by Android's SurfaceControlViewHost input tests.
        lateinit var callback: Runnable
        callback = Runnable {
            mainHandler.post {
                if (focusFrameCallback !== callback) return@post
                focusFrameCallback = null
                if (host !== expectedHost || closed || failed || !surfaceAvailable ||
                    !hasWindowFocus() || embeddedRoot.hasWindowFocus()
                ) return@post

                // Android 12 transfers focus only from SurfaceView.onFocusChanged. The View
                // can stay focused while a dialog or another window owns input, so requesting
                // focus on it again is a no-op. Toggle only while the native window owns focus;
                // a successful transfer makes that window lose focus and schedules no retry.
                // The embedded Compose focus tree, including its selected control, is retained.
                if (surfaceView.isFocused) {
                    surfaceView.isFocusable = false
                    surfaceView.isFocusableInTouchMode = true // Also restores focusability.
                }
                surfaceView.requestFocus()
                if (!embeddedRoot.hasFocus()) embeddedRoot.requestFocus()
            }
        }
        focusFrameCallback = callback
        embeddedRoot.viewTreeObserver.registerFrameCommitCallback(callback)
        embeddedRoot.invalidate()
    }

    private fun cancelEmbeddedFocusRequest() {
        focusFrameCallback?.let { embeddedRoot.viewTreeObserver.unregisterFrameCommitCallback(it) }
        focusFrameCallback = null
    }

    /** Direct endpoints for Activity fallback dispatch or synthetic input; bypass the callbacks. */
    fun dispatchKeyEventToContent(event: KeyEvent): Boolean =
        !closed && !failed && host != null && embeddedRoot.dispatchKeyEventToContent(event)

    /** [event] already uses the embedded render coordinates, as do the input callbacks. */
    fun dispatchTouchEventToContent(event: MotionEvent): Boolean =
        !closed && !failed && host != null && embeddedRoot.dispatchTouchEventToContent(event)

    fun dispatchGenericMotionEventToContent(event: MotionEvent): Boolean =
        !closed && !failed && host != null && embeddedRoot.dispatchGenericMotionEventToContent(event)

    private fun fail(error: Throwable) {
        if (closed || failed) return
        failed = true
        mainHandler.removeCallbacks(refresh)
        unregisterDisplayListener()
        releaseEmbeddedHost()
        // The caller can now takeContentView() and reparent the still-live composition to its
        // native window. Do not dispose it here or silently keep an unrendered custom mode.
        callbacks.onFailure(error)
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        displayManager?.unregisterDisplayListener(displayListener)
    }

    private fun releaseEmbeddedHost() {
        cancelEmbeddedFocusRequest()
        val previous = host
        host = null
        hostedDisplayId = null
        hostedRenderSize = null
        if (previous != null) {
            runCatching { previous.release() }
                .onFailure { Log.w("UiRenderHost", "Releasing embedded UI", it) }
        }
    }

    /** Preserves composition/remember state while returning to the ordinary native-window path. */
    fun takeContentView(): ComposeView {
        disposeHost(disposeComposition = false)
        return contentView
    }

    override fun close() = disposeHost(disposeComposition = true)

    private fun disposeHost(disposeComposition: Boolean) {
        if (closed) return
        closed = true
        mainHandler.removeCallbacks(refresh)
        unregisterDisplayListener()
        surfaceView.holder.removeCallback(surfaceCallback)
        releaseEmbeddedHost()
        coordinates.close()
        contentLifecycle.lifecycle.removeObserver(lifecycleObserver)
        embeddedRoot.removeView(contentView)
        if (disposeComposition) contentView.disposeComposition()
    }
}

/** Embedded events do not pass through Activity.dispatch* automatically. */
internal class UiRenderInputRoot(
    context: Context,
    override val window: Window,
    private val callbacks: UiRenderHostCallbacks,
    private val interactive: Boolean = true,
) : FrameLayout(context), DialogWindowProvider {
    init {
        isFocusable = interactive
        isFocusableInTouchMode = interactive
        descendantFocusability = if (interactive) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        defaultFocusHighlightEnabled = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        interactive && callbacks.dispatchKeyEvent(event) { dispatchKeyEventToContent(event) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        interactive && callbacks.dispatchTouchEvent(event) { dispatchTouchEventToContent(event) }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        interactive && callbacks.dispatchGenericMotionEvent(event) { dispatchGenericMotionEventToContent(event) }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        callbacks.onWindowFocusChanged(hasWindowFocus)
    }

    fun dispatchKeyEventToContent(event: KeyEvent): Boolean = interactive && super.dispatchKeyEvent(event)
    fun dispatchTouchEventToContent(event: MotionEvent): Boolean = interactive && super.dispatchTouchEvent(event)
    fun dispatchGenericMotionEventToContent(event: MotionEvent): Boolean = interactive && super.dispatchGenericMotionEvent(event)
}

/** A hosted ComposeView adds one extra parent compared with Compose's native DialogLayout. */
internal fun View.findUiRenderWindow(): Window? {
    var view: View? = this
    while (view != null) {
        (view as? DialogWindowProvider)?.let { return it.window }
        view = view.parent as? View
    }
    return null
}

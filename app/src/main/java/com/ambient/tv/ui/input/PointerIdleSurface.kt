package com.ambient.tv.ui.input

import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireView
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext

/**
 * Shared by content in every native Compose window, including dialogs and popups. The icon
 * modifier stays attached while its value changes, retaining the stationary pointer's hover.
 * Native AndroidView children additionally need [PointerIconFrameLayout] around their view.
 */
@Composable
internal fun Modifier.pointerIdleVisibility(): Modifier {
    val context = LocalContext.current
    val hiddenIcon = remember(context) {
        PointerIcon(android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL))
    }
    val hidden = PointerVisibility.hideNativePointer
    val observeMotion = remember { { event: MotionEvent -> PointerVisibility.onMotionEvent(event) } }
    return this
        .then(PointerWindowFocusElement)
        .motionEventSpy(observeMotion)
        .onPreviewKeyEvent { event ->
            PointerVisibility.onKeyEvent(event.nativeKeyEvent)
            false
        }
        .pointerHoverIcon(if (hidden) hiddenIcon else PointerIcon.Default, overrideDescendants = hidden)
}

/** Keeps the caller's content constraints, including compact dialogs and informational popups. */
@Composable
internal fun PointerIdleSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.pointerIdleVisibility(), propagateMinConstraints = true) { content() }
}

/**
 * Resolve the actual host after this modifier is attached. A modifier passed to AlertDialog or
 * DropdownMenu is composed by its caller, but installed in the separate destination window.
 */
private data object PointerWindowFocusElement : ModifierNodeElement<PointerWindowFocusNode>() {
    override fun create() = PointerWindowFocusNode()
    override fun update(node: PointerWindowFocusNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "pointerIdleWindowFocus" }
}

private class PointerWindowFocusNode : Modifier.Node(), View.OnAttachStateChangeListener,
    ViewTreeObserver.OnWindowFocusChangeListener {
    private var hostView: View? = null
    private var observer: ViewTreeObserver? = null

    override fun onAttach() {
        val view = requireView()
        hostView = view
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) observeWindow(view)
    }

    override fun onDetach() {
        stopObservingWindow()
        hostView?.removeOnAttachStateChangeListener(this)
        hostView = null
    }

    override fun onViewAttachedToWindow(view: View) = observeWindow(view)

    override fun onViewDetachedFromWindow(view: View) {
        stopObservingWindow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) PointerVisibility.onWindowFocusLost()
    }

    private fun observeWindow(view: View) {
        stopObservingWindow()
        observer = view.viewTreeObserver.also { it.addOnWindowFocusChangeListener(this) }
    }

    private fun stopObservingWindow() {
        observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(this)
        observer = null
    }
}

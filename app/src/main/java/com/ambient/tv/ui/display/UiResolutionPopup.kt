package com.ambient.tv.ui.display

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup as ComposePopup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import kotlin.math.roundToInt

/** Interactive popups use the selected UI buffer; informational overlays keep a small native window. */
@Composable
internal fun UiResolutionPopup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val environment = LocalUiRenderEnvironment.current
    val embeddedAtCreation = remember { environment.embedded }
    if (!embeddedAtCreation) {
        ComposePopup(alignment, offset, onDismissRequest, properties, content)
        return
    }
    UiResolutionPopup(
        popupPositionProvider = remember(alignment, offset) { UiPopupAlignmentPositionProvider(alignment, offset) },
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content,
    )
}

@Composable
internal fun UiResolutionPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val sourceEnvironment = LocalUiRenderEnvironment.current
    // Keep the existing popup/dialog call site through a parent's native fallback. Its anchor
    // still follows the live coordinate space, and the dialog reparents its existing content.
    val embeddedAtCreation = remember { sourceEnvironment.embedded }
    if (!embeddedAtCreation) {
        ComposePopup(popupPositionProvider, onDismissRequest, properties, content)
        return
    }

    if (!properties.focusable) {
        // SCVH has no public API to make its separate input window NOT_TOUCHABLE. A native
        // informational popup keeps the original, content-sized input area instead of a full
        // screen dialog. Its anchor Layout remains in the caller's embedded render space.
        val sourceView = LocalView.current
        val nativeParentView = remember(sourceView) { sourceView.findUiRenderWindow()?.decorView ?: sourceView }
        val parentDensity = LocalDensity.current
        val sourceToNativeScale = 1f / sourceEnvironment.densityScale
        val nativeDensity = remember(parentDensity, sourceToNativeScale) {
            Density(parentDensity.density * sourceToNativeScale, parentDensity.fontScale)
        }
        val nativePositionProvider = remember(popupPositionProvider, sourceToNativeScale, properties.clippingEnabled) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                    uiPopupPosition(popupPositionProvider, anchorBounds, windowSize, layoutDirection,
                        popupContentSize, properties.clippingEnabled, sourceToNativeScale)
            }
        }
        CompositionLocalProvider(LocalView provides nativeParentView, LocalDensity provides nativeDensity) {
            ComposePopup(nativePositionProvider, onDismissRequest, properties) {
                // Compose Popup installs fresh Android locals in its own ViewRoot.
                ProvideUiRenderEnvironment(UiRenderResolutionPolicy.SYSTEM, null, nativeDensity, content)
            }
        }
        return
    }

    val direction = LocalLayoutDirection.current
    val dismiss by rememberUpdatedState(onDismissRequest)
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    // This occupies no space in the caller. Its parent is the same anchor used by Compose Popup.
    Layout(
        content = {},
        modifier = Modifier.onGloballyPositioned { coordinates ->
            coordinates.parentLayoutCoordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let { bounds ->
                anchorBounds = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                    bounds.right.roundToInt(), bounds.bottom.roundToInt())
            }
        },
    ) { _, _ -> layout(0, 0) {} }
    val anchor = anchorBounds ?: return
    val placedBounds = remember { mutableStateOf<IntRect?>(null) }

    UiResolutionDialog(
        onDismissRequest = { dismiss?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = properties.focusable && properties.dismissOnBackPress,
            // The full-screen transparent layer below owns outside clicks in render coordinates.
            dismissOnClickOutside = false,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
        focusable = properties.focusable,
        dimAmount = 0f,
    ) {
        // A dialog can fall back to a native surface even while its parent remains embedded.
        val sourceToDestinationScale = LocalUiRenderEnvironment.current.densityScale / sourceEnvironment.densityScale
        Layout(
            modifier = Modifier.fillMaxSize().then(if (properties.clippingEnabled) Modifier.clipToBounds() else Modifier),
            content = {
                Box(Modifier.fillMaxSize().then(
                    if (properties.dismissOnClickOutside && onDismissRequest != null) Modifier.pointerInput(Unit) {
                        detectTapGestures { point ->
                            val bounds = placedBounds.value
                            if (bounds != null && (point.x < bounds.left || point.x >= bounds.right ||
                                    point.y < bounds.top || point.y >= bounds.bottom)) dismiss?.invoke()
                        }
                    } else Modifier,
                ))
                Box(Modifier.semantics { popup() }) { content() }
            },
        ) { measurables, constraints ->
            val windowSize = IntSize(constraints.maxWidth, constraints.maxHeight)
            val popup = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val scrim = measurables[0].measure(Constraints.fixed(windowSize.width, windowSize.height))
            val position = uiPopupPosition(popupPositionProvider, anchor, windowSize, direction,
                IntSize(popup.width, popup.height), properties.clippingEnabled, sourceToDestinationScale)
            placedBounds.value = IntRect(position.x, position.y, position.x + popup.width, position.y + popup.height)
            layout(windowSize.width, windowSize.height) {
                scrim.place(0, 0)
                popup.place(position.x, position.y)
            }
        }
    }
}

/** Match Compose's two alignment points, including its rounding and RTL offset direction. */
internal class UiPopupAlignmentPositionProvider(
    private val alignment: Alignment,
    private val offset: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val anchorPoint = alignment.align(IntSize.Zero, anchorBounds.size, layoutDirection)
        val contentPoint = alignment.align(IntSize.Zero, popupContentSize, layoutDirection)
        val horizontalOffset = if (layoutDirection == LayoutDirection.Ltr) offset.x else -offset.x
        return IntOffset(anchorBounds.left + anchorPoint.x - contentPoint.x + horizontalOffset,
            anchorBounds.top + anchorPoint.y - contentPoint.y + offset.y)
    }
}

/** Keep the provider in the anchor's pixels, including when a child window falls back to native UI. */
internal fun uiPopupPosition(
    provider: PopupPositionProvider,
    anchor: IntRect,
    window: IntSize,
    direction: LayoutDirection,
    content: IntSize,
    clippingEnabled: Boolean,
    sourceToDestinationScale: Float = 1f,
): IntOffset {
    val scale = sourceToDestinationScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    fun IntSize.inSourcePixels() = IntSize((width / scale).roundToInt(), (height / scale).roundToInt())
    val sourcePosition = provider.calculatePosition(anchor, window.inSourcePixels(), direction, content.inSourcePixels())
    val requested = IntOffset((sourcePosition.x * scale).roundToInt(), (sourcePosition.y * scale).roundToInt())
    if (!clippingEnabled) return requested
    return IntOffset(
        requested.x.coerceIn(0, (window.width - content.width).coerceAtLeast(0)),
        requested.y.coerceIn(0, (window.height - content.height).coerceAtLeast(0)),
    )
}

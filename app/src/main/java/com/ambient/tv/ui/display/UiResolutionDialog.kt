package com.ambient.tv.ui.display

import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ambient.tv.companion.CompanionDialogInputSurface
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import com.ambient.tv.ui.player.findActivity
import timber.log.Timber

/**
 * A dialog still owns a real Android window (back, focus, dimming and exit animation), while
 * its content uses the same independent UI render target as the activity. The ordinary path
 * remains Compose Dialog, including on phones and Android versions without embedded surfaces.
 */
@Composable
internal fun UiResolutionDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    focusable: Boolean = true,
    dimAmount: Float? = null,
    content: @Composable () -> Unit,
) {
    val parentEnvironment = LocalUiRenderEnvironment.current
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    // A parent's native fallback must not replace an already-open dialog's composition.
    // A dialog that started embedded instead returns its existing content to its native window.
    val initialMode = remember { parentEnvironment.modeKey }
    if (initialMode == UiRenderResolutionPolicy.SYSTEM || activity == null) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            if (dimAmount != null) {
                val window = LocalView.current.findUiRenderWindow()
                DisposableEffect(window, dimAmount) {
                    window?.setDimAmount(dimAmount)
                    onDispose { }
                }
            }
            DialogInputContent(focusable, content)
        }
        return
    }
    val parentDensity = LocalDensity.current
    val windowDensity = remember(parentDensity, parentEnvironment.densityScale) {
        Density(parentDensity.density / parentEnvironment.densityScale, parentDensity.fontScale)
    }
    val currentWindowDensity by rememberUpdatedState(windowDensity)
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentProperties by rememberUpdatedState(properties)
    val currentFocusable by rememberUpdatedState(focusable)
    // Compose keys its native Dialog by this constructor density. Keep the window stable even
    // if undoing a non-binary render scale rounds differently; inner content uses live density.
    val dialogWindowDensity = remember { windowDensity }
    CompositionLocalProvider(LocalDensity provides dialogWindowDensity) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            val ownerView = LocalView.current
            val window = ownerView.findUiRenderWindow()
            val parentComposition = rememberCompositionContext()
            // Compose's outer Dialog already owns a unique, Activity-restored saveable namespace.
            // The extra ComposeView must use it rather than an unpersisted native Dialog registry.
            val contentSaveableRegistry = LocalSaveableStateRegistry.current
            val backKeyGate = remember { UiRenderBackKeyGate() }
            var viewport by remember { mutableStateOf<UiRenderViewport?>(null) }
            var failed by remember { mutableStateOf(false) }
            fun useNativeContent(container: FrameLayout) {
                val previous = container.getChildAt(0) as? UiRenderResolutionHost ?: return
                backKeyGate.reset()
                val savedContent = previous.takeContentView()
                failed = true
                viewport = null
                container.removeAllViews()
                container.addView(savedContent, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (currentFocusable) savedContent.requestFocus()
            }
            // Noninteractive overlays must not steal either key focus or pointer input.
            DisposableEffect(window, focusable, dimAmount) {
                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                if (focusable) window?.clearFlags(flags) else window?.addFlags(flags)
                dimAmount?.let { window?.setDimAmount(it) }
                onDispose { }
            }
            if (window == null) {
                // A vendor Dialog without a discoverable Window retains working native content.
                ProvideUiRenderEnvironment(UiRenderResolutionPolicy.SYSTEM, null, windowDensity) {
                    DialogInputContent(currentFocusable) { currentContent() }
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val container = FrameLayout(context)
                        val compose = ComposeView(context).apply {
                            setContent {
                                CompositionLocalProvider(LocalSaveableStateRegistry provides contentSaveableRegistry) {
                                    ProvideUiRenderEnvironment(
                                        if (failed) UiRenderResolutionPolicy.SYSTEM else initialMode,
                                        if (failed) null else viewport,
                                        currentWindowDensity,
                                    ) {
                                        DialogInputContent(currentFocusable) { currentContent() }
                                    }
                                }
                            }
                        }
                        val created = UiRenderResolutionHost(
                            activity = activity,
                            modeKey = initialMode,
                            contentView = compose,
                            window = window,
                            parentCompositionContext = parentComposition,
                            ownerView = ownerView,
                            interactive = focusable,
                            callbacks = UiRenderHostCallbacks(
                                onViewportChanged = { viewport = it },
                                dispatchKeyEvent = { event, dispatch ->
                                    com.ambient.tv.ui.gamepad.dispatchGameDialogKey(window, event) {
                                        val consumedByContent = dispatch()
                                        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                                            event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                                            if (backKeyGate.onKeyEvent(event, consumedByContent)) {
                                                val dispatcher = (com.ambient.tv.ui.gamepad.gameDialogWindowCallback(window) as? OnBackPressedDispatcherOwner)?.onBackPressedDispatcher
                                                    ?: ownerView.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
                                                if (dispatcher != null) dispatcher.onBackPressed()
                                                else if (currentProperties.dismissOnBackPress) currentDismiss()
                                            }
                                            true
                                        } else consumedByContent
                                    }
                                },
                                dispatchGenericMotionEvent = { event, dispatch ->
                                    com.ambient.tv.ui.gamepad.dispatchGameDialogMotion(window, event, dispatch)
                                },
                                onWindowFocusChanged = { focused ->
                                    if (!focused) {
                                        backKeyGate.reset()
                                        com.ambient.tv.companion.CompanionRemoteInput.hide()
                                    }
                                },
                                onFailure = { error ->
                                    backKeyGate.reset()
                                    Timber.w(error, "Dialog UI surface unavailable; retaining native dialog content")
                                    container.post { useNativeContent(container) }
                                },
                            ),
                        )
                        container.addView(created, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        container
                    },
                    onRelease = { container ->
                        backKeyGate.reset()
                        (container.getChildAt(0) as? UiRenderResolutionHost)?.close()
                        (container.getChildAt(0) as? ComposeView)?.disposeComposition()
                        container.removeAllViews()
                    },
                    update = { container ->
                        if (!parentEnvironment.embedded) useNativeContent(container)
                    },
                )
            }
        }
    }
}

/** Every dialog owns one input registration in its actual native or embedded Compose root. */
@Composable
private fun DialogInputContent(focusable: Boolean, content: @Composable () -> Unit) {
    Box(propagateMinConstraints = true) {
        content()
        if (focusable) {
            com.ambient.tv.ui.gamepad.GameDialogInputSurface()
            // The cursor follows the measured content and must not expand compact native dialogs.
            Box(Modifier.matchParentSize()) { CompanionDialogInputSurface() }
        }
    }
}

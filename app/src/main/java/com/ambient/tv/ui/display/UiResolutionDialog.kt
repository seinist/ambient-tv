package com.ambient.tv.ui.display

import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
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
    if (!parentEnvironment.embedded || activity == null) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
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
        // Noninteractive overlays must not steal either key focus or pointer input.
        DisposableEffect(window, focusable, dimAmount) {
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (focusable) window?.clearFlags(flags) else window?.addFlags(flags)
            dimAmount?.let { window?.setDimAmount(it) }
            onDispose { }
        }
        if (window == null) {
            // A vendor Dialog without a discoverable Window retains working native content.
            ProvideUiRenderEnvironment(UiRenderResolutionPolicy.SYSTEM, null, windowDensity) { currentContent() }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val container = FrameLayout(context)
                    val compose = ComposeView(context).apply {
                        setContent {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides contentSaveableRegistry) {
                                ProvideUiRenderEnvironment(
                                    if (failed) UiRenderResolutionPolicy.SYSTEM else parentEnvironment.modeKey,
                                    if (failed) null else viewport,
                                    currentWindowDensity,
                                ) { currentContent() }
                            }
                        }
                    }
                    var host: UiRenderResolutionHost? = null
                    val created = UiRenderResolutionHost(
                        activity = activity,
                        modeKey = parentEnvironment.modeKey,
                        contentView = compose,
                        window = window,
                        parentCompositionContext = parentComposition,
                        ownerView = ownerView,
                        interactive = focusable,
                        callbacks = UiRenderHostCallbacks(
                            onViewportChanged = { viewport = it },
                            dispatchKeyEvent = { event, dispatch ->
                                val consumedByContent = dispatch()
                                if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                                    event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                                    if (backKeyGate.onKeyEvent(event, consumedByContent)) {
                                        val dispatcher = (window.callback as? OnBackPressedDispatcherOwner)?.onBackPressedDispatcher
                                            ?: ownerView.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
                                        if (dispatcher != null) dispatcher.onBackPressed()
                                        else if (currentProperties.dismissOnBackPress) currentDismiss()
                                    }
                                    true
                                } else consumedByContent
                            },
                            onWindowFocusChanged = { focused -> if (!focused) backKeyGate.reset() },
                            onFailure = { error ->
                                backKeyGate.reset()
                                Timber.w(error, "Dialog UI surface unavailable; retaining native dialog content")
                                container.post {
                                    val previous = host ?: return@post
                                    host = null
                                    val savedContent = previous.takeContentView()
                                    failed = true
                                    viewport = null
                                    container.removeAllViews()
                                    container.addView(savedContent, FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                    if (currentFocusable) savedContent.requestFocus()
                                }
                            },
                        ),
                    )
                    host = created
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
                update = { },
            )
        }
    }
}

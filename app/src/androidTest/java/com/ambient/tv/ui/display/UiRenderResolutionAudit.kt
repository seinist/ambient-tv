package com.ambient.tv.ui.display

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.tv.material3.Text
import com.ambient.tv.companion.CompanionInput
import com.ambient.tv.companion.CompanionRemoteInput
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.player.VideoRenderTargetMode
import com.ambient.tv.ui.player.VideoSurfaceLayout
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real SurfaceControl/input/lifecycle proof. Run on an isolated 3840x2160 audit display. */
internal class UiRenderResolutionAudit(
    private val instrumentation: Instrumentation,
    private val log: (String) -> Unit,
) {
    private var onTimeout: ((String) -> Unit)? = null

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching(action))
            done.countDown()
        }
        check(done.await(15, TimeUnit.SECONDS)) { "UI render audit: main thread stalled" }
        return result.get().getOrThrow()
    }

    private fun await(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (main(condition)) return
            Thread.sleep(75)
        }
        runCatching { onTimeout?.invoke(label) }
            .onFailure { log("Audit timeout diagnostics failed: $it") }
        error("UI render audit timed out: $label")
    }

    private fun key(code: Int) {
        // sendKeyDownUpSync assigns each event a fresh downTime on Android 12. A physical
        // key press, and the production Back gate, require the same downTime for both events.
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0))
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
    }

    fun run() {
        check(Build.VERSION.SDK_INT >= 31) { "Embedded window focus requires Android 12 / API 31" }
        check(instrumentation.targetContext.packageName.endsWith(".hdraudit")) { "Use isolated .hdraudit app" }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as UiCatalogActivity
        val viewport = mutableStateOf<UiRenderViewport?>(null)
        val mode = mutableStateOf(UiRenderResolutionPolicy.FULL_HD)
        val dialogVisible = mutableStateOf(false)
        val popupVisible = mutableStateOf(false)
        val focusablePopupVisible = mutableStateOf(false)
        val editorVisible = mutableStateOf(false)
        val editorText = mutableStateOf("")
        val activations = mutableIntStateOf(0)
        val focus = FocusRequester()
        var rootFocused = false
        var pointerBounds = Rect.Zero
        var rememberedIdentity: Any? = null
        var failure: Throwable? = null
        var routedKeyCount = 0
        var routedTouchCount = 0
        var dialogView: View? = null
        var dialogEnvironment: UiRenderEnvironment? = null
        var dialogDraft: MutableIntState? = null
        var dialogDraftValue = 0
        var popupView: View? = null
        var popupEnvironment: UiRenderEnvironment? = null
        var focusablePopupView: View? = null
        var focusablePopupEnvironment: UiRenderEnvironment? = null
        var focusablePopupDraft: MutableIntState? = null
        var focusablePopupDraftValue = 0
        var editorFocused = false
        var observedDensity = 0f
        var observedDensityDpi = 0
        var observedScreenHeightDp = 0
        lateinit var compose: ComposeView
        lateinit var host: UiRenderResolutionHost
        lateinit var outer: FrameLayout
        lateinit var video: VideoSurfaceLayout
        var contentCreated = false
        var hostCreated = false
        var videoCreated = false

        fun healthy(): Boolean {
            failure?.let { throw AssertionError("Embedded host failed", it) }
            return true
        }
        fun ready(width: Int = 1920, height: Int = 1080): Boolean {
            healthy()
            if (!contentCreated || !hostCreated) return false
            val carrier = host.getChildAt(0) as SurfaceView
            return compose.isAttachedToWindow && compose.width == width && compose.height == height &&
                compose.rootView.width == width && compose.rootView.height == height &&
                carrier.holder.surface.isValid && carrier.holder.surfaceFrame.width() == width &&
                carrier.holder.surfaceFrame.height() == height && compose.uiRenderViewport() != null
        }

        onTimeout = { label ->
            val focusState = main {
                val carrier = if (hostCreated) host.getChildAt(0) as? SurfaceView else null
                buildString {
                    appendLine("Timeout: $label")
                    appendLine("host.size=${if (hostCreated) "${host.width}x${host.height}" else null}")
                    appendLine("compose.size=${if (contentCreated) "${compose.width}x${compose.height}" else null}")
                    appendLine("compose.rootView.size=${if (contentCreated) "${compose.rootView.width}x${compose.rootView.height}" else null}")
                    appendLine("compose.rootView.layoutRequested=${if (contentCreated) compose.rootView.isLayoutRequested else null}")
                    appendLine("carrier.size=${carrier?.let { "${it.width}x${it.height}" }}")
                    appendLine("carrier.surfaceFrame=${carrier?.holder?.surfaceFrame}")
                    appendLine("host.viewport=${if (hostCreated) host.viewport else null}")
                    appendLine("providedViewport=${viewport.value}")
                    appendLine("settledViewport=${if (contentCreated) compose.uiRenderViewport() else null}")
                    appendLine("density.local=$observedDensity, configuration.localDpi=$observedDensityDpi")
                    appendLine("configuration.resourceDpi=${activity.resources.configuration.densityDpi}")
                    appendLine("configuration.localHeightDp=$observedScreenHeightDp, resourceHeightDp=${activity.resources.configuration.screenHeightDp}")
                    appendLine("rootFocused=$rootFocused")
                    appendLine("compose.hasFocus=${if (contentCreated) compose.hasFocus() else null}")
                    appendLine("compose.rootView.hasWindowFocus=${if (contentCreated) compose.rootView.hasWindowFocus() else null}")
                    appendLine("carrier.isFocused=${carrier?.isFocused}")
                    appendLine("carrier.hasWindowFocus=${carrier?.hasWindowFocus()}")
                    appendLine("carrier.isAttachedToWindow=${carrier?.isAttachedToWindow}")
                    appendLine("carrier.surfaceValid=${carrier?.holder?.surface?.isValid}")
                    appendLine("nativeDecor.hasWindowFocus=${activity.window.decorView.hasWindowFocus()}")
                    appendLine("activity.currentFocus=${activity.currentFocus}")
                    appendLine("dialogVisible=${dialogVisible.value}, dialogWindowFocus=${dialogView?.rootView?.hasWindowFocus()}")
                    appendLine("dialog.viewIdentity=${dialogView?.let { System.identityHashCode(it) }}, environment=$dialogEnvironment")
                    appendLine("dialog.draftIdentity=${dialogDraft?.let { System.identityHashCode(it) }}, draft=${dialogDraft?.intValue}, composedDraft=$dialogDraftValue")
                    appendLine("popupVisible=${popupVisible.value}, popupWindowFocus=${popupView?.rootView?.hasWindowFocus()}")
                    appendLine("focusablePopupVisible=${focusablePopupVisible.value}, focusablePopupWindowFocus=${focusablePopupView?.rootView?.hasWindowFocus()}")
                    appendLine("focusablePopup.viewIdentity=${focusablePopupView?.let { System.identityHashCode(it) }}, environment=$focusablePopupEnvironment")
                    appendLine("focusablePopup.draftIdentity=${focusablePopupDraft?.let { System.identityHashCode(it) }}, draft=${focusablePopupDraft?.intValue}, composedDraft=$focusablePopupDraftValue")
                    appendLine("routedKeys=$routedKeyCount, routedTouches=$routedTouchCount, activations=${activations.intValue}")
                }
            }
            log(focusState)
            File(instrumentation.targetContext.filesDir, "ui-render-resolution-focus.txt").writeText(focusState)
            captureSurfaces("ui-render-resolution-timeout")
            for (service in listOf("window", "input")) {
                val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys -t 5 $service").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                }
                File(instrumentation.targetContext.filesDir, "ui-render-resolution-$service.txt").writeText(dump)
            }
        }

        try {
            main {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
                outer = FrameLayout(activity)
                compose = ComposeView(activity).apply {
                    setContent {
                        val identity = remember { Any() }
                        SideEffect { rememberedIdentity = identity }
                        ProvideUiRenderEnvironment(mode.value, viewport.value) {
                            val density = LocalDensity.current
                            val configuration = LocalConfiguration.current
                            SideEffect {
                                observedDensity = density.density
                                observedDensityDpi = configuration.densityDpi
                                observedScreenHeightDp = configuration.screenHeightDp
                            }
                            AmbientTvTheme {
                                Box(Modifier.fillMaxSize()) {
                                    AndroidView(
                                        modifier = Modifier.fillMaxSize(),
                                        factory = { context ->
                                            VideoSurfaceLayout(context).apply {
                                                renderResolutionTarget = VideoRenderTargetMode.DISPLAY
                                                video = this
                                                videoCreated = true
                                            }
                                        },
                                    )
                                    Column(Modifier.padding(32.dp)) {
                                        TvActionButton(
                                            "Open resolution dialog (${activations.intValue})",
                                            { activations.intValue++; dialogVisible.value = true },
                                            Modifier.focusRequester(focus)
                                                .onFocusChanged { rootFocused = it.hasFocus },
                                        )
                                        Text("1080p Compose · independent native video surface")
                                        // TV Material Surface handles DPAD and semantics, not pointer taps.
                                        // A separate real Compose click target tests the platform transform.
                                        Box(
                                            Modifier.size(240.dp, 64.dp)
                                                .background(androidx.compose.ui.graphics.Color.DarkGray)
                                                .clickable { activations.intValue++; dialogVisible.value = true }
                                                .onGloballyPositioned { pointerBounds = it.boundsInRoot() },
                                            contentAlignment = Alignment.Center,
                                        ) { Text("Pointer probe") }
                                        if (editorVisible.value && !dialogVisible.value) {
                                            CompanionAuditEditor(editorText) { editorFocused = it }
                                        }
                                    }
                                    LaunchedEffect(dialogVisible.value) {
                                        if (!dialogVisible.value) {
                                            withFrameNanos { }
                                            focus.requestFocus()
                                        }
                                    }
                                    if (dialogVisible.value) {
                                        UiResolutionDialog(
                                            onDismissRequest = { dialogVisible.value = false },
                                            properties = DialogProperties(
                                                usePlatformDefaultWidth = false,
                                                decorFitsSystemWindows = false,
                                            ),
                                        ) {
                                            val view = LocalView.current
                                            val environment = LocalUiRenderEnvironment.current
                                            val draft = remember { mutableIntStateOf(0) }
                                            val draftValue = draft.intValue
                                            val dialogFocus = remember { FocusRequester() }
                                            SideEffect {
                                                dialogView = view
                                                dialogEnvironment = environment
                                                dialogDraft = draft
                                                dialogDraftValue = draftValue
                                            }
                                            DisposableEffect(Unit) {
                                                onDispose { dialogView = null; dialogEnvironment = null; dialogDraft = null }
                                            }
                                            if (editorVisible.value) {
                                                CompanionAuditEditor(editorText) { editorFocused = it }
                                            } else {
                                                TvActionButton(
                                                    "Close resolution dialog ($draftValue)",
                                                    { dialogVisible.value = false },
                                                    Modifier.focusRequester(dialogFocus),
                                                )
                                                LaunchedEffect(Unit) { withFrameNanos { }; dialogFocus.requestFocus() }
                                            }
                                        }
                                    }
                                    if (popupVisible.value) {
                                        UiResolutionPopup(properties = PopupProperties(focusable = false)) {
                                            val view = LocalView.current
                                            val environment = LocalUiRenderEnvironment.current
                                            SideEffect {
                                                popupView = view
                                                popupEnvironment = environment
                                            }
                                            DisposableEffect(Unit) {
                                                onDispose { popupView = null; popupEnvironment = null }
                                            }
                                            Text("Noninteractive resolution overlay")
                                        }
                                    }
                                    if (focusablePopupVisible.value) {
                                        UiResolutionPopup(
                                            onDismissRequest = { focusablePopupVisible.value = false },
                                            properties = PopupProperties(focusable = true),
                                        ) {
                                            val view = LocalView.current
                                            val environment = LocalUiRenderEnvironment.current
                                            val draft = remember { mutableIntStateOf(0) }
                                            val draftValue = draft.intValue
                                            val popupFocus = remember { FocusRequester() }
                                            SideEffect {
                                                focusablePopupView = view
                                                focusablePopupEnvironment = environment
                                                focusablePopupDraft = draft
                                                focusablePopupDraftValue = draftValue
                                            }
                                            DisposableEffect(Unit) {
                                                onDispose {
                                                    focusablePopupView = null
                                                    focusablePopupEnvironment = null
                                                    focusablePopupDraft = null
                                                }
                                            }
                                            TvActionButton("Close interactive popup ($draftValue)",
                                                { focusablePopupVisible.value = false },
                                                Modifier.focusRequester(popupFocus))
                                            LaunchedEffect(Unit) { withFrameNanos { }; popupFocus.requestFocus() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                contentCreated = true
                host = UiRenderResolutionHost(
                    activity, UiRenderResolutionPolicy.FULL_HD, compose,
                    UiRenderHostCallbacks(
                        onViewportChanged = { viewport.value = it },
                        dispatchKeyEvent = { _, dispatch -> routedKeyCount++; dispatch() },
                        dispatchTouchEvent = { _, dispatch -> routedTouchCount++; dispatch() },
                        onFailure = { failure = it },
                    ),
                )
                hostCreated = true
                CompanionRemoteInput.register(activity.window, inputRoot = { host.inputRoot })
                outer.addView(host, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(outer)
            }
            await("3840x2160 outer window") { healthy() && host.width == 3840 && host.height == 2160 }
            await("1920x1080 SCVH root and real carrier surface") { ready() }
            await("nested video surface") { healthy() && videoCreated && video.videoSurface.holder.surface.isValid && video.forcedRenderSize != null }
            val initialIdentity = main { checkNotNull(rememberedIdentity) }
            val panelSize = main { checkNotNull(video.forcedRenderSize) }
            await("Panel video buffer") {
                val frame = video.videoSurface.holder.surfaceFrame
                frame.width() == panelSize.first && frame.height() == panelSize.second
            }
            log("PASS real UI root/carrier 1920x1080 in 3840x2160 window; Panel video buffer ${panelSize.first}x${panelSize.second}")

            // Some emulators expose a smaller Display.Mode even after `wm size 3840x2160`.
            // Test the independent 4K allocation explicitly, without misreporting the Panel policy.
            if (panelSize != (3840 to 2160)) {
                main { video.videoSurface.holder.setFixedSize(3840, 2160) }
                await("independent 4K nested surface") {
                    video.videoSurface.holder.surfaceFrame.let { it.width() == 3840 && it.height() == 2160 }
                }
            }
            val canvasSize = drawNativeVideo(video.videoSurface)
            check(canvasSize == (3840 to 2160)) { "Native video allocation was $canvasSize" }
            main { check(ready()) }
            log("PASS nested SurfaceView allocates and draws a real 3840x2160 Canvas while the Compose root remains 1920x1080")
            captureSurfaces()
            if (panelSize != (3840 to 2160)) {
                main { video.videoSurface.holder.setFixedSize(panelSize.first, panelSize.second) }
            }

            await("root DPAD focus") { rootFocused && compose.rootView.hasWindowFocus() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("DPAD opens embedded native dialog") {
                healthy() && dialogVisible.value && dialogView?.rootView?.hasWindowFocus() == true &&
                    dialogEnvironment?.embedded == true && dialogView?.uiRenderViewport() != null
            }
            main {
                val geometry = checkNotNull(dialogView?.uiRenderViewport())
                check(dialogView!!.rootView.width == geometry.renderWidth)
                check(dialogView!!.rootView.height == geometry.renderHeight)
                check(routedKeyCount > 0 && activations.intValue == 1)
            }
            key(KeyEvent.KEYCODE_BACK)
            await("Back dismisses dialog and returns focus") {
                !dialogVisible.value && dialogView == null && rootFocused && compose.rootView.hasWindowFocus()
            }
            log("PASS DPAD reaches embedded Compose; dialog uses a measured UI surface; Back dismisses it and restores root focus")

            main { popupVisible.value = true }
            await("nonfocusable popup") {
                val popupRoot = popupView?.rootView ?: return@await false
                val flags = (popupRoot.layoutParams as? WindowManager.LayoutParams)?.flags ?: return@await false
                // Informational overlays intentionally retain a small native Popup window.
                // Public SCVH parameters cannot make a fullscreen embedded input window touch-through.
                popupEnvironment?.modeKey == UiRenderResolutionPolicy.SYSTEM && !popupRoot.isUiRenderHosted() &&
                    popupRoot.width in 1 until 3840 && popupRoot.height in 1 until 2160 &&
                    flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 &&
                    compose.rootView.hasWindowFocus() && rootFocused
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("DPAD passes nonfocusable overlay") { activations.intValue == 2 && dialogVisible.value && dialogView?.rootView?.hasWindowFocus() == true }
            key(KeyEvent.KEYCODE_BACK)
            await("overlay dialog dismissed") { !dialogVisible.value && rootFocused && compose.rootView.hasWindowFocus() }
            log("PASS nonfocusable popup retains key focus and does not block underlying controls")

            await("pointer probe laid out") { !pointerBounds.isEmpty }
            val touchPoint = main {
                val coordinates = checkNotNull(viewport.value)
                val location = IntArray(2).also(host::getLocationOnScreen)
                Pair(location[0] + pointerBounds.center.x * coordinates.windowWidth / coordinates.renderWidth,
                    location[1] + pointerBounds.center.y * coordinates.windowHeight / coordinates.renderHeight)
            }
            tap(touchPoint.first, touchPoint.second)
            await("scaled pointer hits button") {
                routedTouchCount > 0 && activations.intValue == 3 && dialogVisible.value &&
                    dialogView?.rootView?.hasWindowFocus() == true
            }
            key(KeyEvent.KEYCODE_BACK)
            await("pointer dialog dismissed") { !dialogVisible.value && dialogView == null }
            main { popupVisible.value = false }
            await("overlay removed") { popupView == null }
            log("PASS pointer crosses the noninteractive overlay and platform input transform reaches the 1080p Compose control")

            main { host.layoutParams = FrameLayout.LayoutParams(1280, 720) }
            await("smaller outer viewport reflows its real UI buffer") {
                val geometry = viewport.value ?: return@await false
                geometry.windowWidth == 1280 && geometry.renderWidth < 1920 &&
                    ready(geometry.renderWidth, geometry.renderHeight)
            }
            main { host.layoutParams = FrameLayout.LayoutParams(-1, -1) }
            await("full outer viewport restores 1080p UI") { ready() && viewport.value?.windowWidth == 3840 }
            main { check(rememberedIdentity === initialIdentity); outer.removeView(host) }
            await("host detach releases embedded ViewRoot") { !compose.isAttachedToWindow }
            main { outer.addView(host, FrameLayout.LayoutParams(-1, -1)) }
            await("host reattach recreates its surface") { ready() && compose.rootView.hasWindowFocus() }
            main { check(rememberedIdentity === initialIdentity && activations.intValue == 3) }
            log("PASS reflow and complete host detach/reattach retain Compose state while recreating real surfaces")

            main { check(activity.moveTaskToBack(true)) }
            await("activity backgrounded") { host.windowVisibility != View.VISIBLE }
            instrumentation.targetContext.startActivity(
                Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            await("activity foregrounded with live surface") { ready() && host.windowVisibility == View.VISIBLE && compose.rootView.hasWindowFocus() }
            main { check(rememberedIdentity === initialIdentity && activations.intValue == 3) }
            log("PASS background/foreground resumes the original composition and input focus")

            main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok"))) }
            await("Companion opens embedded dialog") {
                activations.intValue == 4 && dialogView?.rootView?.hasWindowFocus() == true
            }
            main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok"))) }
            await("Companion closes focused dialog control") {
                !dialogVisible.value && rootFocused && compose.rootView.hasWindowFocus()
            }
            main {
                check(CompanionRemoteInput.dispatch(CompanionInput("move", x = -1f, y = -1f)))
                check(CompanionRemoteInput.dispatch(CompanionInput("move",
                    x = pointerBounds.center.x / compose.rootView.width - 0.01f,
                    y = pointerBounds.center.y / compose.rootView.height - 0.01f)))
                check(CompanionRemoteInput.dispatch(CompanionInput("click")))
            }
            await("Companion pointer uses embedded viewport") {
                activations.intValue == 5 && dialogView?.rootView?.hasWindowFocus() == true
            }
            main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "back"))) }
            await("Companion Back returns to embedded activity") {
                !dialogVisible.value && rootFocused && compose.rootView.hasWindowFocus()
            }
            for (inDialog in listOf(false, true)) {
                main {
                    editorText.value = ""
                    editorFocused = false
                    editorVisible.value = true
                    dialogVisible.value = inDialog
                }
                await("Companion editor focus (dialog=$inDialog)") {
                    val inputRoot = if (inDialog) dialogView?.rootView else compose.rootView
                    editorFocused && inputRoot?.hasWindowFocus() == true &&
                        inputRoot.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()) != null
                }
                main { check(CompanionRemoteInput.dispatch(CompanionInput("text", text = "Auflösung 4K"))) }
                await("Companion inserts into focused Compose editor") { editorText.value == "Auflösung 4K" }
                main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "delete"))) }
                await("Companion deletes in focused Compose editor") { editorText.value == "Auflösung 4" }
                main { editorVisible.value = false; dialogVisible.value = false }
                await("Companion editor removed") { !editorFocused && dialogView == null }
                main {
                    activity.window.insetsController?.hide(WindowInsets.Type.ime())
                    focus.requestFocus()
                }
                await("Companion editor returns control focus without IME") {
                    rootFocused && compose.rootView.hasWindowFocus() &&
                        activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
                }
            }
            log("PASS Companion dispatch reaches embedded activity/dialog keys, Back, pointer and Compose text InputConnection")

            for (closeKey in listOf("ok", "back")) {
                main { focusablePopupVisible.value = true }
                await("focusable popup owns embedded input") {
                    focusablePopupView?.rootView?.hasWindowFocus() == true &&
                        focusablePopupView?.isUiRenderHosted() == true
                }
                main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = closeKey))) }
                await("Companion $closeKey closes focusable popup") {
                    !focusablePopupVisible.value && focusablePopupView == null &&
                        compose.rootView.hasWindowFocus()
                }
            }
            log("PASS focusable popup registers its real Companion endpoint and handles OK/Back")

            val originalDensityDpi = main { activity.resources.configuration.densityDpi }
            val originalOverride = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1)
            val changedDensityDpi = if (originalDensityDpi == 320) 400 else 320
            fun awaitDisplayDensity(dpi: Int) {
                await("real Configuration density $dpi reaches retained Compose", timeoutMs = 45_000) {
                    val geometry = viewport.value ?: return@await false
                    val config = activity.resources.configuration
                    val expectedDensity = dpi / 160f * geometry.renderWidth / geometry.windowWidth
                    ready() && config.densityDpi == dpi && observedDensityDpi == dpi &&
                        kotlin.math.abs(observedDensity - expectedDensity) < 0.001f &&
                        observedScreenHeightDp == config.screenHeightDp &&
                        compose.rootView.hasWindowFocus()
                }
                main { check(rememberedIdentity === initialIdentity && activations.intValue == 5) }
            }
            try {
                shell("wm density $changedDensityDpi")
                awaitDisplayDensity(changedDensityDpi)
            } finally {
                // Restore the actual override state even if the first assertion fails. A second
                // assertion in finally would replace the original failure and its diagnostics.
                shell(if (originalOverride == null) "wm density reset" else "wm density $originalOverride")
            }
            awaitDisplayDensity(originalDensityDpi)
            log("PASS real Android density Configuration round trip updates Compose density/configuration and preserves state")

            // Both native windows must survive the parent's renderer fallback, including state
            // owned inside their separate compositions. Open the popup last so it owns input.
            main { dialogVisible.value = true }
            await("dialog open before parent fallback") {
                healthy() && dialogView?.rootView?.hasWindowFocus() == true &&
                    dialogEnvironment?.embedded == true && dialogDraft != null
            }
            main { focusablePopupVisible.value = true }
            await("popup above open dialog before parent fallback") {
                healthy() && focusablePopupView?.rootView?.hasWindowFocus() == true &&
                    focusablePopupEnvironment?.embedded == true && focusablePopupDraft != null
            }
            val dialogBeforeFallback = main { checkNotNull(dialogView) to checkNotNull(dialogDraft) }
            val popupBeforeFallback = main { checkNotNull(focusablePopupView) to checkNotNull(focusablePopupDraft) }
            main {
                dialogBeforeFallback.second.intValue = 37
                popupBeforeFallback.second.intValue = 83
            }
            await("dialog and popup compose distinct local drafts") {
                dialogDraftValue == 37 && focusablePopupDraftValue == 83
            }
            main {
                val savedContent = host.takeContentView()
                mode.value = UiRenderResolutionPolicy.SYSTEM
                viewport.value = null
                outer.removeAllViews()
                outer.addView(savedContent, FrameLayout.LayoutParams(-1, -1))
                // Match MainActivity's real fallback without taking focus from child windows.
                savedContent.requestFocus()
            }
            await("native fallback reuses live composition") { compose.isAttachedToWindow && compose.width == 3840 && !compose.isUiRenderHosted() }
            await("open dialog and popup retain content through parent fallback") {
                dialogVisible.value && focusablePopupVisible.value &&
                    dialogView === dialogBeforeFallback.first && dialogDraft === dialogBeforeFallback.second &&
                    dialogDraft?.intValue == 37 && dialogDraftValue == 37 &&
                    dialogEnvironment?.modeKey == UiRenderResolutionPolicy.SYSTEM && dialogView?.isUiRenderHosted() == false &&
                    focusablePopupView === popupBeforeFallback.first && focusablePopupDraft === popupBeforeFallback.second &&
                    focusablePopupDraft?.intValue == 83 && focusablePopupDraftValue == 83 &&
                    focusablePopupEnvironment?.modeKey == UiRenderResolutionPolicy.SYSTEM &&
                    focusablePopupView?.isUiRenderHosted() == false &&
                    focusablePopupView?.rootView?.hasWindowFocus() == true
            }
            main {
                check(rememberedIdentity === initialIdentity && activations.intValue == 5)
                check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok")))
            }
            await("Companion closes retained native popup and restores dialog focus") {
                !focusablePopupVisible.value && focusablePopupView == null && dialogVisible.value &&
                    dialogView === dialogBeforeFallback.first && dialogDraft === dialogBeforeFallback.second &&
                    dialogDraft?.intValue == 37 && dialogView?.rootView?.hasWindowFocus() == true
            }
            main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok"))) }
            await("Companion closes retained native dialog") { !dialogVisible.value && dialogView == null }
            await("native fallback input focus restored") { rootFocused && compose.rootView.hasWindowFocus() }
            main { check(rememberedIdentity === initialIdentity && activations.intValue == 5) }
            log("PASS parent native fallback preserves open dialog/popup View and remember state, then Companion closes both and restores main input")
            main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "ok"))) }
            await("native fallback remains interactive") {
                activations.intValue == 6 && dialogVisible.value &&
                    dialogView?.rootView?.hasWindowFocus() == true &&
                    dialogEnvironment?.modeKey == UiRenderResolutionPolicy.SYSTEM && dialogView?.isUiRenderHosted() == false
            }
            main { check(CompanionRemoteInput.dispatch(CompanionInput("key", key = "back"))) }
            await("native fallback dialog closes") { !dialogVisible.value }
            log("PASS native fallback preserves remember state and continues composing and handling DPAD/Back")
        } finally {
            onTimeout = null
            main {
                CompanionRemoteInput.unregister(activity.window)
                if (hostCreated) host.close()
                if (contentCreated) compose.disposeComposition()
                activity.finish()
            }
        }
    }

    @Composable
    @Suppress("DEPRECATION") // Exercise an editor that uses the value-based text API.
    private fun CompanionAuditEditor(text: MutableState<String>, onFocusChanged: (Boolean) -> Unit) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        BasicTextField(
            value = text.value,
            onValueChange = { text.value = it },
            modifier = Modifier.size(320.dp, 64.dp).focusRequester(focus)
                .onFocusChanged { onFocusChanged(it.hasFocus) },
            singleLine = true,
        )
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
        DisposableEffect(Unit) { onDispose { keyboard?.hide(); onFocusChanged(false) } }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }

    private fun drawNativeVideo(surface: SurfaceView): Pair<Int, Int> {
        val holder = surface.holder
        val canvas = checkNotNull(holder.lockCanvas())
        try {
            canvas.drawColor(Color.rgb(18, 70, 110))
            val paint = Paint().apply { color = Color.rgb(210, 130, 30) }
            canvas.drawRect(canvas.width * .5f, canvas.height * .5f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
            return canvas.width to canvas.height
        } finally { holder.unlockCanvasAndPost(canvas) }
    }

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 32L)) {
            val event = MotionEvent.obtain(time, time + offset, action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    private fun captureSurfaces(filePrefix: String = "ui-render-resolution") {
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys SurfaceFlinger").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        File(instrumentation.targetContext.filesDir, "$filePrefix-surfaceflinger.txt").writeText(dump)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "UI render audit could not capture the composed display"
        }
        try {
            File(instrumentation.targetContext.filesDir, "$filePrefix.png").outputStream().use { output ->
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                    "UI render audit could not encode the composed display screenshot"
                }
            }
        } finally {
            screenshot.recycle()
        }
    }
}

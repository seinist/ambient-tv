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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
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
        error("UI render audit timed out: $label")
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    fun run() {
        check(Build.VERSION.SDK_INT >= 30)
        check(instrumentation.targetContext.packageName.endsWith(".hdraudit")) { "Use isolated .hdraudit app" }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as UiCatalogActivity
        val viewport = mutableStateOf<UiRenderViewport?>(null)
        val mode = mutableStateOf(UiRenderResolutionPolicy.FULL_HD)
        val dialogVisible = mutableStateOf(false)
        val popupVisible = mutableStateOf(false)
        val activations = mutableIntStateOf(0)
        val focus = FocusRequester()
        var rootFocused = false
        var buttonBounds = Rect.Zero
        var rememberedIdentity: Any? = null
        var failure: Throwable? = null
        var routedKeyCount = 0
        var routedTouchCount = 0
        var dialogView: View? = null
        var dialogEnvironment: UiRenderEnvironment? = null
        var popupView: View? = null
        var popupEnvironment: UiRenderEnvironment? = null
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

        try {
            main {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.setDecorFitsSystemWindows(false)
                activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
                outer = FrameLayout(activity)
                compose = ComposeView(activity).apply {
                    setContent {
                        val identity = remember { Any() }
                        SideEffect { rememberedIdentity = identity }
                        ProvideUiRenderEnvironment(mode.value, viewport.value) {
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
                                                .onFocusChanged { rootFocused = it.hasFocus }
                                                .onGloballyPositioned { buttonBounds = it.boundsInRoot() },
                                        )
                                        Text("1080p Compose · independent native video surface")
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
                                            val dialogFocus = remember { FocusRequester() }
                                            SideEffect { dialogView = view; dialogEnvironment = environment }
                                            DisposableEffect(Unit) {
                                                onDispose { dialogView = null; dialogEnvironment = null }
                                            }
                                            TvActionButton(
                                                "Close resolution dialog",
                                                { dialogVisible.value = false },
                                                Modifier.focusRequester(dialogFocus),
                                            )
                                            LaunchedEffect(Unit) { withFrameNanos { }; dialogFocus.requestFocus() }
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

            val touchPoint = main {
                val coordinates = checkNotNull(viewport.value)
                val location = IntArray(2).also(host::getLocationOnScreen)
                Pair(location[0] + buttonBounds.center.x * coordinates.windowWidth / coordinates.renderWidth,
                    location[1] + buttonBounds.center.y * coordinates.windowHeight / coordinates.renderHeight)
            }
            tap(touchPoint.first, touchPoint.second)
            await("scaled pointer hits button") { routedTouchCount > 0 && activations.intValue == 3 && dialogVisible.value }
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

            main {
                val savedContent = host.takeContentView()
                mode.value = UiRenderResolutionPolicy.SYSTEM
                viewport.value = null
                outer.removeAllViews()
                outer.addView(savedContent, FrameLayout.LayoutParams(-1, -1))
            }
            await("native fallback reuses live composition") { compose.isAttachedToWindow && compose.width == 3840 && !compose.isUiRenderHosted() }
            main {
                check(rememberedIdentity === initialIdentity && activations.intValue == 3)
                check(focus.requestFocus())
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("native fallback remains interactive") { activations.intValue == 4 && dialogVisible.value }
            key(KeyEvent.KEYCODE_BACK)
            await("native fallback dialog closes") { !dialogVisible.value }
            log("PASS native fallback preserves remember state and continues composing and handling DPAD/Back")
        } finally {
            main {
                if (hostCreated) host.close()
                if (contentCreated) compose.disposeComposition()
                activity.finish()
            }
        }
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

    private fun captureSurfaces() {
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys SurfaceFlinger").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        File(instrumentation.targetContext.filesDir, "ui-render-resolution-surfaceflinger.txt").writeText(dump)
    }
}

package com.ambient.tv.ui.moonlight

import android.os.Bundle
import android.view.InputDevice
import kotlin.math.roundToInt
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.moonlight.MoonlightCompanionLink
import com.ambient.tv.moonlight.MoonlightControllerRouter
import com.ambient.tv.moonlight.MoonlightKeyboardRouter
import com.ambient.tv.moonlight.MoonlightStreamEngine
import com.ambient.tv.moonlight.MoonlightStreamPhase
import com.ambient.tv.moonlight.MoonlightStreamProfile
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.common.TvActionButton
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Native fullscreen streaming surface. Intentionally separate from Android's movie/audio players
 * and Libretro core. Only an authenticated, paired Moonlight catalog may launch this Activity.
 *
 * Back opens the OSD on the first press; a second Back disconnects the stream WITHOUT terminating
 * the remote PC process. Controller button MODE toggles the same OSD. No hidden remote quit.
 */
@AndroidEntryPoint
class MoonlightStreamActivity : ComponentActivity() {
    @Inject lateinit var engine: MoonlightStreamEngine
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var companion: MoonlightCompanionLink

    private var osdVisible by mutableStateOf(false)
    private val keyboard by lazy {
        MoonlightKeyboardRouter { virtualKey, pressed, modifiers ->
            engine.sendKeyboard(virtualKey, pressed, modifiers)
        }
    }
    private val input by lazy {
        MoonlightControllerRouter(
            output = { port, mask, buttons, lt, rt, lx, ly, rx, ry ->
                engine.sendGamepad(port, mask, buttons, lt, rt, lx, ly, rx, ry)
            },
            reservedPorts = { companion.reservedPorts() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hostId = intent.getStringExtra(EXTRA_HOST_ID)?.takeIf { it.length in 1..64 }
        val appId = intent.getIntExtra(EXTRA_APP_ID, -1)
        if (!BuildConfig.MOONLIGHT_NATIVE || hostId == null || appId <= 0) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContent {
            MoonlightStreamContent(
                engine = engine,
                hostId = hostId,
                appId = appId,
                streamProfile = preferences.moonlightStreamProfile,
                controlsShown = osdVisible,
                showControls = { showOsd(true) },
                disconnect = ::disconnectAndFinish,
                closeControls = { showOsd(false) },
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode in setOf(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE)) {
            showOsd(!osdVisible)
            return true
        }
        if (!osdVisible && (input.onKey(event) || keyboard.onKey(event))) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!osdVisible && input.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun showOsd(visible: Boolean) {
        if (visible && !osdVisible) releaseRemoteInputs()
        osdVisible = visible
    }

    private fun releaseRemoteInputs() {
        input.releaseAll()
        keyboard.releaseAll()
        engine.sendMouseButton(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseRemoteInputs()
    }

    override fun onStop() {
        releaseRemoteInputs()
        engine.stop()
        super.onStop()
    }

    private fun disconnectAndFinish() {
        releaseRemoteInputs()
        engine.stop()
        finish()
    }

    override fun onDestroy() {
        releaseRemoteInputs()
        engine.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST_ID = "moonlight_host_id"
        const val EXTRA_APP_ID = "moonlight_app_id"
    }
}

@Composable
private fun MoonlightStreamContent(
    engine: MoonlightStreamEngine,
    hostId: String,
    appId: Int,
    streamProfile: MoonlightStreamProfile,
    controlsShown: Boolean,
    showControls: () -> Unit,
    closeControls: () -> Unit,
    disconnect: () -> Unit,
) {
    val status by engine.status.collectAsState()
    val resumeFocus = remember { FocusRequester() }
    val disconnectFocus = remember { FocusRequester() }
    LaunchedEffect(controlsShown, status.phase) {
        if (controlsShown || status.phase != MoonlightStreamPhase.STREAMING) {
            withFrameNanos { }
            runCatching {
                if (controlsShown && status.phase == MoonlightStreamPhase.STREAMING)
                    resumeFocus.requestFocus()
                else disconnectFocus.requestFocus()
            }
        }
    }
    BackHandler { if (controlsShown) disconnect() else showControls() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.start(hostId, appId, holder.surface, streamProfile)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) { engine.stop() }
                    })
                }
            },
            update = { view ->
                view.setOnTouchListener { v, event ->
                    if (controlsShown) return@setOnTouchListener false
                    val width = v.width.coerceAtLeast(1)
                    val height = v.height.coerceAtLeast(1)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            engine.sendMousePosition(event.x.roundToInt(), event.y.roundToInt(), width, height)
                            engine.sendMouseButton(true)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            engine.sendMousePosition(event.x.roundToInt(), event.y.roundToInt(), width, height)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            engine.sendMouseButton(false)
                            true
                        }
                        else -> false
                    }
                }
                view.setOnGenericMotionListener { v, event ->
                    if (controlsShown ||
                        (event.source and InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE)
                        return@setOnGenericMotionListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_HOVER_MOVE -> {
                            engine.sendMousePosition(event.x.roundToInt(), event.y.roundToInt(),
                                v.width.coerceAtLeast(1), v.height.coerceAtLeast(1))
                            true
                        }
                        MotionEvent.ACTION_SCROLL -> {
                            engine.sendMouseScroll(event.getAxisValue(MotionEvent.AXIS_VSCROLL).roundToInt())
                            true
                        }
                        else -> false
                    }
                }
            },
        )
        if (controlsShown || status.phase != MoonlightStreamPhase.STREAMING) {
            ReferencePlayerOverlayBackground()
            ReferencePlayerClock(Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 58.dp, end = 58.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReferencePlayerHeader(
                    title = status.title.ifBlank { stringResource(R.string.moonlight_title) },
                    artworkUrl = null,
                    contextLine = "Moonlight · GameStream",
                    subtitle = status.error ?: stringResource(when (status.phase) {
                        MoonlightStreamPhase.STREAMING -> R.string.moonlight_stream_running
                        MoonlightStreamPhase.ENDED -> R.string.moonlight_stream_ended
                        MoonlightStreamPhase.FAILED -> R.string.moonlight_stream_failed
                        else -> R.string.moonlight_stream_connecting
                    }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (status.phase == MoonlightStreamPhase.STREAMING && controlsShown)
                        TvActionButton(stringResource(R.string.moonlight_stream_resume), closeControls,
                            modifier = Modifier.focusRequester(resumeFocus))
                    TvActionButton(stringResource(R.string.moonlight_stream_disconnect), disconnect,
                        modifier = Modifier.focusRequester(disconnectFocus))
                }
            }
        }
    }
    DisposableEffect(engine, hostId, appId) {
        onDispose { engine.stop() }
    }
}

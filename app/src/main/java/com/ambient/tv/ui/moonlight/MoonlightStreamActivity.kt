package com.ambient.tv.ui.moonlight

import android.os.Bundle
import android.view.InputDevice
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.ambient.tv.moonlight.MoonlightControllerRouter
import com.ambient.tv.moonlight.MoonlightStreamEngine
import com.ambient.tv.moonlight.MoonlightStreamPhase
import com.ambient.tv.moonlight.MoonlightStreamProfile
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

    private var osdVisible by mutableStateOf(false)
    private val input by lazy {
        MoonlightControllerRouter { port, mask, buttons, lt, rt, lx, ly, rx, ry ->
            engine.sendGamepad(port, mask, buttons, lt, rt, lx, ly, rx, ry)
        }
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
        if (!osdVisible && input.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!osdVisible && input.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun showOsd(visible: Boolean) {
        if (visible && !osdVisible) input.releaseAll()
        osdVisible = visible
    }

    private fun disconnectAndFinish() {
        input.releaseAll()
        engine.stop()
        finish()
    }

    override fun onDestroy() {
        input.releaseAll()
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
    controlsShown: Boolean,
    showControls: () -> Unit,
    closeControls: () -> Unit,
    disconnect: () -> Unit,
) {
    val status by engine.status.collectAsState()
    BackHandler { if (controlsShown) disconnect() else showControls() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.start(hostId, appId, holder.surface, MoonlightStreamProfile())
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.stop()
                        }
                    })
                }
            },
        )
        if (controlsShown || status.phase != MoonlightStreamPhase.STREAMING) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(status.title.ifBlank { stringResource(R.string.moonlight_title) },
                    color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(status.error ?: stringResource(when (status.phase) {
                    MoonlightStreamPhase.STREAMING -> R.string.moonlight_stream_running
                    MoonlightStreamPhase.ENDED -> R.string.moonlight_stream_ended
                    MoonlightStreamPhase.FAILED -> R.string.moonlight_stream_failed
                    else -> R.string.moonlight_stream_connecting
                }), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (status.phase == MoonlightStreamPhase.STREAMING && controlsShown)
                        TvActionButton(stringResource(R.string.moonlight_stream_resume), closeControls)
                    TvActionButton(stringResource(R.string.moonlight_stream_disconnect), disconnect)
                }
            }
        }
    }
    DisposableEffect(engine, hostId, appId) {
        onDispose { engine.stop() }
    }
}

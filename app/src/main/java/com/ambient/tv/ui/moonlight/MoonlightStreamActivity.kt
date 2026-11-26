package com.ambient.tv.ui.moonlight

import android.os.Bundle
import android.content.Context
import android.hardware.input.InputManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import android.view.Surface
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.tv.material3.Text
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.moonlight.MoonlightCompanionLink
import com.ambient.tv.moonlight.MoonlightDeviceFeedback
import com.ambient.tv.moonlight.MoonlightControllerRouter
import com.ambient.tv.moonlight.MoonlightKeyboardRouter
import com.ambient.tv.moonlight.MoonlightStreamEngine
import com.ambient.tv.moonlight.MoonlightSystemTransport
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
    @Inject lateinit var moonlightTransport: MoonlightSystemTransport

    private val streamOwner = Any()
    private var inputProfile: com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext? = null
    private val pointer by lazy { MoonlightPointerRouter(engine, streamOwner) }
    private var osdVisible by mutableStateOf(false)
    private var gameplayAllowed = false
    private var focusRevision by mutableStateOf(0)
    private var quitJob: Job? = null
    private var qualityProfile by mutableStateOf(MoonlightStreamProfile())
    private var sessionProfile by mutableStateOf(MoonlightStreamProfile())
    private val displaySession by lazy { MoonlightDisplaySession(this) }
    private var displayPreparation: Job? = null
    private var displayResult by mutableStateOf(MoonlightDisplayResult())
    private var displayReady by mutableStateOf(false)
    private var validLaunch = false
    private var remoteQuitBusy by mutableStateOf(false)
    private var remoteQuitError by mutableStateOf(false)
    private val inputManager by lazy {
        getSystemService(Context.INPUT_SERVICE) as InputManager
    }
    private val feedback by lazy { MoonlightDeviceFeedback(input) }
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { input.registerAttachedDevices(); input.republish() }
        override fun onInputDeviceChanged(deviceId: Int) { input.removeDevice(deviceId); input.registerAttachedDevices(); input.republish() }
        override fun onInputDeviceRemoved(deviceId: Int) { keyboard.removeDevice(deviceId); input.removeDevice(deviceId) }
    }
    private val keyboard by lazy {
        MoonlightKeyboardRouter { virtualKey, pressed, modifiers ->
            engine.sendKeyboard(streamOwner, virtualKey, pressed, modifiers)
        }
    }
    private val input: MoonlightControllerRouter by lazy {
        MoonlightControllerRouter(
            output = { port, mask, buttons, lt, rt, lx, ly, rx, ry ->
                engine.sendGamepad(streamOwner, port, mask, buttons, lt, rt, lx, ly, rx, ry, input.capabilities(port.toInt()))
            },
            reservedPorts = { companion.reservedPorts() },
            onMenu = { showOsd(true) },
            profileContext = { inputProfile },
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
        inputProfile = com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext("moonlight", "gamestream", "$hostId:$appId")
        com.ambient.tv.ui.gamepad.retro.RetroInputSettings.lastPlayed.value = inputProfile
        qualityProfile = preferences.moonlightStreamProfile
        sessionProfile = qualityProfile
        validLaunch = true
        // A dedicated native GameStream activity must not leave Ambient's previous
        // background audio/video session running underneath it.
        com.ambient.tv.ui.player.BackgroundPlaybackSession.stop()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.rumbleEvents.collect { rumble ->
                    if (gameplayAllowed && hasWindowFocus() &&
                        engine.generation(streamOwner) == rumble.generation) {
                        if (rumble.port in companion.reservedPorts()) companion.rumble(rumble)
                        else feedback.rumble(rumble)
                    }
                }
            }
        }
        setContent {
            MoonlightStreamContent(
                engine = engine,
                owner = streamOwner,
                pointer = pointer,
                hostId = hostId,
                appId = appId,
                streamProfile = qualityProfile,
                sessionProfile = sessionProfile,
                displayReady = displayReady,
                displayResult = displayResult,
                onUpdateProfile = { profile ->
                    val validated = profile.validated()
                    preferences.moonlightStreamProfile = validated
                    qualityProfile = validated
                },
                onSendText = { engine.sendText(streamOwner, it) },
                onQuitRemote = { quitRemoteGame(hostId, appId) },
                remoteQuitBusy = remoteQuitBusy,
                remoteQuitError = remoteQuitError,
                controlsShown = osdVisible,
                focusRevision = focusRevision,
                showControls = { showOsd(true) },
                disconnect = ::disconnectAndFinish,
                closeControls = { showOsd(false) },
                onStarted = {
                    input.releaseAll()
                    companion.republishReservations()
                    companion.interaction(playing = false, menu = false)
                },
                onManualRetry = {
                    engine.stop(streamOwner)
                    prepareDisplay()
                },
                onCompanionMode = { phase, menu ->
                    val allowed = phase == MoonlightStreamPhase.STREAMING && !menu &&
                        hasWindowFocus() && engine.owns(streamOwner)
                    if (!allowed && gameplayAllowed) { releaseRemoteInputs(); feedback.cancelAll() }
                    gameplayAllowed = allowed
                    if (allowed) { input.refreshAssignments(); input.republish() }
                    companion.interaction(playing = allowed, menu = menu && hasWindowFocus())
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!validLaunch) return
        prepareDisplay()
        input.registerAttachedDevices()
        inputManager.registerInputDeviceListener(deviceListener, null)
        companion.attach(
            owner = streamOwner,
            title = getString(R.string.moonlight_title),
            usedPorts = { input.localUsedPorts() },
            profileContext = inputProfile,
            onMenu = { runOnUiThread { showOsd(!osdVisible) } },
        )
        companion.interaction(playing = false, menu = false)
    }

    private fun prepareDisplay() {
        displayPreparation?.cancel()
        displaySession.restore()
        sessionProfile = qualityProfile
        displayReady = false
        displayPreparation = lifecycleScope.launch {
            displayResult = try {
                displaySession.prepare(sessionProfile, preferences.displayModeWhitelist,
                    preferences.displaySwitchPauseMs)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Moonlight display preparation failed")
                displaySession.restore()
                displaySession.snapshot(unavailable = true)
            }
            displayReady = true
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showOsd(!osdVisible)
            return true
        }
        if (gameplayAllowed && hasWindowFocus() && engine.owns(streamOwner) && (input.onKey(event) || keyboard.onKey(event))) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gameplayAllowed && hasWindowFocus() && engine.owns(streamOwner) && input.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun showOsd(visible: Boolean) {
        if (visible && !osdVisible) {
            gameplayAllowed = false
            releaseRemoteInputs()
            feedback.cancelAll()
        }
        osdVisible = visible
        if (visible) companion.interaction(playing = false, menu = hasWindowFocus())
    }

    private fun releaseRemoteInputs() {
        input.releaseAll()
        keyboard.releaseAll()
        pointer.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusRevision++
        if (!hasFocus) {
            gameplayAllowed = false
            releaseRemoteInputs()
            feedback.cancelAll()
            companion.interaction(playing = false, menu = false)
        }
    }

    override fun onStop() {
        gameplayAllowed = false
        quitJob?.cancel()
        if (validLaunch) inputManager.unregisterInputDeviceListener(deviceListener)
        feedback.cancelAll()
        companion.detach()
        releaseRemoteInputs()
        engine.stop(streamOwner)
        displayPreparation?.cancel()
        displayPreparation = null
        displayReady = false
        if (validLaunch) displaySession.restore()
        super.onStop()
    }

    /** Explicit host-side termination; never a side effect of Back or stream disconnect. */
    private fun quitRemoteGame(hostId: String, appId: Int) {
        if (!engine.owns(streamOwner) || remoteQuitBusy || engine.status.value.phase != MoonlightStreamPhase.STREAMING) return
        remoteQuitBusy = true
        remoteQuitError = false
        quitJob = lifecycleScope.launch {
            try {
                if (moonlightTransport.quitRemoteApp(hostId, appId)) disconnectAndFinish()
                else remoteQuitError = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                remoteQuitError = true
            } finally {
                remoteQuitBusy = false
            }
        }
    }

    private fun disconnectAndFinish() {
        releaseRemoteInputs()
        engine.stop(streamOwner)
        finish()
    }

    override fun onDestroy() {
        companion.detach()
        releaseRemoteInputs()
        engine.stop(streamOwner)
        displayPreparation?.cancel()
        if (validLaunch) displaySession.restore()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST_ID = "moonlight_host_id"
        const val EXTRA_APP_ID = "moonlight_app_id"
    }
}

private class MoonlightSurfaceBinding(val surface: Surface)

@Composable
private fun MoonlightStreamContent(
    engine: MoonlightStreamEngine,
    owner: Any,
    pointer: MoonlightPointerRouter,
    hostId: String,
    appId: Int,
    streamProfile: MoonlightStreamProfile,
    sessionProfile: MoonlightStreamProfile,
    displayReady: Boolean,
    displayResult: MoonlightDisplayResult,
    onUpdateProfile: (MoonlightStreamProfile) -> Unit,
    onSendText: (String) -> Unit,
    onQuitRemote: () -> Unit,
    remoteQuitBusy: Boolean,
    remoteQuitError: Boolean,
    controlsShown: Boolean,
    focusRevision: Int,
    showControls: () -> Unit,
    closeControls: () -> Unit,
    disconnect: () -> Unit,
    onCompanionMode: (MoonlightStreamPhase, Boolean) -> Unit,
    onStarted: () -> Unit,
    onManualRetry: () -> Unit,
) {
    val globalStatus by engine.status.collectAsState()
    val status = if (globalStatus.owner === owner) globalStatus else com.ambient.tv.moonlight.MoonlightStreamStatus()
    // Surface identity is separate from the mutable next-connection profile. No decoder or
    // remote app launch occurs while the physical display is still switching.
    var binding by remember { mutableStateOf<MoonlightSurfaceBinding?>(null) }
    var startFailed by remember { mutableStateOf(false) }
    var retryRevision by remember { mutableStateOf(0) }
    var autoRetries by remember { mutableStateOf(0) }
    var resumeOnly by remember { mutableStateOf(false) }
    var excludedVideoFormats by remember { mutableStateOf(0) }
    LaunchedEffect(engine, hostId, appId, binding, displayReady, sessionProfile, retryRevision) {
        startFailed = false
        val target = binding ?: return@LaunchedEffect
        if (!displayReady || !target.surface.isValid) return@LaunchedEffect
        // A resize/return from background can follow asynchronous teardown of the old stream.
        val idle = withTimeoutOrNull(5_000L) {
            while (engine.isBusy()) delay(50L)
            true
        } ?: false
        if (!target.surface.isValid) return@LaunchedEffect
        if (!idle) {
            startFailed = true
            return@LaunchedEffect
        }
        if (engine.start(owner, hostId, appId, target.surface, sessionProfile,
                displayResult.mode?.refreshRate, displayResult.hdr10, resumeOnly = resumeOnly,
                excludedVideoFormats = excludedVideoFormats)) {
            val generation = engine.generation(owner)
            onStarted()
            try { awaitCancellation() } finally { if (generation != null) engine.stop(owner, generation) }
        } else startFailed = true
    }
    LaunchedEffect(status.generation, status.phase, status.retryable, displayReady) {
        if (displayReady && status.phase == MoonlightStreamPhase.FAILED && status.retryable && autoRetries < 2) {
            delay(1000L * (autoRetries + 1))
            autoRetries++
            resumeOnly = true
            excludedVideoFormats = excludedVideoFormats or status.rejectedVideoFormat
            retryRevision++
        }
    }
    var panel by remember { mutableStateOf(MoonlightPlaybackPage.NONE) }
    var panelOrigin by remember { mutableStateOf(MoonlightPlaybackPage.NONE) }
    LaunchedEffect(controlsShown) {
        if (!controlsShown) panel = MoonlightPlaybackPage.NONE
    }
    LaunchedEffect(status.phase, controlsShown, panel, focusRevision, startFailed) {
        onCompanionMode(status.phase, controlsShown || panel != MoonlightPlaybackPage.NONE ||
            status.phase != MoonlightStreamPhase.STREAMING || startFailed)
    }
    val resumeFocus = remember { FocusRequester() }
    val textFocus = remember { FocusRequester() }
    val infoFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val disconnectFocus = remember { FocusRequester() }
    LaunchedEffect(controlsShown, status.phase, panel) {
        if ((controlsShown || status.phase != MoonlightStreamPhase.STREAMING) &&
            panel == MoonlightPlaybackPage.NONE) {
            withFrameNanos { }
            runCatching {
                val target = when {
                    controlsShown && panelOrigin == MoonlightPlaybackPage.TEXT -> textFocus
                    controlsShown && panelOrigin == MoonlightPlaybackPage.INFO -> infoFocus
                    controlsShown && panelOrigin != MoonlightPlaybackPage.NONE -> settingsFocus
                    controlsShown && status.phase == MoonlightStreamPhase.STREAMING -> resumeFocus
                    else -> disconnectFocus
                }
                target.requestFocus()
                if (panelOrigin != MoonlightPlaybackPage.NONE) panelOrigin = MoonlightPlaybackPage.NONE
            }
        }
    }
    BackHandler(enabled = panel == MoonlightPlaybackPage.NONE) {
        if (controlsShown) disconnect() else showControls()
    }
    val backPanel = {
        panel = if (panel == MoonlightPlaybackPage.ROOT) MoonlightPlaybackPage.NONE
            else MoonlightPlaybackPage.ROOT
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val video = status.metrics.video
        val width = video.width.takeIf { it > 0 } ?: sessionProfile.width
        val height = video.height.takeIf { it > 0 } ?: sessionProfile.height
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val surfaceWidth = minOf(maxWidth, maxHeight * aspect)
        val surfaceHeight = surfaceWidth / aspect
        AndroidView(
            modifier = Modifier.size(surfaceWidth, surfaceHeight),
            factory = { context ->
                SurfaceView(context).apply {
                    isFocusableInTouchMode = true
                    pointer.attach(this)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            binding = MoonlightSurfaceBinding(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            pointer.release()
                            binding = null
                        }
                    })
                }
            },
            update = { view ->
                val inputEnabled = !controlsShown && panel == MoonlightPlaybackPage.NONE &&
                    status.phase == MoonlightStreamPhase.STREAMING && !startFailed && engine.owns(owner)
                view.setOnTouchListener { v, event -> inputEnabled && v.hasWindowFocus() && pointer.touch(v, event) }
                view.setOnGenericMotionListener { v, event -> inputEnabled && v.hasWindowFocus() && pointer.mouse(v, event) }
                view.setOnCapturedPointerListener { v, event -> inputEnabled && v.hasWindowFocus() && pointer.mouse(v, event, captured = true) }

            },
        )
        if (!controlsShown && status.phase == MoonlightStreamPhase.STREAMING &&
            !com.ambient.tv.ui.mobile.isTelevision(LocalContext.current)) {
            TvActionButton(stringResource(R.string.moonlight_player_options), showControls,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), icon = Icons.Filled.Settings)
        }
        if (!displayReady || controlsShown || status.phase != MoonlightStreamPhase.STREAMING || startFailed) {
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
                    subtitle = if (!displayReady) stringResource(R.string.moonlight_display_switching)
                    else if (startFailed) stringResource(R.string.moonlight_stream_failed)
                    else status.error ?: stringResource(when (status.phase) {
                        MoonlightStreamPhase.STREAMING -> R.string.moonlight_stream_running
                        MoonlightStreamPhase.ENDED -> R.string.moonlight_stream_ended
                        MoonlightStreamPhase.FAILED -> R.string.moonlight_stream_failed
                        else -> R.string.moonlight_stream_connecting
                    }),
                )
                // As in the regular video/game OSD: primary action at left, options at right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (startFailed || status.phase in setOf(MoonlightStreamPhase.FAILED, MoonlightStreamPhase.ENDED)) {
                            TvActionButton(stringResource(R.string.moonlight_retry_connection), {
                                autoRetries = 0
                                excludedVideoFormats = 0
                                resumeOnly = status.retryable || resumeOnly
                                onManualRetry()
                                retryRevision++
                            }, icon = Icons.Filled.PlayArrow)
                        }
                        if (status.phase == MoonlightStreamPhase.STREAMING && controlsShown) {
                            TvActionButton(stringResource(R.string.moonlight_stream_resume),
                                closeControls, modifier = Modifier.focusRequester(resumeFocus),
                                icon = Icons.Filled.PlayArrow)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.phase == MoonlightStreamPhase.STREAMING) {
                            TvActionButton(stringResource(R.string.moonlight_player_input_short),
                                {
                                    panelOrigin = MoonlightPlaybackPage.TEXT
                                    panel = MoonlightPlaybackPage.TEXT
                                }, modifier = Modifier.focusRequester(textFocus),
                                icon = Icons.Filled.Keyboard)
                        }
                        TvActionButton(stringResource(R.string.moonlight_player_info_short),
                            {
                                panelOrigin = MoonlightPlaybackPage.INFO
                                panel = MoonlightPlaybackPage.INFO
                            }, modifier = Modifier.focusRequester(infoFocus),
                            icon = Icons.Filled.Info)
                        TvActionButton(stringResource(R.string.moonlight_player_options),
                            {
                                panelOrigin = MoonlightPlaybackPage.ROOT
                                panel = MoonlightPlaybackPage.ROOT
                            },
                            modifier = Modifier.focusRequester(settingsFocus),
                            icon = Icons.Filled.Settings)
                        TvActionButton(stringResource(R.string.moonlight_stream_disconnect),
                            disconnect, modifier = Modifier.focusRequester(disconnectFocus),
                            icon = Icons.Filled.Stop)
                    }
                }
            }
        }
        MoonlightPlaybackPanel(
            page = panel,
            status = status,
            streamProfile = streamProfile,
            activeProfile = sessionProfile,
            displayResult = displayResult,
            onUpdateProfile = onUpdateProfile,
            onPage = { panel = it },
            onBack = backPanel,
            onSendText = onSendText,
            onQuitRemote = onQuitRemote,
            remoteQuitBusy = remoteQuitBusy,
            remoteQuitError = remoteQuitError,
        )
    }
    DisposableEffect(engine, hostId, appId) {
        onDispose { engine.stop(owner) }
    }
}

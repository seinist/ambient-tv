package com.ambient.tv.moonlight

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.ambient.tv.data.local.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One owner for the native Moonlight GameStream connection.
 *
 * A native stream is neither Media3 nor Libretro; it never changes their playback state.
 * Disconnecting closes the transport only. It does NOT invoke NvHTTP.quitApp() or shut down
 * Sunshine/Windows. Old native callbacks are ignored via the immutable session generation.
 */
@Singleton
class MoonlightStreamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val transport: MoonlightNativeTransport,
    private val crypto: MoonlightKeystoreCryptoProvider,
    private val libraryStore: MoonlightLibraryStore,
) {
    private data class Active(
        val generation: Long,
        val hostId: String,
        val appId: Int,
        var connection: NvConnection? = null,
        var video: MoonlightMediaCodecRenderer? = null,
        var audio: MoonlightAudioTrackRenderer? = null,
        var started: Boolean = false,
        var cancelled: Boolean = false,
        var stopIssued: Boolean = false,
        var preparation: Job? = null,
        var audioFocus: AudioFocusRequest? = null,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val revision = AtomicLong(0)
    private val mutable = MutableStateFlow(MoonlightStreamStatus())
    val status: StateFlow<MoonlightStreamStatus> = mutable.asStateFlow()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var active: Active? = null

    /** All calls are non-blocking for the Compose UI, including HTTPS launch negotiation. */
    fun start(hostId: String, appId: Int, surface: Surface, profile: MoonlightStreamProfile): Boolean {
        if (!surface.isValid || appId <= 0 || !prefs.moonlightEnabled) return false
        val valid = runCatching { profile.validated() }.getOrNull() ?: return false
        val session = synchronized(lock) {
            if (active != null) return false
            Active(revision.incrementAndGet(), hostId, appId).also {
                active = it
                mutable.value = MoonlightStreamStatus(MoonlightStreamPhase.CONNECTING)
            }
        }
        session.preparation = scope.launch {
            try {
                val target = transport.verifiedHost(hostId)
                val apps = transport.loadApplications(hostId).apps
                val app = apps.firstOrNull { it.id == appId }
                    ?: throw SecurityException("App is no longer published by this paired host")
                val videoFormat = MoonlightHardwareVideo.supportedFormat(valid)
                if (videoFormat == 0) throw UnsupportedOperationException("No compatible hardware video decoder")
                if (!surface.isValid) throw IllegalStateException("Stream surface was destroyed")
                if (!isOwned(session)) return@launch
                if (!requestAudioFocus(session)) throw IllegalStateException("Audio focus denied")

                val video = MoonlightMediaCodecRenderer(surface, valid) { text ->
                    fail(session, text)
                }
                val audio = MoonlightAudioTrackRenderer()
                val audioConfig = when (valid.audioChannels) {
                    6 -> MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
                    8 -> MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
                    else -> MoonBridge.AUDIO_CONFIGURATION_STEREO
                }
                val config = StreamConfiguration.Builder()
                    .setApp(NvApp(app.title, app.id, false))
                    .setResolution(valid.width, valid.height)
                    .setRefreshRate(valid.fps)
                    .setLaunchRefreshRate(valid.fps)
                    .setClientRefreshRateX100(valid.fps * 100)
                    .setBitrate(valid.bitrateKbps)
                    .setSupportedVideoFormats(videoFormat)
                    .setAudioConfiguration(audioConfig)
                    .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                    .setAttachedGamepadMaskByCount(1)
                    .setColorSpace(MoonBridge.COLORSPACE_REC_709)
                    .setColorRange(MoonBridge.COLOR_RANGE_LIMITED)
                    .build()
                val connection = NvConnection(
                    context.applicationContext,
                    ComputerDetails.AddressTuple(target.profile.host, target.profile.port),
                    target.httpsPort, prefs.moonlightDeviceId, config, crypto, target.pinnedCertificate,
                )
                val stillOwned = synchronized(lock) {
                    if (active !== session || session.cancelled) false else {
                        session.connection = connection
                        session.video = video
                        session.audio = audio
                        mutable.value = MoonlightStreamStatus(
                            MoonlightStreamPhase.CONNECTING, app.title, "Sunshine GameStream",
                        )
                        true
                    }
                }
                if (!stillOwned) {
                    releaseAudioFocus(session)
                    return@launch
                }
                // NvConnection spawns its own native start thread; callbacks are generation-gated.
                connection.start(audio, video, callbacks(session))
            } catch (cancel: CancellationException) {
                finishStopped(session)
                throw cancel
            } catch (error: Exception) {
                fail(session, error.message ?: "Moonlight streaming could not start")
            }
        }
        return true
    }

    fun stop() {
        val current = synchronized(lock) { active } ?: return
        stopSession(current)
    }

    private fun stopSession(session: Active) {
        val connection = synchronized(lock) {
            if (active !== session || session.stopIssued) return
            session.cancelled = true
            session.stopIssued = true
            if (mutable.value.phase != MoonlightStreamPhase.FAILED)
                mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.STOPPING)
            session.connection
        }
        // The reviewed upstream NvConnection patch owns its semaphore and permits a safe
        // stop before connectionStarted, without leaking global native-connection permits.
        session.preparation?.cancel()
        if (connection == null) {
            finishStopped(session)
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching { connection.stop() }
                finishStopped(session)
            }
        }
    }

    private fun isOwned(session: Active): Boolean = synchronized(lock) {
        active === session && !session.cancelled
    }

    fun sendGamepad(
        port: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ) {
        val conn = synchronized(lock) { active?.takeIf { it.started && !it.cancelled }?.connection }
        runCatching { conn?.sendControllerInput(port, mask, buttons, lt, rt, lx, ly, rx, ry) }
    }

    fun sendKeyboard(virtualKey: Short, pressed: Boolean, modifiers: Byte) {
        val conn = synchronized(lock) { active?.takeIf { it.started && !it.cancelled }?.connection }
        val direction = if (pressed) KeyboardPacket.KEY_DOWN else KeyboardPacket.KEY_UP
        runCatching { conn?.sendKeyboardInput(virtualKey, direction, modifiers, 0) }
    }

    fun sendMousePosition(x: Int, y: Int, width: Int, height: Int) {
        if (width !in 1..32767 || height !in 1..32767) return
        val conn = synchronized(lock) { active?.takeIf { it.started && !it.cancelled }?.connection }
        runCatching {
            conn?.sendMousePosition(x.coerceIn(0, width).toShort(), y.coerceIn(0, height).toShort(),
                width.toShort(), height.toShort())
        }
    }

    fun sendMouseButton(pressed: Boolean) {
        val conn = synchronized(lock) { active?.takeIf { it.started && !it.cancelled }?.connection }
        runCatching {
            if (pressed) conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
            else conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        }
    }

    fun sendMouseScroll(clicks: Int) {
        val conn = synchronized(lock) { active?.takeIf { it.started && !it.cancelled }?.connection }
        runCatching { conn?.sendMouseScroll(clicks.coerceIn(-8, 8).toByte()) }
    }

    private fun callbacks(session: Active): NvConnectionListener = object : NvConnectionListener {
        override fun stageStarting(stage: String) = Unit
        override fun stageComplete(stage: String) = Unit
        override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
            fail(session, "Sunshine connection failed ($errorCode)")
        }
        override fun connectionStarted() {
            val wasCancelled = synchronized(lock) {
                if (active !== session) return
                session.started = true
                if (!session.cancelled) {
                    mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.STREAMING)
                    libraryStore.recordLaunch(session.hostId, session.appId)
                }
                session.cancelled
            }
            if (wasCancelled) stopSession(session)
        }
        override fun connectionTerminated(errorCode: Int) {
            if (errorCode != 0 && !session.cancelled)
                fail(session, "Sunshine stream disconnected ($errorCode)")
            else stopSession(session)
        }
        override fun connectionStatusUpdate(connectionStatus: Int) = Unit
        override fun displayMessage(message: String) = Unit
        override fun displayTransientMessage(message: String) = Unit
        override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) = Unit
        override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) = Unit
        override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
            session.video?.setHdrMode(enabled, hdrMetadata)
        }
        override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) = Unit
        override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) = Unit
    }

    private fun requestAudioFocus(session: Active): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { focus ->
                if (focus == AudioManager.AUDIOFOCUS_LOSS) stopSession(session)
            }.build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return false
        val keep = synchronized(lock) {
            if (active !== session || session.cancelled) false
            else { session.audioFocus = request; true }
        }
        if (!keep) audioManager.abandonAudioFocusRequest(request)
        return keep
    }

    private fun releaseAudioFocus(session: Active) {
        val request = synchronized(lock) {
            session.audioFocus.also { session.audioFocus = null }
        } ?: return
        audioManager.abandonAudioFocusRequest(request)
    }

    private fun fail(session: Active, text: String) {
        synchronized(lock) {
            if (active !== session) return
            mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.FAILED, error = text)
        }
        stopSession(session)
    }

    private fun finishStopped(session: Active) {
        synchronized(lock) {
            if (active !== session) return
            active = null
            mutable.value = mutable.value.copy(
                phase = if (mutable.value.phase == MoonlightStreamPhase.FAILED)
                    MoonlightStreamPhase.FAILED else MoonlightStreamPhase.ENDED,
            )
        }
        releaseAudioFocus(session)
        // Native connection teardown also owns renderer.stop()/cleanup(); if startup failed
        // before native setup, these calls are harmless and release no hardware resources.
        runCatching { session.video?.cleanup() }
        runCatching { session.audio?.cleanup() }
    }
}

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
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        val owner: Any,
        val hostId: String,
        val appId: Int,
        val profile: MoonlightStreamProfile,
        var connection: NvConnection? = null,
        var advertisedControllerMask: Int = 0,
        var video: MoonlightMediaCodecRenderer? = null,
        var audio: MoonlightAudioTrackRenderer? = null,
        var started: Boolean = false,
        var cancelled: Boolean = false,
        var stopIssued: Boolean = false,
        var preparation: Job? = null,
        var audioFocus: AudioFocusRequest? = null,
        var metricsJob: Job? = null,
    )

    private val lock = Any()
    private val inputGate = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val revision = AtomicLong(0)
    private val mutable = MutableStateFlow(MoonlightStreamStatus())
    val status: StateFlow<MoonlightStreamStatus> = mutable.asStateFlow()
    private val mutableRumble = MutableSharedFlow<MoonlightRumble>(extraBufferCapacity = 16)
    val rumbleEvents = mutableRumble.asSharedFlow()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var active: Active? = null
    private var localControllerMask = 0
    private val remoteControllerPorts = mutableSetOf<Int>()
    private val localCaps = mutableMapOf<Int, MoonlightPadCapabilities>()
    private val remoteCaps = mutableMapOf<Int, MoonlightPadCapabilities>()

    /** All calls are non-blocking for the Compose UI, including HTTPS launch negotiation. */
    fun start(owner: Any, hostId: String, appId: Int, surface: Surface, profile: MoonlightStreamProfile,
              displayRefreshHz: Float? = null, displayHdr10: Boolean? = null): Boolean {
        if (!surface.isValid || appId <= 0 || !prefs.moonlightEnabled) return false
        val valid = runCatching { profile.validated() }.getOrNull() ?: return false
        val session = synchronized(lock) {
            if (active != null) return false
            Active(revision.incrementAndGet(), owner, hostId, appId, valid).also {
                active = it
                mutable.value = MoonlightStreamStatus(MoonlightStreamPhase.CONNECTING)
            }
        }
        session.preparation = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val target = transport.verifiedHost(hostId)
                val apps = transport.loadApplications(target).apps
                val app = apps.firstOrNull { it.id == appId }
                    ?: throw SecurityException("App is no longer published by this paired host")
                val videoFormat = MoonlightHardwareVideo.supportedFormat(valid,
                    appHdrSupported = app.hdrSupported,
                    displayHdr10 = displayHdr10 ?: MoonlightHardwareVideo.displaySupportsHdr10(context),
                    hostCodecModes = target.hostCodecModes,
                )
                if (videoFormat == 0) throw UnsupportedOperationException("No compatible hardware video decoder")
                if (!surface.isValid) throw IllegalStateException("Stream surface was destroyed")
                if (!isOwned(session)) return@launch
                if (!requestAudioFocus(session)) throw IllegalStateException("Audio focus denied")

                val video = MoonlightMediaCodecRenderer(surface, valid) { text ->
                    fail(session, text)
                }
                val audio = MoonlightAudioTrackRenderer { text -> fail(session, text) }
                val audioConfig = when (valid.audioChannels) {
                    6 -> MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
                    8 -> MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
                    else -> MoonBridge.AUDIO_CONFIGURATION_STEREO
                }
                val initialMask = synchronized(lock) { localControllerMask or remoteMask() }
                val config = StreamConfiguration.Builder()
                    .setApp(NvApp(app.title, app.id, app.hdrSupported))
                    .setResolution(valid.width, valid.height)
                    .setRefreshRate(valid.fps)
                    .setLaunchRefreshRate(valid.fps)
                    .setClientRefreshRateX100(displayRefreshHz?.takeIf { it.isFinite() && it in 1f..999f }
                        ?.let { (it * 100f).roundToInt() } ?: valid.fps * 100)
                    .setBitrate(valid.bitrateKbps)
                    .setSupportedVideoFormats(videoFormat)
                    .setAudioConfiguration(audioConfig)
                    .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                    .setAttachedGamepadMask(initialMask)
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
                        session.advertisedControllerMask = 0
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
                stopSession(session)
                throw cancel
            } catch (error: Exception) {
                fail(session, error.message ?: "Moonlight streaming could not start")
            }
        }
        session.preparation?.start()
        return true
    }

    /** Failed sessions can still own native teardown even after their public phase changes. */
    fun isBusy(): Boolean = synchronized(lock) { active != null }

    fun stop(owner: Any) {
        val current = synchronized(lock) { active?.takeIf { it.owner === owner } } ?: return
        stopSession(current)
    }

    fun owns(owner: Any): Boolean = synchronized(lock) {
        active?.let { it.owner === owner && !it.cancelled } == true
    }

    private fun stopSession(session: Active) {
        synchronized(lock) {
            if (active !== session || session.stopIssued) return
            session.cancelled = true
            session.stopIssued = true
            session.metricsJob?.cancel()
            if (mutable.value.phase != MoonlightStreamPhase.FAILED)
                mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.STOPPING)
        }
        val preparation = session.preparation
        preparation?.cancel()
        scope.launch(Dispatchers.IO) {
            preparation?.join()
            val connection = synchronized(lock) { session.connection }
            // No more input can enter after cancellation; drain the previous JNI call.
            synchronized(inputGate) { Unit }
            if (runCatching { connection?.stop() }.isFailure) {
                synchronized(lock) {
                    if (active === session) mutable.value = mutable.value.copy(
                        phase = MoonlightStreamPhase.FAILED, error = "Moonlight teardown failed")
                }
                // Do not start a second native connection in an uncertain state.
                return@launch
            }
            finishStopped(session)
        }
    }

    private fun isOwned(session: Active): Boolean = synchronized(lock) {
        active === session && !session.cancelled
    }

    /** Hotplug-capable GameStream controller mask, shared with authenticated Companion phones. */
    private fun desiredControllerMask(): Int = localControllerMask or remoteMask()

    /** All native calls are outside the session lock; callbacks must not block on JNI. */
    private data class PadFrame(
        val connection: NvConnection?,
        val mask: Short,
        val arrivals: List<Pair<Int, MoonlightPadCapabilities>>,
    )

    private fun padFrame(owner: Any, change: () -> Unit): PadFrame = synchronized(lock) {
        val owned = active?.takeIf { it.owner === owner && !it.cancelled }
            ?: return@synchronized PadFrame(null, 0, emptyList())
        change()
        val session = owned.takeIf { it.started }
        val nextMask = desiredControllerMask()
        val newPorts = if (session == null) emptyList() else (0..3).filter { port ->
            nextMask and (1 shl port) != 0 &&
                session.advertisedControllerMask and (1 shl port) == 0
        }
        if (session != null) session.advertisedControllerMask = nextMask
        PadFrame(session?.connection, nextMask.toShort(), newPorts.map { port ->
            port to (if (port in remoteControllerPorts) remoteCaps[port] else localCaps[port]
                ?: MoonlightPadCapabilities())
        }.map { (port, caps) -> port to (caps ?: MoonlightPadCapabilities()) })
    }

    private fun sendArrivals(frame: PadFrame) {
        val connection = frame.connection ?: return
        for ((port, caps) in frame.arrivals) {
            var flags = 0
            if (caps.analogTriggers) flags = flags or MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt()
            if (caps.rumble) flags = flags or MoonBridge.LI_CCAP_RUMBLE.toInt()
            if (caps.accelerometer) flags = flags or MoonBridge.LI_CCAP_ACCEL.toInt()
            if (caps.gyroscope) flags = flags or MoonBridge.LI_CCAP_GYRO.toInt()
            runCatching { connection.sendControllerArrivalEvent(port.toByte(), frame.mask,
                MoonBridge.LI_CTYPE_XBOX, caps.buttons, flags.toShort()) }
        }
    }

    fun sendGamepad(owner: Any,
        port: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
        capabilities: MoonlightPadCapabilities = MoonlightPadCapabilities(),
    ) {
        synchronized(inputGate) {
            if (port.toInt() !in 0..3) return
            val frame = padFrame(owner) {
                localControllerMask = mask.toInt() and 0xF
                if (localControllerMask and (1 shl port.toInt()) != 0) localCaps[port.toInt()] = capabilities
                else localCaps.remove(port.toInt())
            }
            sendArrivals(frame)
            runCatching {
                frame.connection?.sendControllerInput(port, frame.mask, buttons, lt, rt, lx, ly, rx, ry)
            }
        }
    }

    /** Phone claims and physical devices never silently share a port. */
    fun remoteReservedPorts(owner: Any): Set<Int> = synchronized(lock) {
        if (active?.owner === owner) remoteControllerPorts.toSet() else emptySet()
    }

    fun setRemotePortActive(owner: Any, port: Int, enabled: Boolean) {
        synchronized(inputGate) {
            if (port !in 0..3) return
            val frame = padFrame(owner) {
                if (enabled) {
                    remoteControllerPorts.add(port)
                    remoteCaps.putIfAbsent(port, MoonlightPadCapabilities())
                } else { remoteControllerPorts.remove(port); remoteCaps.remove(port) }
            }
            sendArrivals(frame)
            if (!enabled) runCatching {
                frame.connection?.sendControllerInput(port.toShort(), frame.mask, 0,
                    0, 0, 0, 0, 0, 0)
            }
        }
    }

    fun sendRemoteGamepad(owner: Any,
        port: Int, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ) {
        synchronized(inputGate) {
            if (port !in remoteReservedPorts(owner)) return
            val frame = padFrame(owner) {}
            sendArrivals(frame)
            runCatching {
                frame.connection?.sendControllerInput(port.toShort(), frame.mask, buttons,
                    lt, rt, lx, ly, rx, ry)
            }
        }
    }

    fun setRemoteMotionCapabilities(owner: Any, port: Int, mask: Int) {
        synchronized(inputGate) {
            if (port !in remoteReservedPorts(owner)) return
            val frame = padFrame(owner) {
                val old = remoteCaps[port] ?: MoonlightPadCapabilities()
                val next = old.copy(accelerometer = old.accelerometer || mask and 1 != 0,
                    gyroscope = old.gyroscope || mask and 2 != 0)
                if (old != next) {
                    remoteCaps[port] = next
                    active?.let { it.advertisedControllerMask = it.advertisedControllerMask and (1 shl port).inv() }
                }
            }
            sendArrivals(frame)
        }
    }

    fun activeSize(owner: Any): Pair<Int, Int>? = synchronized(lock) {
        val session = active?.takeIf { it.owner === owner && !it.cancelled } ?: return@synchronized null
        val video = session.video?.metrics()
        if (video != null && video.width > 0 && video.height > 0) video.width to video.height
        else session.profile.width to session.profile.height
    }

    private fun remoteMask(): Int =
        remoteControllerPorts.fold(0) { mask, port -> mask or (1 shl port) }

    fun sendKeyboard(owner: Any, virtualKey: Short, pressed: Boolean, modifiers: Byte) {
        synchronized(inputGate) {
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            val direction = if (pressed) KeyboardPacket.KEY_DOWN else KeyboardPacket.KEY_UP
            runCatching { conn?.sendKeyboardInput(virtualKey, direction, modifiers, 0) }
        }
    }

    fun sendMousePosition(owner: Any, x: Int, y: Int, width: Int, height: Int) {
        synchronized(inputGate) {
            if (width !in 1..32767 || height !in 1..32767) return
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            runCatching {
                conn?.sendMousePosition(x.coerceIn(0, width).toShort(), y.coerceIn(0, height).toShort(),
                    width.toShort(), height.toShort())
            }
        }
    }

    fun sendMouseButton(owner: Any, pressed: Boolean, button: Int = 1) {
        synchronized(inputGate) {
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            if (button !in 1..5) return
            runCatching {
                if (pressed) conn?.sendMouseButtonDown(button.toByte())
                else conn?.sendMouseButtonUp(button.toByte())
            }
        }
    }

    fun sendMouseScroll(owner: Any, clicks: Int) {
        synchronized(inputGate) {
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            runCatching { conn?.sendMouseScroll(clicks.coerceIn(-8, 8).toByte()) }
        }
    }

    /** Normalized multi-contact touchscreen events; native GameStream owns remote touch state. */
    fun sendTouchEvent(owner: Any, type: Byte, pointerId: Int, x: Float, y: Float,
                       pressure: Float = 1f): Int {
        synchronized(inputGate) {
            if (pointerId !in 0..15 || !x.isFinite() || !y.isFinite() || !pressure.isFinite())
                return MoonBridge.LI_ERR_UNSUPPORTED
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
                ?: return MoonBridge.LI_ERR_UNSUPPORTED
            return runCatching {
                conn.sendTouchEvent(type, pointerId, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f),
                    pressure.coerceIn(0f, 1f), 0f, 0f, 0.toShort())
            }.getOrDefault(MoonBridge.LI_ERR_UNSUPPORTED)
        }
    }

    /** Controller gyroscope and acceleration, from the authenticated Companion stream. */
    fun sendMotion(owner: Any, port: Int, type: Byte, x: Float, y: Float, z: Float) {
        synchronized(inputGate) {
            if (port !in 0..3 || listOf(x, y, z).any { !it.isFinite() }) return
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            runCatching { conn?.sendControllerMotionEvent(port.toByte(), type,
                x.coerceIn(-100f, 100f), y.coerceIn(-100f, 100f), z.coerceIn(-100f, 100f)) }
        }
    }

    fun sendMouseMove(owner: Any, dx: Int, dy: Int) {
        synchronized(inputGate) {
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            runCatching {
                conn?.sendMouseMove(dx.coerceIn(-32767, 32767).toShort(),
                    dy.coerceIn(-32767, 32767).toShort())
            }
        }
    }

    /** Remote text entry is explicit, short and independent of Android system-key controls. */
    fun sendText(owner: Any, text: String) {
        synchronized(inputGate) {
            val clean = text.filter { !it.isISOControl() || it == '\n' || it == '\t' }
                .take(512)
            if (clean.isEmpty()) return
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            runCatching { conn?.sendUtf8Text(clean) }
        }
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
            else scope.launch {
                synchronized(inputGate) { sendArrivals(padFrame(session.owner) {}) }
                startMetrics(session)
            }
        }
        override fun connectionTerminated(errorCode: Int) {
            if (errorCode != 0 && !session.cancelled)
                fail(session, "Sunshine stream disconnected ($errorCode)", retryable = true)
            else stopSession(session)
        }
        override fun connectionStatusUpdate(connectionStatus: Int) = Unit
        override fun displayMessage(message: String) {
            if (message == NvConnection.AMBIENT_OTHER_APP_RUNNING)
                fail(session, context.getString(com.ambient.tv.R.string.moonlight_other_app_running))
        }
        override fun displayTransientMessage(message: String) = Unit
        override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
            if (controllerNumber.toInt() !in 0..3 || !isOwned(session)) return
            mutableRumble.tryEmit(MoonlightRumble(controllerNumber.toInt(),
                lowFreqMotor.toInt() and 0xffff, highFreqMotor.toInt() and 0xffff))
        }
        override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
            if (controllerNumber.toInt() !in 0..3 || !isOwned(session)) return
            mutableRumble.tryEmit(MoonlightRumble(controllerNumber.toInt(),
                leftTrigger.toInt() and 0xffff, rightTrigger.toInt() and 0xffff))
        }
        override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
            session.video?.setHdrMode(enabled, hdrMetadata)
        }
        override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) = Unit
        override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) = Unit
    }

    private fun startMetrics(session: Active) {
        synchronized(lock) {
            if (active !== session || session.cancelled || session.metricsJob != null) return
            session.metricsJob = scope.launch(Dispatchers.IO) {
                var lastFrames = 0L
                var lastTime = System.nanoTime()
                while (isOwned(session)) {
                    kotlinx.coroutines.delay(1000)
                    if (!isOwned(session)) break
                    val video = session.video?.metrics() ?: continue
                    val audio = runCatching { session.audio?.metrics() }.getOrNull() ?: MoonlightAudioMetrics()
                    val rtt = synchronized(inputGate) {
                        if (isOwned(session)) runCatching { MoonBridge.getEstimatedRttInfo() }.getOrNull() else null
                    }
                    val now = System.nanoTime()
                    val fps = (video.rendered - lastFrames) * 1e9 / (now - lastTime).coerceAtLeast(1L)
                    lastFrames = video.rendered
                    lastTime = now
                    synchronized(lock) {
                        if (active === session && !session.cancelled)
                            mutable.value = mutable.value.copy(metrics = MoonlightStreamMetrics(
                                video, audio, rtt?.let { (it shr 32).toInt().takeIf { n -> n >= 0 } },
                                rtt?.let { it.toInt().takeIf { n -> n >= 0 } }, fps.coerceAtLeast(0.0)))
                    }
                }
            }
        }
    }

    private fun requestAudioFocus(session: Active): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { focus ->
                if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stopSession(session)
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

    private fun fail(session: Active, text: String, retryable: Boolean = false) {
        synchronized(lock) {
            if (active !== session || session.cancelled) return
            mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.FAILED, error = text, retryable = retryable && session.started)
        }
        stopSession(session)
    }

    private fun finishStopped(session: Active) {
        runCatching { releaseAudioFocus(session) }
        val videoReleased = runCatching { session.video?.finishCleanup() != false }.getOrDefault(false)
        runCatching { session.audio?.cleanup() }
        if (!videoReleased) {
            synchronized(lock) { if (active === session) mutable.value = mutable.value.copy(
                phase = MoonlightStreamPhase.FAILED, error = "Moonlight decoder teardown incomplete") }
            return
        }
        synchronized(lock) {
            if (active !== session) return
            localControllerMask = 0
            remoteControllerPorts.clear()
            localCaps.clear(); remoteCaps.clear()
            active = null
            mutable.value = mutable.value.copy(
                phase = if (mutable.value.phase == MoonlightStreamPhase.FAILED)
                    MoonlightStreamPhase.FAILED else MoonlightStreamPhase.ENDED,
            )
        }
    }
}

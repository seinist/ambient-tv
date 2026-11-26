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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.collect

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
        @Volatile var cancelled: Boolean = false,
        @Volatile var networkPoor: Boolean = false,
        @Volatile var touchSupported: Boolean? = null,
        var stopIssued: Boolean = false,
        var preparation: Job? = null,
        var audioFocus: AudioFocusRequest? = null,
        var metricsJob: Job? = null,
        var trustJob: Job? = null,
        val inputMailbox: MoonlightInputMailbox = MoonlightInputMailbox(),
        val inputLatency: MoonlightLatencyHistogram = MoonlightLatencyHistogram(),
    )

    private val lock = Any()
    private val inputGate = Any()
    private val inputExecutor = java.util.concurrent.ThreadPoolExecutor(0, 1, 15L,
        java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { task -> Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            task.run()
        }, "moonlight-input").apply { isDaemon = true } })
    private val inputOwnership = MoonlightInputOwnership()
    private val heldKeyboard = mutableMapOf<Short, Byte>()
    private val motionRequests = mutableMapOf<Pair<Int, Byte>, Pair<Int, Long>>()
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
              displayRefreshHz: Float? = null, displayHdr10: Boolean? = null,
              resumeOnly: Boolean = false, excludedVideoFormats: Int = 0,
              safeDecoder: Boolean = false): Boolean {
        if (!surface.isValid || appId <= 0 || !prefs.moonlightEnabled) return false
        val valid = runCatching { profile.validated() }.getOrNull() ?: return false
        val session = synchronized(lock) {
            if (active != null) return false
            Active(revision.incrementAndGet(), owner, hostId, appId, valid).also {
                active = it
                mutable.value = MoonlightStreamStatus(MoonlightStreamPhase.CONNECTING, owner = owner, generation = it.generation)
            }
        }
        session.preparation = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val target = transport.verifiedHost(hostId)
                // Playback authorization has just refreshed the pinned catalog. Reuse it only
                // for this exact freshly verified peer; direct entry or stale data still fetches.
                val cached = withContext(Dispatchers.IO) {
                    libraryStore.catalog(hostId)?.takeIf { it.peerIdentity == target.identity &&
                        libraryStore.isCatalogFresh(hostId, 15_000L) && libraryStore.isRecentlyOnline(hostId) }
                }
                val apps = cached?.apps ?: transport.loadApplications(target).apps
                val app = apps.firstOrNull { it.id == appId }
                    ?: throw SecurityException("App is no longer published by this paired host")
                val videoFormat = withContext(Dispatchers.IO) {
                    MoonlightHardwareVideo.initialize(context)
                    MoonlightHardwareVideo.supportedFormat(valid,
                        appHdrSupported = app.hdrSupported,
                        displayHdr10 = displayHdr10 ?: MoonlightHardwareVideo.displaySupportsHdr10(context),
                        hostCodecModes = target.hostCodecModes,
                    ) and excludedVideoFormats.inv()
                }
                if (videoFormat == 0) throw UnsupportedOperationException("No compatible hardware video decoder")
                if (!surface.isValid) throw IllegalStateException("Stream surface was destroyed")
                if (!isOwned(session)) return@launch
                if (!transport.current(target)) throw SecurityException("Moonlight host changed")
                if (!requestAudioFocus(session)) throw IllegalStateException("Audio focus denied")

                val rendererCaps = withContext(Dispatchers.IO) {
                    if (safeDecoder) 0 else runCatching {
                        MoonlightHardwareVideo.rendererCapabilities(valid, videoFormat)
                    }.getOrDefault(0)
                }
                if (!isOwned(session)) return@launch
                val video = MoonlightMediaCodecRenderer(surface, valid, rendererCaps) { text, rejected ->
                    val remaining = videoFormat and rejected.inv()
                    val conservativeRetry = MoonlightHardwareVideo.hasAdvancedCapabilities(rendererCaps)
                    fail(session, text, retryable = conservativeRetry || rejected == 0 || remaining != 0,
                        rejectedVideoFormat = if (!conservativeRetry && remaining != 0) rejected else 0,
                        safeDecoderRetry = conservativeRetry)
                }
                val audio = MoonlightAudioTrackRenderer(valid.pacing) { text -> fail(session, text, retryable = true) }
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
                    // Match the pinned official client. NvConnection detects VPN/remote routes
                    // and overrides this LAN maximum to 1024 bytes for those connections.
                    .setMaxPacketSize(1392)
                    .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                    .setAttachedGamepadMask(initialMask)
                    .setColorSpace(MoonBridge.COLORSPACE_REC_709)
                    .setColorRange(MoonBridge.COLOR_RANGE_LIMITED)
                    .build()
                val connection = NvConnection(
                    context.applicationContext,
                    ComputerDetails.AddressTuple(target.profile.host, target.profile.port),
                    target.httpsPort, prefs.moonlightDeviceId, config, crypto, target.pinnedCertificate,
                ).apply { setAmbientStartPolicy(resumeOnly, target.serverId) }
                val stillOwned = synchronized(lock) {
                    if (active !== session || session.cancelled) false else {
                        session.connection = connection
                        session.advertisedControllerMask = 0
                        session.video = video
                        session.audio = audio
                        mutable.value = MoonlightStreamStatus(
                            MoonlightStreamPhase.CONNECTING, app.title, "Sunshine GameStream",
                            owner = owner, generation = session.generation,
                        )
                        true
                    }
                }
                if (!stillOwned) {
                    releaseAudioFocus(session)
                    return@launch
                }
                session.trustJob = scope.launch {
                    combine(prefs.changes().onStart { emit(Unit) }, transport.trustRevisions) { _, _ -> Unit }
                        .collect {
                            if (isOwned(session) && !transport.current(target))
                                fail(session, context.getString(com.ambient.tv.R.string.moonlight_identity_changed))
                        }
                }
                // NvConnection spawns its own native start thread; callbacks are generation-gated.
                if (isOwned(session)) connection.start(audio, video, callbacks(session))
            } catch (cancel: CancellationException) {
                stopSession(session)
                throw cancel
            } catch (error: Exception) {
                fail(session, context.getString(if (error is SecurityException)
                    com.ambient.tv.R.string.moonlight_identity_changed else com.ambient.tv.R.string.moonlight_stream_failed))
            }
        }
        session.preparation?.start()
        return true
    }

    /** Failed sessions can still own native teardown even after their public phase changes. */
    fun isBusy(): Boolean = synchronized(lock) { active != null }

    fun generation(owner: Any): Long? = synchronized(lock) { active?.takeIf { it.owner === owner }?.generation }
    fun stop(owner: Any, generation: Long? = null) {
        val current = synchronized(lock) { active?.takeIf { it.owner === owner &&
            (generation == null || it.generation == generation) } } ?: return
        stopSession(current)
    }

    fun owns(owner: Any): Boolean = synchronized(lock) {
        active?.let { it.owner === owner && !it.cancelled } == true
    }

    private fun stopSession(session: Active) {
        synchronized(lock) {
            if (active !== session || session.stopIssued) return
            session.cancelled = true
            session.inputMailbox.close()
            session.stopIssued = true
            session.metricsJob?.cancel()
            session.trustJob?.cancel()
            if (mutable.value.phase != MoonlightStreamPhase.FAILED)
                mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.STOPPING)
        }
        val preparation = session.preparation
        preparation?.cancel()
        scope.launch(Dispatchers.IO) {
            val watchdog = scope.launch {
                kotlinx.coroutines.delay(6000)
                synchronized(lock) {
                    if (active === session) mutable.value = mutable.value.copy(
                        phase = MoonlightStreamPhase.FAILED, error = "Moonlight teardown is still running",
                        retryable = false)
                }
            }
            try {
            preparation?.join()
            val connection = synchronized(lock) { session.connection }
            // No more input can enter after cancellation; drain the previous JNI call.
            synchronized(inputGate) {
                // A cancelled mailbox must not replay presses. Neutralize inputs already handed
                // to the host before native stop (which may keep the remote game running).
                if (connection != null && session.started) {
                    val mask = synchronized(lock) { desiredControllerMask() }.toShort()
                    for (port in 0..3) if (mask.toInt() and (1 shl port) != 0)
                        runCatching { connection.sendControllerInput(port.toShort(), mask, 0, 0, 0, 0, 0, 0, 0) }
                    heldKeyboard.keys.forEach { key ->
                        runCatching { connection.sendKeyboardInput(key, KeyboardPacket.KEY_UP, 0, 0) }
                    }
                    inputOwnership.pressedMouseButtons().forEach { button ->
                        runCatching { connection.sendMouseButtonUp(button.toByte()) }
                    }
                    inputOwnership.activeTouchIds().forEach { id ->
                        runCatching { connection.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL, id,
                            0f, 0f, 0f, 0f, 0f, (-1).toShort()) }
                    }
                }
                heldKeyboard.clear()
                inputOwnership.clear(); motionRequests.clear()
            }
            var stopped = false
            repeat(3) {
                if (!stopped) {
                    stopped = runCatching { connection?.stop() }.isSuccess
                    if (!stopped) kotlinx.coroutines.delay(250)
                }
            }
            if (!stopped) {
                synchronized(lock) {
                    if (active === session) {
                        session.stopIssued = false
                        mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.FAILED,
                            error = "Moonlight teardown failed; retry or restart the app", retryable = false)
                    }
                }
                return@launch
            }
            finishStopped(session)
            } finally { watchdog.cancel() }
        }
    }

    private fun isOwned(session: Active): Boolean = synchronized(lock) {
        active === session && !session.cancelled
    }

    /**
     * Keep UI callbacks independent of JNI and its locks. A session-local bounded mailbox
     * preserves discrete edges and reservations; only equivalent adjacent analog updates
     * can replace each other. Overflow ends the session rather than losing a release.
     */
    private fun enqueueInput(owner: Any, key: Any? = null, operation: () -> Unit) {
        val session = synchronized(lock) { active?.takeIf { it.owner === owner && !it.cancelled } } ?: return
        val offeredAt = System.nanoTime()
        val offer = session.inputMailbox.offer(key) {
            synchronized(inputGate) {
                if (isOwned(session)) {
                    session.inputLatency.recordUs((System.nanoTime() - offeredAt) / 1000L)
                    operation()
                }
            }
        }
        scheduleInputDrain(session, offer)
    }

    private fun scheduleInputDrain(session: Active, offer: MoonlightInputMailbox.Offer) {
        when (offer) {
            MoonlightInputMailbox.Offer.START -> inputExecutor.execute {
                while (true) {
                    val task = session.inputMailbox.take() ?: break
                    if (runCatching { task() }.isFailure) {
                        fail(session, "Moonlight input dispatch failed", retryable = true)
                        break
                    }
                }
            }
            MoonlightInputMailbox.Offer.OVERFLOW -> fail(session, "Moonlight input queue overflow", retryable = true)
            else -> Unit
        }
    }

    private fun enqueueRelative(owner: Any, kind: String, dx: Int, dy: Int, limit: Int,
                                operation: (NvConnection, Int, Int) -> Unit) {
        val session = synchronized(lock) { active?.takeIf { it.owner === owner && !it.cancelled } } ?: return
        val offeredAt = System.nanoTime()
        val offer = session.inputMailbox.offerRelative(kind, dx.coerceIn(-limit, limit),
            dy.coerceIn(-limit, limit), limit) { x, y ->
            synchronized(inputGate) {
                val conn = synchronized(lock) {
                    active?.takeIf { it === session && it.started && !it.cancelled }?.connection
                }
                if (conn != null) {
                    session.inputLatency.recordUs((System.nanoTime() - offeredAt) / 1000L)
                    operation(conn, x, y)
                }
            }
        }
        scheduleInputDrain(session, offer)
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
        if (session != null) session.advertisedControllerMask = session.advertisedControllerMask and nextMask
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
            val result = runCatching { connection.sendControllerArrivalEvent(port.toByte(), frame.mask,
                MoonBridge.LI_CTYPE_XBOX, caps.buttons, flags.toShort()) }.getOrDefault(-1)
            if (result == 0) synchronized(lock) {
                active?.takeIf { it.connection === connection && !it.cancelled }?.let {
                    it.advertisedControllerMask = it.advertisedControllerMask or (1 shl port)
                }
            }
        }
    }

    fun sendGamepad(owner: Any,
        port: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
        capabilities: MoonlightPadCapabilities = MoonlightPadCapabilities(),
    ) {
        enqueueInput(owner, MoonlightPadUpdateKey(false, port.toInt(), mask.toInt(), buttons, lt, rt, lx, ly, rx, ry, capabilities)) {
            if (port.toInt() !in 0..3) return@enqueueInput
            val frame = padFrame(owner) {
                localControllerMask = mask.toInt() and 0xF
                if (localControllerMask and (1 shl port.toInt()) != 0) {
                    if (localCaps.put(port.toInt(), capabilities) != capabilities)
                        active?.let { it.advertisedControllerMask = it.advertisedControllerMask and (1 shl port.toInt()).inv() }
                } else localCaps.remove(port.toInt())
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
        enqueueInput(owner) {
            if (port !in 0..3) return@enqueueInput
            val frame = padFrame(owner) {
                if (enabled) {
                    remoteControllerPorts.add(port)
                    remoteCaps.putIfAbsent(port, MoonlightPadCapabilities(rumble = true))
                } else {
                    remoteControllerPorts.remove(port)
                    remoteCaps.remove(port)
                    motionRequests.keys.removeAll { it.first == port }
                }
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
        enqueueInput(owner, MoonlightPadUpdateKey(true, port, 0, buttons, lt, rt, lx, ly, rx, ry, null)) {
            if (port !in remoteReservedPorts(owner)) return@enqueueInput
            val frame = padFrame(owner) {}
            sendArrivals(frame)
            runCatching {
                frame.connection?.sendControllerInput(port.toShort(), frame.mask, buttons,
                    lt, rt, lx, ly, rx, ry)
            }
        }
    }

    fun setRemoteMotionCapabilities(owner: Any, port: Int, mask: Int) {
        enqueueInput(owner) {
            if (port !in remoteReservedPorts(owner)) return@enqueueInput
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
        enqueueInput(owner) {
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            val direction = if (pressed) KeyboardPacket.KEY_DOWN else KeyboardPacket.KEY_UP
            if (conn != null && runCatching { conn.sendKeyboardInput(virtualKey, direction, modifiers, 0) }.isSuccess) {
                if (pressed) heldKeyboard[virtualKey] = modifiers else heldKeyboard.remove(virtualKey)
            }
        }
    }

    fun sendMousePosition(owner: Any, x: Int, y: Int, width: Int, height: Int) {
        enqueueInput(owner, Triple("position", width, height)) {
            if (width !in 1..32767 || height !in 1..32767) return@enqueueInput
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            runCatching {
                conn?.sendMousePosition(x.coerceIn(0, width).toShort(), y.coerceIn(0, height).toShort(),
                    width.toShort(), height.toShort())
            }
        }
    }

    fun sendMouseButton(owner: Any, pressed: Boolean, button: Int = 1, source: Int = 0) {
        enqueueInput(owner) {
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            if (conn == null) return@enqueueInput
            val transition = inputOwnership.mouse(button, source, pressed) ?: return@enqueueInput
            runCatching {
                if (transition) conn.sendMouseButtonDown(button.toByte())
                else conn.sendMouseButtonUp(button.toByte())
            }
        }
    }

    fun sendMouseScroll(owner: Any, clicks: Int) {
        enqueueRelative(owner, "scroll", clicks, 0, 8) { conn, x, _ ->
            conn.sendMouseScroll(x.toByte())
        }
    }

    /** Normalized multi-contact touchscreen events; native GameStream owns remote touch state. */
    fun sendTouchEvent(owner: Any, type: Byte, pointerId: Int, x: Float, y: Float,
                       pressure: Float = 1f, source: Int = 0): Int {
        val id = inputOwnership.touchId(source, pointerId) ?: return MoonBridge.LI_ERR_UNSUPPORTED
        if (!x.isFinite() || !y.isFinite() || !pressure.isFinite()) return MoonBridge.LI_ERR_UNSUPPORTED
        val session = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled } }
            ?: return MoonBridge.LI_ERR_UNSUPPORTED
        if (session.touchSupported == false) return MoonBridge.LI_ERR_UNSUPPORTED
        fun dispatch(): Int {
            if (!isOwned(session)) return MoonBridge.LI_ERR_UNSUPPORTED
            val conn = session.connection ?: return MoonBridge.LI_ERR_UNSUPPORTED
            if (!inputOwnership.acceptsTouch(id, type)) return 0
            val result = runCatching {
                conn.sendTouchEvent(type, id, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f),
                    pressure.coerceIn(0f, 1f), 0f, 0f, (-1).toShort())
            }.getOrDefault(MoonBridge.LI_ERR_UNSUPPORTED)
            if (result == 0) inputOwnership.acceptedTouch(id, type)
            return result
        }
        // Only the first actual DOWN probes host support synchronously. The result is fixed for
        // this connection, so later contacts and MOVE events use the ordered worker.
        if (session.touchSupported == null) synchronized(inputGate) {
            val result = dispatch()
            if (type == MoonBridge.LI_TOUCH_EVENT_DOWN) {
                if (result == 0) session.touchSupported = true
                else if (result == MoonBridge.LI_ERR_UNSUPPORTED) session.touchSupported = false
            }
            return result
        }
        enqueueInput(owner, if (type == MoonBridge.LI_TOUCH_EVENT_MOVE) "touch:$id" else null) {
            val result = dispatch()
            if (result != 0 && type != MoonBridge.LI_TOUCH_EVENT_MOVE)
                fail(session, "Moonlight touch input failed", retryable = true)
        }
        return 0
    }

    /** Controller gyroscope and acceleration, from the authenticated Companion stream. */
    fun sendMotion(owner: Any, port: Int, type: Byte, x: Float, y: Float, z: Float) {
        enqueueInput(owner, Triple("motion", port, type)) {
            if (port !in remoteReservedPorts(owner) || !x.isFinite() || !y.isFinite() || !z.isFinite()) return@enqueueInput
            val now = System.nanoTime()
            val request = motionRequests[port to type] ?: return@enqueueInput
            if (request.first <= 0 || now - request.second < 1_000_000_000L / request.first) return@enqueueInput
            motionRequests[port to type] = request.first to now
            val conn = synchronized(lock) { active?.takeIf { it.owner === owner && it.started && !it.cancelled }?.connection }
            runCatching { conn?.sendControllerMotionEvent(port.toByte(), type,
                x.coerceIn(-100f, 100f), y.coerceIn(-100f, 100f), z.coerceIn(-100f, 100f)) }
        }
    }

    fun sendMouseMove(owner: Any, dx: Int, dy: Int) {
        enqueueRelative(owner, "mouse", dx, dy, 32767) { conn, x, y ->
            conn.sendMouseMove(x.toShort(), y.toShort())
        }
    }

    /** Remote text entry is explicit, short and independent of Android system-key controls. */
    fun sendText(owner: Any, text: String) {
        enqueueInput(owner) {
            val clean = text.filter { !it.isISOControl() || it == '\n' || it == '\t' }
                .take(512)
            if (clean.isEmpty()) return@enqueueInput
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
                enqueueInput(session.owner) { if (isOwned(session)) sendArrivals(padFrame(session.owner) {}) }
                startMetrics(session)
            }
        }
        override fun connectionTerminated(errorCode: Int) {
            if (errorCode != 0 && !session.cancelled)
                fail(session, "Sunshine stream disconnected ($errorCode)", retryable = true,
                    adaptNetworkQuality = session.networkPoor && session.profile.adaptiveQuality)
            else stopSession(session)
        }
        override fun connectionStatusUpdate(connectionStatus: Int) {
            if (isOwned(session)) session.networkPoor = connectionStatus == MoonBridge.CONN_STATUS_POOR
        }
        override fun displayMessage(message: String) {
            when (message) {
                NvConnection.AMBIENT_OTHER_APP_RUNNING ->
                    fail(session, context.getString(com.ambient.tv.R.string.moonlight_other_app_running))
                "ambient:resume-not-running" ->
                    fail(session, context.getString(com.ambient.tv.R.string.moonlight_stream_ended))
            }
        }
        override fun displayTransientMessage(message: String) = Unit
        override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
            if (controllerNumber.toInt() !in 0..3 || !isOwned(session)) return
            mutableRumble.tryEmit(MoonlightRumble(controllerNumber.toInt(),
                lowFreqMotor.toInt() and 0xffff, highFreqMotor.toInt() and 0xffff, session.generation))
        }
        override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
            // Trigger rumble is not advertised; do not map it to the ordinary motors.
        }
        override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
            if (isOwned(session)) session.video?.setHdrMode(enabled, hdrMetadata)
        }
        override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
            val port = controllerNumber.toInt()
            if (port !in 0..3 || motionType !in setOf(MoonBridge.LI_MOTION_TYPE_ACCEL, MoonBridge.LI_MOTION_TYPE_GYRO)) return
            enqueueInput(session.owner) {
                if (isOwned(session)) motionRequests[port to motionType] =
                    (reportRateHz.toInt() and 0xffff).coerceIn(0, 120) to 0L
            }
        }
        override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) = Unit
    }

    private fun startMetrics(session: Active) {
        synchronized(lock) {
            if (active !== session || session.cancelled || session.metricsJob != null) return
            session.metricsJob = scope.launch(Dispatchers.IO) {
                var lastFrames = 0L
                var lastPresented = 0L
                var lastReceived = 0L
                var noOutputSeconds = 0
                var backlogSeconds = 0
                var noPresentationSeconds = 0
                var poorNetworkSeconds = 0
                var lastMissing = 0L
                val avPolicy = MoonlightAvQueuePolicy()
                var lastTime = System.nanoTime()
                while (isOwned(session)) {
                    kotlinx.coroutines.delay(1000)
                    if (!isOwned(session)) break
                    val video = session.video?.metrics() ?: continue
                    val audio = runCatching { session.audio?.metrics() }.getOrNull() ?: MoonlightAudioMetrics()
                    val rtt = synchronized(inputGate) {
                        if (isOwned(session)) runCatching { MoonBridge.getEstimatedRttInfo() }.getOrNull() else null
                    }
                    val pendingVideo = synchronized(inputGate) {
                        if (isOwned(session)) runCatching { MoonBridge.getPendingVideoFrames() }.getOrDefault(0) else 0
                    }
                    // Once stalled, the recovery deadline keeps running even when IDR
                    // recovery suppresses incoming pictures. Don't wait forever for more packets.
                    if (video.rendered > lastFrames) noOutputSeconds = 0
                    else if (video.received > lastReceived || noOutputSeconds > 0 || lastFrames == 0L)
                        noOutputSeconds++
                    val intervalReceived = (video.received - lastReceived).coerceAtLeast(0L)
                    val intervalMissing = (video.missing - lastMissing).coerceAtLeast(0L)
                    lastReceived = video.received
                    lastMissing = video.missing
                    // Do not interrupt a healthy or static stream solely because of a noisy
                    // status callback. Only opt-in, sustained packet loss permits a resume.
                    val highLoss = intervalMissing > 2 && intervalMissing * 10 > intervalReceived + intervalMissing
                    poorNetworkSeconds = if (session.networkPoor && highLoss) poorNetworkSeconds + 1
                        else (poorNetworkSeconds - 1).coerceAtLeast(0)
                    if (session.profile.adaptiveQuality && session.profile.bitrateKbps > 1000 && poorNetworkSeconds >= 10) {
                        fail(session, "Moonlight network loss: reconnecting at lower bitrate", retryable = true,
                            adaptNetworkQuality = true)
                        break
                    }
                    backlogSeconds = if (pendingVideo >= 8) backlogSeconds + 1 else 0
                    noPresentationSeconds = if (lastPresented > 0 && video.presented == lastPresented && video.rendered > lastFrames)
                        noPresentationSeconds + 1 else 0
                    if (noOutputSeconds == 3 || backlogSeconds == 3 || noPresentationSeconds == 3) session.video?.requestRecovery()
                    if (noOutputSeconds >= 7 || backlogSeconds >= 7 || noPresentationSeconds >= 7) {
                        fail(session, "Moonlight decoder stopped producing frames", retryable = true)
                        break
                    }
                    val now = System.nanoTime()
                    val queueBalance = avPolicy.sample(now, audio, video, pendingVideo)
                    session.audio?.setQueueReduction(queueBalance.reductionMs)
                    val interval = (now - lastTime).coerceAtLeast(1L)
                    val fps = (video.rendered - lastFrames) * 1e9 / interval
                    val presentedFps = (video.presented - lastPresented) * 1e9 / interval
                    lastFrames = video.rendered
                    lastPresented = video.presented
                    lastTime = now
                    synchronized(lock) {
                        if (active === session && !session.cancelled)
                            mutable.value = mutable.value.copy(metrics = MoonlightStreamMetrics(
                                video, audio, rtt?.let { (it shr 32).toInt().takeIf { n -> n >= 0 } },
                                rtt?.let { it.toInt().takeIf { n -> n >= 0 } }, fps.coerceAtLeast(0.0),
                                presentedFps.coerceAtLeast(0.0), pendingVideo.coerceAtLeast(0), session.networkPoor,
                                session.inputLatency.p95Ms(), session.inputMailbox.depth(),
                                queueBalance.differenceMs, queueBalance.reductionMs))
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
                if (!isOwned(session)) return@setOnAudioFocusChangeListener
                when (focus) {
                    AudioManager.AUDIOFOCUS_LOSS -> stopSession(session)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        session.audio?.setMuted(true)
                    AudioManager.AUDIOFOCUS_GAIN -> session.audio?.setMuted(false)
                }
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

    private fun fail(session: Active, text: String, retryable: Boolean = false,
                     rejectedVideoFormat: Int = 0, adaptNetworkQuality: Boolean = false,
                     safeDecoderRetry: Boolean = false) {
        synchronized(lock) {
            if (active !== session || session.cancelled) return
            mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.FAILED, error = text, retryable = retryable && (session.started || rejectedVideoFormat != 0 || safeDecoderRetry),
                rejectedVideoFormat = rejectedVideoFormat, adaptNetworkQuality = adaptNetworkQuality,
                safeDecoderRetry = safeDecoderRetry)
        }
        stopSession(session)
    }

    private fun finishStopped(session: Active) {
        runCatching { releaseAudioFocus(session) }
        val videoReleased = runCatching { session.video?.finishCleanup() != false }.getOrDefault(false)
        val audioReleased = runCatching { session.audio?.finishCleanup() != false }.getOrDefault(false)
        if (!videoReleased || !audioReleased) {
            synchronized(lock) { if (active === session) {
                session.stopIssued = false
                mutable.value = mutable.value.copy(phase = MoonlightStreamPhase.FAILED,
                    error = "Moonlight renderer teardown incomplete; retry or restart the app", retryable = false)
            } }
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

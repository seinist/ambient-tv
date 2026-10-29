package com.ambient.tv.renderer

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ambient.tv.BuildConfig
import com.ambient.tv.branding.AppBrand
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.shared.SharedMediaLink
import com.ambient.tv.data.shared.SharedMediaRepository
import com.ambient.tv.launcher.LauncherIntents
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.util.LocalNetworkAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

enum class RendererAnswer { ONCE, ALWAYS, DENY }

/** A sender wants to start playback; the app asks first unless that sender is trusted. */
data class RendererPrompt(val id: Long, val sender: String, val title: String, val onAnswer: (RendererAnswer) -> Unit)

object RendererPrompts {
    private val state = MutableStateFlow<RendererPrompt?>(null)
    val current: StateFlow<RendererPrompt?> = state.asStateFlow()
    internal fun show(prompt: RendererPrompt) { state.value = prompt }
    internal fun dismiss(id: Long) { state.update { if (it?.id == id) null else it } }
}

private sealed interface SoapResult {
    class Ok(val values: List<Pair<String, String>> = emptyList()) : SoapResult
    class Fault(val code: Int, val description: String) : SoapResult
}

/**
 * DLNA/UPnP MediaRenderer: apps such as BubbleUPnP or Web Video Caster find this app on the
 * LAN and send it media URLs. Playback runs through the normal player (shared-link path: no
 * server, no history). Off by default; runs while the app is in the foreground or plays
 * something it received, answers only peers in a local subnet, and asks before playing from a
 * sender that is not trusted.
 */
@Singleton
class DlnaRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val shared: SharedMediaRepository,
) : DefaultLifecycleObserver {
    private class Media(val uri: String, val metadata: String, val link: SharedMediaLink)
    private class Subscription(val service: RendererService, val host: String, val callbacks: List<String>, @Volatile var expiresAt: Long) {
        val seq = AtomicLong(0)
        @Volatile var failures = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor { Thread(it, "renderer-control").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var foreground = false
    @Volatile private var running = false
    private val listeningState = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = listeningState.asStateFlow()
    private val receivedTitleState = MutableStateFlow<String?>(null)
    val receivedTitle: StateFlow<String?> = receivedTitleState.asStateFlow()
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var ticker: ScheduledExecutorService? = null
    /** GENA deliveries, one at a time in order; a slow subscriber never stalls the ticker. */
    private var events: ThreadPoolExecutor? = null
    private var ssdp: SsdpAdvertiser? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Transport state, guarded by [lock].
    private var media: Media? = null
    private var playingKey: String? = null
    /** Address of the sender that started the current playback. */
    private var controller: String? = null
    private var seenActive = false
    private var openingSince = 0L
    private var prompt: RendererPrompt? = null
    /** The device whose request [prompt] asks about. */
    private var promptHost: String? = null
    private var promptSince = 0L

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val lastEvents = ConcurrentHashMap<RendererService, String>()
    @Volatile private var cachedAddresses: Pair<Long, List<RendererAddress>> = 0L to emptyList()

    private val serverHeader = "Android/${Build.VERSION.RELEASE} UPnP/1.0 AmbientTV/${BuildConfig.VERSION_NAME}"
    private val audio: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    /** Called once from the Application; follows the app's foreground state from then on. */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { foreground = true; refresh() }
    override fun onStop(owner: LifecycleOwner) { foreground = false; refresh() }

    /** Settings, permission or foreground changed: start or stop to match. */
    fun refresh() {
        control.execute {
            val wanted = com.ambient.tv.ui.mobile.isTelevision(context) && preferences.dlnaRendererEnabled && LocalNetworkAccess.isGranted(context) && (foreground || rendererPlaybackActive())
            if (wanted) start() else stop()
        }
    }

    private fun start() {
        if (running) return
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PREFERRED_PORT)) } }
            .getOrElse { runCatching { ServerSocket(0) }.getOrElse { Timber.w(it, "Renderer: no port"); return } }
        running = true
        server = socket
        val pool = ThreadPoolExecutor(1, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
            { Thread(it, "renderer-http").apply { isDaemon = true } },
            { task, _ -> (task as? ConnectionTask)?.reject() })
        workers = pool
        Thread({
            while (running) {
                val client = try { socket.accept() } catch (_: java.io.IOException) {
                    // Closed on stop; anything else (descriptor shortage) is transient: keep serving.
                    if (!running || socket.isClosed) break
                    Thread.sleep(200)
                    continue
                }
                if (!allowedPeer(client.inetAddress)) { runCatching { client.close() }; continue }
                runCatching { client.soTimeout = 5_000 }
                pool.execute(ConnectionTask(client))
            }
        }, "renderer-accept").apply { isDaemon = true; start() }
        multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("ambient-tv-renderer")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        ssdp = SsdpAdvertiser(preferences.dlnaRendererUdn, serverHeader) { "http://${it.hostAddress}:${socket.localPort}/desc.xml" }
            .also { it.start() }
        events = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(MAX_QUEUED_EVENTS),
            { Thread(it, "renderer-events").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
        ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "renderer-tick").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { tick() } }, 1, 1, TimeUnit.SECONDS) }
        listeningState.value = true
        Timber.i("Renderer listening on port %d", socket.localPort)
    }

    private fun stop() {
        if (!running) return
        running = false
        listeningState.value = false
        receivedTitleState.value = null
        ssdp?.stop(); ssdp = null
        runCatching { server?.close() }; server = null
        workers?.shutdownNow(); workers = null
        ticker?.shutdownNow(); ticker = null
        events?.shutdownNow(); events = null
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }; multicastLock = null
        subscriptions.clear()
        lastEvents.clear()
        synchronized(lock) { prompt?.let { RendererPrompts.dismiss(it.id) }; prompt = null }
    }

    private inner class ConnectionTask(private val socket: Socket) : Runnable {
        override fun run() { runCatching { handle(socket) }.onFailure { Timber.d(it, "Renderer request failed") }; runCatching { socket.close() } }
        fun reject() {
            runCatching { writeRendererResponse(socket.getOutputStream(), 503, "Service Unavailable", server = serverHeader) }
            runCatching { socket.close() }
        }
    }

    // --- HTTP ------------------------------------------------------------------------------

    private fun handle(socket: Socket) {
        val request = readRendererRequest(BufferedInputStream(socket.getInputStream()))
        val out = BufferedOutputStream(socket.getOutputStream())
        val peer = socket.inetAddress
        when (request.method) {
            "GET", "HEAD" -> {
                val body = when {
                    request.path == "/desc.xml" -> deviceDescription(preferences.dlnaRendererUdn, friendlyName(), AppBrand.name, BuildConfig.VERSION_NAME)
                    else -> RendererService.byScpdPath(request.path)?.let(::scpdFor)
                }
                if (body == null) respond(out, 404, "Not Found")
                else respond(out, 200, "OK", if (request.method == "HEAD") "" else body)
            }
            "POST" -> {
                val service = RendererService.byControlPath(request.path) ?: return respond(out, 404, "Not Found")
                control(service, request, peer, out)
            }
            "SUBSCRIBE" -> {
                val service = RendererService.byEventPath(request.path) ?: return respond(out, 404, "Not Found")
                subscribe(service, request, peer, out)
            }
            "UNSUBSCRIBE" -> {
                val removed = request.headers["sid"]?.let { subscriptions.remove(it) }
                respond(out, if (removed != null) 200 else 412, if (removed != null) "OK" else "Precondition Failed")
            }
            else -> respond(out, 405, "Method Not Allowed")
        }
    }

    private fun respond(out: OutputStream, status: Int, reason: String, body: String = "", headers: List<Pair<String, String>> = emptyList()) =
        writeRendererResponse(out, status, reason, body, headers = headers, server = serverHeader)

    private fun control(service: RendererService, request: RendererRequest, peer: InetAddress, out: OutputStream) {
        val (type, action) = parseSoapAction(request.headers["soapaction"]) ?: return respond(out, 500, "Internal Server Error", soapFault(401, "Invalid Action"))
        if (type != service.type) return respond(out, 500, "Internal Server Error", soapFault(401, "Invalid Action"))
        val args = runCatching { parseSoapArguments(request.body, action) }.getOrElse {
            return respond(out, 500, "Internal Server Error", soapFault(402, "Invalid Args"))
        }
        val sender = describeSender(request.headers["user-agent"], peer)
        val result = runCatching {
            when (service) {
                RendererService.AVTRANSPORT -> transport(action, args, peer, sender)
                RendererService.RENDERING -> rendering(action, args, peer, sender)
                RendererService.CONNECTIONS -> connections(action, args)
            }
        }.getOrElse { Timber.w(it, "Renderer action %s failed", action); SoapResult.Fault(501, "Action Failed") }
        when (result) {
            is SoapResult.Ok -> respond(out, 200, "OK", soapResponse(service.type, action, result.values),
                listOf("EXT" to ""))
            is SoapResult.Fault -> respond(out, 500, "Internal Server Error", soapFault(result.code, result.description))
        }
    }

    // --- AVTransport -----------------------------------------------------------------------

    private fun transport(action: String, args: Map<String, String>, peer: InetAddress, sender: String): SoapResult {
        if (args["InstanceID"]?.trim().let { it != null && it != "0" }) return SoapResult.Fault(718, "Invalid InstanceID")
        return when (action) {
            "SetAVTransportURI" -> setUri(args, peer, sender)
            "Play" -> play(peer, sender)
            "Pause" -> when {
                ours() == null -> SoapResult.Fault(701, "Transition not available")
                !mayControl(peer, sender) -> notAuthorized()
                else -> { onMain { BackgroundPlaybackSession.userPause() }; SoapResult.Ok() }
            }
            "Stop" -> if (!mayReplace(peer, sender)) notAuthorized() else { stopOurs(); SoapResult.Ok() }
            "Seek" -> if (ours() != null && !mayControl(peer, sender)) notAuthorized() else seek(args)
            "Next", "Previous" -> SoapResult.Fault(701, "Transition not available")
            "GetTransportInfo" -> SoapResult.Ok(listOf("CurrentTransportState" to transportState(), "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1"))
            "GetPositionInfo" -> {
                val status = ours()?.status
                val current = synchronized(lock) { media }
                SoapResult.Ok(listOf(
                    "Track" to if (current != null) "1" else "0",
                    "TrackDuration" to formatUpnpTime(status?.durationMs ?: 0),
                    "TrackMetaData" to current?.metadata.orEmpty(),
                    "TrackURI" to current?.uri.orEmpty(),
                    "RelTime" to formatUpnpTime(status?.positionMs ?: 0),
                    "AbsTime" to formatUpnpTime(status?.positionMs ?: 0),
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                ))
            }
            "GetMediaInfo" -> {
                val current = synchronized(lock) { media }
                SoapResult.Ok(listOf(
                    "NrTracks" to if (current != null) "1" else "0",
                    "MediaDuration" to formatUpnpTime(ours()?.status?.durationMs ?: 0),
                    "CurrentURI" to current?.uri.orEmpty(),
                    "CurrentURIMetaData" to current?.metadata.orEmpty(),
                    "NextURI" to "", "NextURIMetaData" to "",
                    "PlayMedium" to if (current != null) "NETWORK" else "NONE",
                    "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
                ))
            }
            "GetCurrentTransportActions" -> SoapResult.Ok(listOf("Actions" to transportActions(transportState())))
            "GetDeviceCapabilities" -> SoapResult.Ok(listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED"))
            "GetTransportSettings" -> SoapResult.Ok(listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"))
            else -> SoapResult.Fault(401, "Invalid Action")
        }
    }

    private fun setUri(args: Map<String, String>, peer: InetAddress, sender: String): SoapResult {
        val uri = args["CurrentURI"].orEmpty().trim()
        // Neither replacing nor clearing may touch what another device is playing or asking about.
        if (!mayReplace(peer, sender)) return notAuthorized()
        if (uri.isEmpty()) {
            stopOurs()
            synchronized(lock) { media = null }
            return SoapResult.Ok()
        }
        val metadata = args["CurrentURIMetaData"].orEmpty().trim()
        val didl = parseDidl(metadata)
        val link = SharedMediaLink.parseRendered(uri, didl?.mimeType, didl?.upnpClass, didl?.title)
            ?: return SoapResult.Fault(714, "Illegal MIME-type")
        // A new URI while ours is playing switches to it, as renderers are expected to; in the
        // background the player cannot be opened, so the running one stays untouched.
        val switching = ours()?.status?.playing == true
        if (switching && !foreground) return SoapResult.Fault(701, "Transition not available")
        synchronized(lock) {
            media = Media(uri, metadata, link)
            // A prompt for the previous URI would start nothing once answered; ask again on Play.
            prompt?.let { RendererPrompts.dismiss(it.id) }
            prompt = null
        }
        if (switching) requestStart(peer, sender)
        return SoapResult.Ok()
    }

    private fun play(peer: InetAddress, sender: String): SoapResult {
        synchronized(lock) { if (media == null) return SoapResult.Fault(701, "Transition not available") }
        if (ours() != null) {
            if (!mayControl(peer, sender)) return notAuthorized()
            onMain { BackgroundPlaybackSession.userPlay() }
            return SoapResult.Ok()
        }
        // Opening the player needs the app on screen; in the background nothing new starts.
        if (!foreground) return SoapResult.Fault(701, "Transition not available")
        val busy = synchronized(lock) { prompt != null || opening() }
        if (!busy) requestStart(peer, sender)
        return SoapResult.Ok()
    }

    private fun seek(args: Map<String, String>): SoapResult {
        val unit = args["Unit"]?.trim()?.uppercase()
        if (unit != "REL_TIME" && unit != "ABS_TIME") return SoapResult.Fault(710, "Seek mode not supported")
        val target = parseUpnpTime(args["Target"]) ?: return SoapResult.Fault(711, "Illegal seek target")
        if (ours() == null) return SoapResult.Fault(701, "Transition not available")
        onMain { BackgroundPlaybackSession.seekTo(target) }
        return SoapResult.Ok()
    }

    /** Trusted (or asking disabled) → play now; otherwise ask on the TV first. */
    private fun requestStart(peer: InetAddress, sender: String) {
        val host = peer.hostAddress.orEmpty()
        val current = synchronized(lock) { media } ?: return
        if (!preferences.dlnaRendererAsk || sender in preferences.dlnaRendererTrusted) {
            startPlayback(current, host)
            return
        }
        val id = SystemClock.elapsedRealtimeNanos()
        val request = RendererPrompt(id, sender, current.link.title) { answer -> onAnswer(id, host, sender, current, answer) }
        synchronized(lock) {
            prompt?.let { RendererPrompts.dismiss(it.id) }
            prompt = request
            promptHost = host
            promptSince = SystemClock.elapsedRealtime()
        }
        RendererPrompts.show(request)
    }

    private fun onAnswer(id: Long, host: String, sender: String, requested: Media, answer: RendererAnswer) {
        RendererPrompts.dismiss(id)
        val stillCurrent = synchronized(lock) {
            if (prompt?.id != id) return
            prompt = null
            media === requested
        }
        // An address-only entry from older versions is replaced by the sender it now belongs to.
        if (answer == RendererAnswer.ALWAYS) preferences.dlnaRendererTrusted = preferences.dlnaRendererTrusted - host + sender
        if (answer != RendererAnswer.DENY && stillCurrent) startPlayback(requested, host)
    }

    private fun startPlayback(target: Media, host: String) {
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("cast",
            "start format=${target.link.extension.ifBlank { "unknown" }} audio=${target.link.audioOnly}")
        val key = shared.register(target.link)
        synchronized(lock) {
            playingKey = key
            controller = host
            seenActive = false
            openingSince = SystemClock.elapsedRealtime()
        }
        LauncherIntents.post(Destinations.player(key, restart = true))
    }

    private fun stopOurs() {
        if (ours() != null) onMain { BackgroundPlaybackSession.stop() }
        synchronized(lock) {
            playingKey = null
            prompt?.let { RendererPrompts.dismiss(it.id) }
            prompt = null
        }
    }

    /** The running playback session, when it is the one this renderer started. */
    private fun ours(): BackgroundPlaybackSession.Snapshot? {
        val snapshot = BackgroundPlaybackSession.snapshot.value
        val key = synchronized(lock) { playingKey } ?: return null
        return snapshot.takeIf { it.metadata?.mediaId == key }
    }

    private fun rendererPlaybackActive(): Boolean = ours() != null

    /**
     * Another device on the LAN may not pause, seek, stop or turn up what someone else started.
     * A lasting permission belongs to the [sender] (app and address), not to the address alone:
     * another device that later receives the same address by DHCP is asked again.
     */
    private fun mayControl(peer: InetAddress, sender: String): Boolean {
        val host = peer.hostAddress.orEmpty()
        return host == synchronized(lock) { controller } || sender in preferences.dlnaRendererTrusted || !preferences.dlnaRendererAsk
    }

    /** No foreign playback runs and no other device's prompt is open, or [peer] may control. */
    private fun mayReplace(peer: InetAddress, sender: String): Boolean {
        if (mayControl(peer, sender)) return true
        if (ours() != null) return false
        val host = peer.hostAddress.orEmpty()
        return synchronized(lock) { prompt == null || promptHost == host }
    }

    private fun notAuthorized() = SoapResult.Fault(606, "Action not authorized")

    /** Caller holds [lock]. */
    private fun opening(): Boolean =
        playingKey != null && !seenActive && SystemClock.elapsedRealtime() - openingSince < OPEN_TIMEOUT_MS

    private fun transportState(): String {
        ours()?.let { snapshot ->
            return when {
                snapshot.status.ended -> "STOPPED"
                snapshot.status.playing -> "PLAYING"
                else -> "PAUSED_PLAYBACK"
            }
        }
        synchronized(lock) {
            if (prompt != null || opening()) return "TRANSITIONING"
            return if (media != null) "STOPPED" else "NO_MEDIA_PRESENT"
        }
    }

    private fun transportActions(state: String): String = when (state) {
        "PLAYING" -> "Pause,Stop,Seek"
        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
        "STOPPED" -> "Play"
        "TRANSITIONING" -> "Stop"
        else -> ""
    }

    // --- RenderingControl / ConnectionManager ----------------------------------------------

    private fun rendering(action: String, args: Map<String, String>, peer: InetAddress, sender: String): SoapResult {
        if (args["InstanceID"]?.trim().let { it != null && it != "0" }) return SoapResult.Fault(702, "Invalid InstanceID")
        val channel = args["Channel"]?.trim()
        if (channel != null && channel != "Master" && action in setOf("GetVolume", "SetVolume", "GetMute", "SetMute")) {
            return SoapResult.Fault(402, "Invalid Args")
        }
        val manager = audio ?: return SoapResult.Fault(501, "Action Failed")
        // Reading is harmless; changing the TV's volume needs a playback this sender may steer.
        if (action == "SetVolume" || action == "SetMute") {
            if (ours() == null || !mayControl(peer, sender)) return notAuthorized()
        }
        return when (action) {
            "GetVolume" -> SoapResult.Ok(listOf("CurrentVolume" to volumePercent(manager).toString()))
            "SetVolume" -> {
                val desired = args["DesiredVolume"]?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
                    ?: return SoapResult.Fault(402, "Invalid Args")
                val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, (desired * max + 50) / 100, 0)
                SoapResult.Ok()
            }
            "GetMute" -> SoapResult.Ok(listOf("CurrentMute" to if (manager.isStreamMute(AudioManager.STREAM_MUSIC)) "1" else "0"))
            "SetMute" -> {
                val mute = when (args["DesiredMute"]?.trim()?.lowercase()) {
                    "1", "true", "yes" -> true
                    "0", "false", "no" -> false
                    else -> return SoapResult.Fault(402, "Invalid Args")
                }
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                SoapResult.Ok()
            }
            "ListPresets" -> SoapResult.Ok(listOf("CurrentPresetNameList" to "FactoryDefaults"))
            "SelectPreset" -> SoapResult.Ok()
            else -> SoapResult.Fault(401, "Invalid Action")
        }
    }

    private fun volumePercent(manager: AudioManager): Int {
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 + max / 2) / max
    }

    private fun connections(action: String, args: Map<String, String>): SoapResult = when (action) {
        "GetProtocolInfo" -> SoapResult.Ok(listOf("Source" to "", "Sink" to SINK_PROTOCOL_INFO))
        "GetCurrentConnectionIDs" -> SoapResult.Ok(listOf("ConnectionIDs" to "0"))
        "GetCurrentConnectionInfo" -> if (args["ConnectionID"]?.trim() != "0") SoapResult.Fault(706, "Invalid connection reference")
            else SoapResult.Ok(listOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK"))
        else -> SoapResult.Fault(401, "Invalid Action")
    }

    // --- Eventing ---------------------------------------------------------------------------

    private fun subscribe(service: RendererService, request: RendererRequest, peer: InetAddress, out: OutputStream) {
        val timeout = parseTimeoutSeconds(request.headers["timeout"])
        val subscriptionHeaders = { sid: String -> listOf("SID" to sid, "TIMEOUT" to "Second-$timeout") }
        request.headers["sid"]?.let { sid ->
            val existing = subscriptions[sid] ?: return respond(out, 412, "Precondition Failed")
            existing.expiresAt = SystemClock.elapsedRealtime() + timeout * 1000L
            return respond(out, 200, "OK", headers = subscriptionHeaders(sid))
        }
        if (request.headers["nt"] != "upnp:event") return respond(out, 412, "Precondition Failed")
        // Events only go back to the subscriber itself, never to a third host.
        val callbacks = parseCallbacks(request.headers["callback"]).filter { url ->
            runCatching { URI(url) }.getOrNull()?.let { it.scheme == "http" && it.host == peer.hostAddress && it.port in 1..65535 } == true
        }
        if (callbacks.isEmpty()) return respond(out, 412, "Precondition Failed")
        val host = peer.hostAddress.orEmpty()
        val sid = "uuid:${UUID.randomUUID()}"
        val subscription = Subscription(service, host, callbacks, SystemClock.elapsedRealtime() + timeout * 1000L)
        // Check and insert atomically: parallel SUBSCRIBE requests must not overshoot the limits.
        val accepted = synchronized(subscriptions) {
            if (subscriptions.size >= MAX_SUBSCRIPTIONS || subscriptions.values.count { it.host == host } >= MAX_SUBSCRIPTIONS_PER_HOST) false
            else { subscriptions[sid] = subscription; true }
        }
        if (!accepted) return respond(out, 503, "Service Unavailable")
        respond(out, 200, "OK", headers = subscriptionHeaders(sid))
        out.flush()
        // The initial event follows the response, as GENA requires, through the same ordered queue.
        val body = eventBody(service)
        events?.execute { notify(sid, subscription, body) }
    }

    private fun eventBody(service: RendererService): String = when (service) {
        RendererService.AVTRANSPORT -> lastChangeEvent(service, transportEventValues())
        RendererService.RENDERING -> lastChangeEvent(service, renderingEventValues())
        RendererService.CONNECTIONS -> propertySet(listOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to SINK_PROTOCOL_INFO, "CurrentConnectionIDs" to "0"))
    }

    private fun transportEventValues(): List<Pair<String, String>> {
        val state = transportState()
        val current = synchronized(lock) { media }
        val duration = formatUpnpTime(ours()?.status?.durationMs ?: 0)
        return listOf(
            "TransportState" to state,
            "TransportStatus" to "OK",
            "CurrentTransportActions" to transportActions(state),
            "AVTransportURI" to current?.uri.orEmpty(),
            "CurrentTrackURI" to current?.uri.orEmpty(),
            "NumberOfTracks" to if (current != null) "1" else "0",
            "CurrentTrackDuration" to duration,
            "CurrentMediaDuration" to duration,
            "CurrentPlayMode" to "NORMAL",
            "TransportPlaySpeed" to "1",
        )
    }

    private fun renderingEventValues(): List<Pair<String, String>> {
        val manager = audio ?: return emptyList()
        return listOf("Volume" to volumePercent(manager).toString(), "Mute" to if (manager.isStreamMute(AudioManager.STREAM_MUSIC)) "1" else "0")
    }

    private fun notify(sid: String, subscription: Subscription, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val seq = subscription.seq.getAndIncrement()
        val delivered = subscription.callbacks.any { callback ->
            runCatching {
                val uri = URI(callback)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(uri.host, uri.port), 2_000)
                    socket.soTimeout = 2_000
                    val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                    val head = "NOTIFY $path HTTP/1.1\r\nHOST: ${uri.host}:${uri.port}\r\n" +
                        "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                        "SID: $sid\r\nSEQ: $seq\r\nCONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n\r\n"
                    val out = socket.getOutputStream()
                    out.write(head.toByteArray(Charsets.US_ASCII))
                    out.write(bytes)
                    out.flush()
                    socket.getInputStream().read() // Wait for the status line; content is irrelevant.
                }
            }.isSuccess
        }
        if (delivered) subscription.failures = 0
        else if (++subscription.failures >= 3) subscriptions.remove(sid)
    }

    private fun tick() {
        receivedTitleState.value = ours()?.metadata?.title
        val now = SystemClock.elapsedRealtime()
        subscriptions.entries.removeAll { it.value.expiresAt < now }
        val snapshot = BackgroundPlaybackSession.snapshot.value
        synchronized(lock) {
            val key = playingKey
            if (key != null) {
                val active = snapshot.metadata?.mediaId == key
                if (active) seenActive = true
                // Closed in the player (or never opened): the transport is stopped.
                else if (seenActive || now - openingSince > OPEN_TIMEOUT_MS) playingKey = null
            }
            prompt?.takeIf { now - promptSince > PROMPT_TIMEOUT_MS }?.let { RendererPrompts.dismiss(it.id); prompt = null }
        }
        if (!foreground && !rendererPlaybackActive()) refresh()
        RendererService.entries.filter { it != RendererService.CONNECTIONS }.forEach { service ->
            val body = eventBody(service)
            if (lastEvents.put(service, body) != body) {
                subscriptions.filter { it.value.service == service }.forEach { (sid, subscription) -> events?.execute { notify(sid, subscription, body) } }
            }
        }
    }

    // --- Helpers ------------------------------------------------------------------------------

    /** Only peers in one of this device's LAN subnets may talk to the renderer. */
    private fun allowedPeer(peer: InetAddress?): Boolean {
        peer ?: return false
        val now = SystemClock.elapsedRealtime()
        val addresses = cachedAddresses.takeIf { now - it.first < 10_000 }?.second
            ?: rendererAddresses().also { cachedAddresses = now to it }
        return addresses.any { sameSubnet(it.address, peer, it.prefixLength) }
    }

    private fun onMain(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).isSuccess
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post { ok.set(runCatching(block).isSuccess); done.countDown() }
        return done.await(2, TimeUnit.SECONDS) && ok.get()
    }

    fun displayName(): String = friendlyName()

    private fun friendlyName(): String {
        preferences.dlnaRendererName.takeIf { it.isNotBlank() }?.let { return it }
        val device = runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return "${AppBrand.name} ($device)"
    }

    private fun describeSender(userAgent: String?, peer: InetAddress): String = rendererSender(userAgent, peer.hostAddress.orEmpty())

    private companion object {
        const val PREFERRED_PORT = 49_494
        const val MAX_WORKERS = 4
        const val MAX_SUBSCRIPTIONS = 16
        const val MAX_SUBSCRIPTIONS_PER_HOST = 4
        const val MAX_QUEUED_EVENTS = 64
        const val OPEN_TIMEOUT_MS = 20_000L
        const val PROMPT_TIMEOUT_MS = 60_000L
    }
}

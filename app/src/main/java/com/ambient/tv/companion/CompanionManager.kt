package com.ambient.tv.companion

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.util.LocalNetworkAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

data class CompanionPrompt(val name: String, val code: String)
data class CompanionBackupOffer(val id: String, val sender: String, val mode: String, val credentials: Boolean, val accounts: Int, val sources: Int,
    /** Export time of the incoming data and this device's last successful import (0 = unknown). */
    val exportedAt: Long = 0, val localAppliedAt: Long = 0,
    /** Sent by a TV to this phone: display/audio hardware settings are never offered then. */
    val fromTelevision: Boolean = false)
/** TV side: a phone asked to pull this TV's data ("Vom TV holen"); needs local confirmation. */
data class CompanionPullRequest(val session: String, val pin: String, val name: String, val mode: String, val credentials: Boolean, val transferId: String)
data class CompanionState(
    val enabled: Boolean = false,
    val backgroundReceiver: Boolean = false,
    val connectionSettingsPanel: Boolean = false,
    val connectionStage: Int? = null,
    val connectionError: String = "",
    val devices: List<CompanionPeer> = emptyList(),
    /** The phone is looking for TVs right now (shown, so a tap on "Find TVs" never looks dead). */
    val searching: Boolean = false,
    /** The last search ended without finding any TV. */
    val searchEmpty: Boolean = false,
    val paired: List<CompanionPeer> = emptyList(),
    val connected: List<String> = emptyList(),
    val syncPeers: Map<String, List<String>> = emptyMap(),
    val target: CompanionPeer? = null,
    val online: Boolean = false,
    val invitation: String? = null,
    /** Short code shown next to the pairing QR, for typing on the phone. */
    val pairCode: String = "",
    /** TV side: a phone's code was accepted; waiting for it to confirm the comparison code. */
    val pairingWith: String = "",
    /** TV side: elapsedRealtime when the shown invitation expires (0 = none). */
    val pairExpiresAt: Long = 0L,
    /** Phone side: the guided pairing page is open. */
    val pairingFlow: Boolean = false,
    /** Phone side: name of the TV the guided pairing just paired with (its success step). */
    val pairingResult: String = "",
    /** Phone side: device the transfer page was opened for; fixes its target. */
    val transferTarget: String? = null,
    val prompt: CompanionPrompt? = null,
    val remote: CompanionFrame? = null,
    val panel: Boolean = false,
    val busy: Boolean = false,
    val sharedMedia: CompanionMedia? = null,
    val sendingTo: String? = null,
    val remotePanel: Boolean = false,
    val settingsPanel: Boolean = false,
    val controller: String = "",
    val backupOffer: CompanionBackupOffer? = null,
    val backupBusy: Boolean = false,
    val backupStatus: String = "",
    val settingsRevision: Int = 0,
    val settingsDirty: Boolean = false,
    val rollbackAvailable: Boolean = false,
    val restoreDialog: Boolean = false,
    val nowPlayingPanel: Boolean = false,
    val enqueueSelection: Boolean = false,
    /** Foreground-only, read-only snapshots of other paired TVs. No auto-pair or control lease. */
    val sessions: Map<String, CompanionFrame> = emptyMap(),
    /** Last time (elapsedRealtime) each device was found by the search or ended a session. */
    val lastSeen: Map<String, Long> = emptyMap(),
    /** TV side: pending pull request from a phone. */
    val pullRequest: CompanionPullRequest? = null,
    /** Phone side: waiting for a TV to send its data after "Vom TV holen". */
    val pulling: Boolean = false,
    /** Paired TVs whose stored host:port is being probed directly right now. */
    val probing: Set<String> = emptySet(),
)

/** Authenticated companion; optional TV receiver service owns idle availability. */
@Singleton
class CompanionManager @Inject constructor(@ApplicationContext private val context: Context,
    private val spotify: com.ambient.tv.spotify.SpotifyRepository?,
    private val mapper: CompanionMediaMapper?,
    private val connectionTransport: CompanionConnectionTransport? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackArtwork = CompanionPlaybackArtwork(context, scope)
    private val prefs = context.getSharedPreferences("companion_devices_v1", Context.MODE_PRIVATE)
    private var identityAlias: String? = null
    private val identity by lazy { CompanionIdentity(identityAlias ?: CompanionIdentity.DEFAULT_ALIAS) }
    private val mutable = MutableStateFlow(CompanionState(enabled = prefs.getBoolean("enabled", false), backgroundReceiver = prefs.getBoolean("background_receiver", false), paired = loadPeers()))
    val state = mutable.asStateFlow()
    internal val playbackRequest = MutableStateFlow<CompanionPlaybackRequest?>(null)
    private var playbackRequestJob: Job? = null
    @Volatile private var playbackStartAck: Pair<String, CompletableDeferred<CompanionFrame>>? = null
    private var receiverStartId = ""
    private var receiverStartMedia: CompanionMedia? = null
    private var receiverStartGeneration = -1L
    private var receiverSpotifyStartStatus = "accepted"

    @Volatile private var receiverServiceActive = false
    private val completedStarts = CompanionStartHistory()

    private var returnToDevicePanel = false
    fun showConnectionSettings() {
        returnToDevicePanel = mutable.value.panel
        mutable.update { it.copy(connectionSettingsPanel = true, panel = false) }
    }
    fun hideConnectionSettings() {
        mutable.update { it.copy(connectionSettingsPanel = false, panel = it.panel || returnToDevicePanel) }
        returnToDevicePanel = false
    }
    fun connectionSettings(peer: CompanionPeer): CompanionConnectionSettings =
        connectionTransport?.store?.get(peer.fingerprint) ?: CompanionConnectionSettings()
    suspend fun saveConnectionSettings(peer: CompanionPeer, settings: CompanionConnectionSettings) {
        require(mutable.value.paired.any { it.fingerprint == peer.fingerprint })
        requireNotNull(connectionTransport).save(peer.fingerprint, settings)
        stopSessionWatch(peer.fingerprint)
        if (mutable.value.target?.fingerprint == peer.fingerprint) disconnect(clearTarget = false)
    }
    suspend fun testWake(peer: CompanionPeer) {
        val transport = requireNotNull(connectionTransport)
        val settings = transport.store.get(peer.fingerprint)
        val route = if (settings.wireGuard) transport.vpnRoute(peer) else {
            val link = requireNotNull(lan())
            val address = InetAddress.getByName(peer.host)
            require(link.contains(address))
            CompanionRoute(link.network, address, settings.port.takeIf { it > 0 } ?: peer.port, false)
        }
        transport.wake(peer, route)
    }

    fun backgroundLaunchAllowed(): Boolean = Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(context)
    fun enableBackgroundReceiver(enabled: Boolean) {
        if (enabled) {
            check(television && foreground && backgroundLaunchAllowed() && LocalNetworkAccess.isGranted(context))
            enableReceiver(true)
        }
        prefs.edit().putBoolean("background_receiver", enabled).apply()
        mutable.update { it.copy(backgroundReceiver = enabled) }
        if (enabled) startReceiverService()
        else {
            receiverServiceActive = false
            context.stopService(Intent(context, CompanionReceiverService::class.java))
            if (!receiverActive()) stopReceiver()
        }
    }
    private fun startReceiverService() {
        if (identityAlias != null || !backgroundLaunchAllowed() || !LocalNetworkAccess.isGranted(context)) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, CompanionReceiverService::class.java)) }
            .onFailure { notice(R.string.companion_background_failed, error = true) }
    }
    internal fun receiverServiceStarted(): Boolean {
        television = com.ambient.tv.ui.mobile.isTelevision(context)
        if (!television || !mutable.value.enabled || !mutable.value.backgroundReceiver || !backgroundLaunchAllowed() || !LocalNetworkAccess.isGranted(context)) return false
        receiverServiceActive = true
        restoreQueue()
        maintainReceiver()
        return true
    }
    internal fun maintainReceiver(): Boolean {
        if (!receiverServiceActive || !mutable.value.enabled || !mutable.value.backgroundReceiver || !backgroundLaunchAllowed() || !LocalNetworkAccess.isGranted(context)) return false
        if (server != null && server?.inetAddress != lan()?.address) stopReceiver()
        startServer()
        return true
    }
    internal fun receiverServiceStopped() {
        receiverServiceActive = false
        if (!receiverActive()) stopReceiver()
    }

    /** Only previously authenticated private peers may cross subnet boundaries. Pairing stays local. */
    private fun acceptAddress(link: Lan, address: InetAddress): Boolean = address is Inet4Address &&
        companionAddressAllowed(link.contains(address), television, address.hostAddress.orEmpty())

    private fun receiverMac(link: Lan): String = runCatching {
        java.net.NetworkInterface.getByInetAddress(link.address)?.hardwareAddress
            ?.joinToString(":") { "%02X".format(it.toInt() and 255) }.orEmpty()
    }.getOrDefault("").takeIf(::validCompanionMac).orEmpty()

    /** The authenticated command stays in memory; intents contain no media or remote-controlled extras. */
    private suspend fun executeRemoteStart(request: CompanionFrame, pin: String, socket: Socket): Boolean {
        if (completedStarts.alreadyCompleted(pin, request)) return false
        if (!foreground) {
            if (!receiverServiceActive || !mutable.value.backgroundReceiver || !backgroundLaunchAllowed()) throw CompanionLaunchException("launch_permission")
            context.startActivity(Intent(context, com.ambient.tv.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        val ready = withTimeoutOrNull(20_000) {
            while (!foreground || execute == null) {
                check(!socket.isClosed && mutable.value.paired.any { it.fingerprint == pin })
                delay(50)
            }
            true
        } == true
        if (!ready) throw CompanionLaunchException("launch_timeout")
        check(!socket.isClosed && mutable.value.paired.any { it.fingerprint == pin } && !settingsDirty)
        check(mutable.value.backupOffer == null && !mutable.value.backupBusy && !mutable.value.restoreDialog && mutable.value.prompt == null)
        receiverStartId = request.startRequestId
        receiverStartMedia = request.media
        receiverStartGeneration = BackgroundPlaybackSession.snapshot.value.generation
        receiverSpotifyStartStatus = "accepted"
        requireNotNull(execute).invoke(request)
        completedStarts.complete(pin, request)
        return true
    }

    fun confirmSpotifyStart(requestId: String, success: Boolean) {
        if (requestId.isNotEmpty() && requestId == receiverStartId) {
            receiverSpotifyStartStatus = if (success) "playing" else "error"
        }
    }
    private val connectionNotices = ConcurrentHashMap<String, Long>()

    /** Connection ownership is independent of the output chosen for an individual start. */
    fun requestPlayback(media: CompanionMedia?, title: String = "", playLocally: () -> Unit) {
        if (television) { playLocally(); return }
        if (mutable.value.enqueueSelection) {
            if (media != null) startMedia(media, title) else notice(R.string.companion_play_failed, error = true)
            return
        }
        // Only ask for a target while a TV is actually connected; otherwise play here.
        if (!mutable.value.online || mutable.value.target == null) { playLocally(); return }
        if (playbackRequest.value?.starting == true) return
        if (mutable.value.remotePanel) hideRemote()
        mutable.update { it.copy(panel = false, nowPlayingPanel = false, settingsPanel = false) }
        playbackRequest.value = CompanionPlaybackRequest(media, title, playLocally)
    }

    fun cancelPlaybackRequest() {
        playbackRequestJob?.cancel(); playbackRequestJob = null
        playbackStartAck?.second?.cancel(); playbackStartAck = null
        playbackRequest.value = null
    }

    internal fun playRequestLocally() {
        val request = playbackRequest.value ?: return
        if (request.starting) return
        cancelPlaybackRequest()
        request.playLocally()
    }

    internal fun playRequestOn(peer: CompanionPeer) {
        val request = playbackRequest.value ?: return
        val media = request.media ?: return
        if (request.starting || mutable.value.paired.none { it.fingerprint == peer.fingerprint }) return
        if (!guardReachable(peer)) return
        playbackRequest.value = request.copy(starting = true, device = peer.name, error = null)
        playbackRequestJob = scope.launch(Dispatchers.Main) {
            try {
                withTimeout(100_000) {
                    if (!mutable.value.online || mutable.value.target?.fingerprint != peer.fingerprint) {
                        connect(mutable.value.devices.firstOrNull { it.fingerprint == peer.fingerprint } ?: peer)
                        state.first { it.target?.fingerprint == peer.fingerprint && it.online || !it.busy }
                        check(mutable.value.online && mutable.value.target?.fingerprint == peer.fingerprint)
                    }
                    val token = java.util.UUID.randomUUID().toString()
                    val ack = CompletableDeferred<CompanionFrame>()
                    playbackStartAck = token to ack
                    check(send(CompanionFrame(type = "start", media = media, title = request.title.take(160), startRequestId = token)))
                    val response = ack.await()
                    if (response.error == "source_unavailable") throw CompanionSourceUnavailableException()
                    if (response.error == "launch_permission" || response.error == "launch_timeout") throw CompanionLaunchException(response.error)
                    check(response.error.isEmpty())
                    // An accepted command is not yet proof that the TV actually started playing.
                    val confirmed = state.first { current ->
                        !current.online || current.target?.fingerprint != peer.fingerprint ||
                            current.remote?.let { frame -> companionStartConfirmed(media, frame, token) || frame.phase == "error" || frame.startRequestId == token && frame.startStatus == "error" } == true
                    }
                    check(confirmed.online && confirmed.target?.fingerprint == peer.fingerprint &&
                        confirmed.remote?.let { companionStartConfirmed(media, it, token) } == true)
                    BackgroundPlaybackSession.pause()
                    if (spotify?.playback?.value?.playing == true) spotify.pause()
                    playbackRequest.value = null
                    showNowPlaying()
                    notice(R.string.companion_playing_on, peer.name)
                }
            } catch (_: TimeoutCancellationException) {
                playbackRequest.value = request.copy(device = peer.name, error = R.string.companion_target_start_timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CompanionLaunchException) {
                playbackRequest.value = request.copy(device = peer.name, error = if (failure.reason == "launch_permission") R.string.companion_launch_permission else R.string.companion_launch_timeout)
            } catch (_: CompanionSourceUnavailableException) {
                playbackRequest.value = request.copy(device = peer.name, error = R.string.companion_target_source_missing)
            } catch (_: Exception) {
                playbackRequest.value = request.copy(device = peer.name, error = R.string.companion_start_failed)
            } finally {
                if (playbackRequestJob === currentCoroutineContext()[Job]) playbackStartAck = null
            }
        }
    }

    private fun connectionNotice(peer: CompanionPeer) {
        val now = SystemClock.elapsedRealtime()
        val previous = connectionNotices.put(peer.fingerprint, now)
        if (previous != null && now - previous < 60_000) return
        AppNotifications.post(AppNotice(key = "companion:connected:${peer.fingerprint}:$now",
            message = R.string.companion_connected, args = listOf(peer.name),
            kind = NoticeKind.CONNECTION, phase = NoticePhase.SUCCESS, interruptPlayback = true))
    }
    /** A new pairing replaces the generic "connected" notice for this peer. */
    private fun pairedNotice(peer: CompanionPeer) {
        val now = SystemClock.elapsedRealtime()
        connectionNotices[peer.fingerprint] = now
        AppNotifications.post(AppNotice(key = "companion:paired:${peer.fingerprint}:$now",
            message = R.string.companion_paired_success, args = listOf(peer.name),
            kind = NoticeKind.CONNECTION, phase = NoticePhase.SUCCESS, interruptPlayback = true))
    }
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var server: SSLServerSocket? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private val sessionWatchJobs = ConcurrentHashMap<String, Job>()
    private val sessionWatchSockets = ConcurrentHashMap<String, Socket>()
    private var directShareJob: Job? = null
    private class ReturnSession(val nonce: String) {
        val frames = Channel<Pair<CompanionFrame, CompletableDeferred<Boolean>>>(1)
        var pending: Pair<CompanionSyncFrame, CompletableDeferred<Boolean>>? = null
        var sequence = 0L
        fun close() { pending?.second?.complete(false); frames.close() }
    }
    private val returnSessions = java.util.concurrent.ConcurrentHashMap<String, ReturnSession>()
    private var backupSendJob: Job? = null
    @Volatile private var backupAck: CompletableDeferred<Boolean>? = null
    @Volatile private var backupAckError: String = ""
    private var outgoingBackupId: String? = null
    private val playbackQueue = CompanionPlaybackQueue()
    private val playbackMutation = Mutex()
    private val queueStore by lazy { CompanionQueueStore(java.io.File(context.noBackupFilesDir, "companion_queue_v1.json")) }
    @Volatile private var queueLoaded = false
    private var queueRestoreJob: Job? = null
    private var lastEndedGeneration = -1L
    private var replacementTimeout: Job? = null
    private var replacementTimeoutGeneration = -1L
    private var lastSpotifyQueueRefresh = 0L
    @Volatile internal var remoteServiceActive = false
    @Volatile private var remoteServiceRequested = false
    private var remoteServiceFailed = false
    private val inputLease = CompanionInputLease()
    private var leaseTimer: Job? = null
    private var offerExpiry: Job? = null
    private var offerSession: String? = null
    private var offerRaw: String? = null
    private val backupResults = mutableMapOf<String, Pair<String, String>>()
    private var settingsService: CompanionSettingsService? = null
    private var settingsCanApply: () -> Boolean = { false }
    private var clientSocket: Socket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: android.net.wifi.WifiManager.MulticastLock? = null
    private val sockets = ConcurrentHashMap<Socket, String>()
    private val admission = CompanionAdmission()
    private var commands = Channel<CompanionFrame>(16)
    @Volatile private var foreground = false
    @Volatile private var television = com.ambient.tv.ui.mobile.isTelevision(context)
    private val deviceRole get() = if (television) COMPANION_TV else COMPANION_HANDHELD
    @Volatile private var pairToken = ""
    @Volatile private var pairCode = ""
    private var pairAttempts = 0
    @Volatile private var pairDeadline = 0L
    private var pairExpiry: Job? = null
    private var pairOpening: Job? = null
    @Volatile private var confirmation: CompletableDeferred<Boolean>? = null
    private var lastRejected = 0L
    private val handshakeLimiter = CompanionHandshakeLimiter()
    private data class PendingConnection(val peer: CompanionPeer, val invitation: String, val initialMedia: CompanionMedia?)
    private var pendingConnection: PendingConnection? = null
    private var pendingPairingOpen = false
    private var execute: (suspend (CompanionFrame) -> Unit)? = null
    var settingsDirty: Boolean
        get() = mutable.value.settingsDirty
        set(value) { mutable.update { it.copy(settingsDirty = value) } }
    var saveSettingsBeforeTransfer: ((() -> Unit) -> Boolean)? = null
    @Volatile private var remoteAfterConnect: String? = null
    @Volatile private var transferAfterPairing = false

    fun showRemoteOrDevices() {
        if (mutable.value.online) showRemote() else showPanel()
    }

    fun connectRemote(peer: CompanionPeer) {
        if (mutable.value.online && mutable.value.target?.fingerprint == peer.fingerprint) { showRemote(); return }
        connect(peer, openRemote = true)
    }

    fun pairForTransfer() {
        if (television) { showPanel(); transferAfterPairing = true; openPairing(); return }
        startPairingFlow()
        transferAfterPairing = true
    }

    fun showPairing() {
        showPanel()
        if (television) openPairing()
    }

    /** Phone: the guided pairing page (prepare, scan or type the code, compare, done). TV: its QR. */
    fun startPairingFlow() {
        if (television) { showPairing(); return }
        if (mutable.value.remotePanel) hideRemote()
        mutable.update { it.copy(pairingFlow = true, pairingResult = "", panel = false, settingsPanel = false, nowPlayingPanel = false) }
        discover()
    }
    /** Phone: leave whichever companion page covers the content (another tab was chosen). */
    fun closeHandheldPages() {
        if (mutable.value.pairingFlow) closePairingFlow()
        if (mutable.value.remotePanel) hideRemote()
        if (mutable.value.panel) hidePanel()
    }
    fun closePairingFlow() {
        transferAfterPairing = false
        mutable.update { it.copy(pairingFlow = false, pairingResult = "") }
    }
    @Volatile internal var diagnosticFailure: String = ""
        private set

    /** Test-only identity injection keeps both sides of the device test cryptographically distinct. */
    internal constructor(context: Context, auditIdentityAlias: String, auditMapper: CompanionMediaMapper? = null) : this(context, null, auditMapper) {
        identityAlias = auditIdentityAlias
    }
    private val deviceName get() = safeDeviceName(runCatching {
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL)

    private fun loadPeers(): List<CompanionPeer> = runCatching {
        companionJson.decodeFromString<List<CompanionPeer>>(prefs.getString("peers", "[]")!!)
    }.getOrDefault(emptyList()).take(20)

    @Synchronized private fun savePeer(peer: CompanionPeer) {
        val peers = (mutable.value.paired.filterNot { it.fingerprint == peer.fingerprint } + peer).takeLast(20)
        check(prefs.edit().putString("peers", companionJson.encodeToString(peers)).commit())
        mutable.update { it.copy(paired = peers) }
        if (!television) { CompanionShareTargets.publish(context, peers); if (foreground) startServer() }
    }

    init {
        scope.launch { state.collect { refreshSessionWatches() } }
        spotify?.let { repo -> scope.launch(Dispatchers.Main) { repo.playback.collect { if (television && !foreground && !receiverActive()) { stopReceiver(); playbackArtwork.clear() } } } }
        scope.launch(Dispatchers.Main) {
            BackgroundPlaybackSession.snapshot.collect { snapshot ->
                updateReplacementTimeout(snapshot)
                if (television && !foreground && !receiverActive()) { stopReceiver(); playbackArtwork.clear() }
                maybeAdvanceQueue(snapshot)
            }
        }
    }

    private fun updateReplacementTimeout(snapshot: BackgroundPlaybackSession.Snapshot) {
        if (!television || !snapshot.replacingSource || snapshot.status.error != null) {
            replacementTimeout?.cancel(); replacementTimeout = null; replacementTimeoutGeneration = -1L
            return
        }
        val token = snapshot.generation
        if (replacementTimeoutGeneration == token) return
        replacementTimeoutGeneration = token
        replacementTimeout?.cancel()
        replacementTimeout = scope.launch(Dispatchers.Main) {
            delay(60_000)
            if (BackgroundPlaybackSession.isSourceReplacement(token)) BackgroundPlaybackSession.failSourceReplacement(token, context.getString(R.string.companion_start_timeout))
        }
    }

    /** Restore before opening the receiver so its first status already contains the saved queue. */
    @Synchronized private fun restoreQueue() {
        if (queueLoaded || queueRestoreJob?.isActive == true) return
        queueRestoreJob = scope.launch(Dispatchers.Main) {
            val result = withContext(Dispatchers.IO) { runCatching { if (identityAlias == null) queueStore.load() else null } }
            result.getOrNull()?.let(playbackQueue::restoreCheckpoint)
            if (result.isFailure) notice(R.string.companion_queue_restore_failed, error = true)
            queueLoaded = true
            if (television) CompanionQueuePlayback.pending = playbackQueue.hasNext
            if (receiverActive() && television && mutable.value.enabled) startServer()
            // Restoring a pending queue alone must never launch playback after a cold start.
        }
    }

    private suspend fun persistQueue() {
        if (identityAlias != null) return
        val checkpoint = playbackQueue.checkpoint()
        val result = withContext(NonCancellable + Dispatchers.IO) { runCatching { queueStore.save(checkpoint) } }
        if (result.isFailure) notice(R.string.companion_queue_save_failed, error = true)
    }

    private suspend fun maybeAdvanceQueue(snapshot: BackgroundPlaybackSession.Snapshot) {
        if (!television || !foreground || !queueLoaded || !snapshot.status.ended || !playbackQueue.hasNext) return
        playbackMutation.withLock {
            val latest = BackgroundPlaybackSession.snapshot.value
            if (!television || !foreground || !queueLoaded || !latest.status.ended || latest.generation != snapshot.generation || snapshot.generation == lastEndedGeneration || !playbackQueue.hasNext || BackgroundPlaybackSession.sleepTimer.kind == com.ambient.tv.ui.player.SleepTimerKind.END_OF_ITEM) return@withLock
            // Both onStart and status collection can observe EOF; claim it before any suspension.
            lastEndedGeneration = snapshot.generation
            runCatching { advanceQueue(snapshot.metadata?.companionMedia) }.onFailure { notice(R.string.companion_play_failed, error = true) }
        }
    }
    @Synchronized private fun refreshSessionWatches() {
        val peers = if (foreground && !television && mutable.value.panel && LocalNetworkAccess.isGranted(context))
            mutable.value.devices.filter { p -> connectionTransport?.store?.get(p.fingerprint)?.wireGuard != true && p.fingerprint != mutable.value.target?.fingerprint && mutable.value.paired.any { it.fingerprint == p.fingerprint } }.take(20)
            else emptyList()
        sessionWatchJobs.keys.filter { pin -> peers.none { it.fingerprint == pin } }.forEach { stopSessionWatch(it) }
        peers.forEach { peer ->
            synchronized(sessionWatchJobs) {
                if (sessionWatchJobs.containsKey(peer.fingerprint)) return@forEach
                val job = scope.launch(start = CoroutineStart.LAZY) { watchSession(peer) }
                sessionWatchJobs[peer.fingerprint] = job; job.start()
            }
        }
    }
    private fun stopSessionWatch(pin: String) {
        sessionWatchJobs.remove(pin)?.cancel()
        sessionWatchSockets.remove(pin)?.let { runCatching { it.close() } }
        mutable.update { it.copy(sessions = it.sessions - pin) }
    }
    private suspend fun watchSession(peer: CompanionPeer) {
        try {
            while (currentCoroutineContext().isActive && foreground && mutable.value.panel && mutable.value.target?.fingerprint != peer.fingerprint) {
                var owned: Socket? = null
                try {
                    val link = requireNotNull(lan()); require(isPrivateV4(peer.host))
                    val address = InetAddress.getByName(peer.host); require(link.contains(address))
                    val raw = Socket(); owned = raw; sessionWatchSockets[peer.fingerprint] = raw
                    link.network.bindSocket(raw); raw.connect(InetSocketAddress(address, peer.port), 3_000)
                    val socket = identity.clientContext(peer.fingerprint).socketFactory.createSocket(raw, peer.host, peer.port, true) as SSLSocket
                    owned = socket; sessionWatchSockets[peer.fingerprint] = socket
                    socket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2"); socket.soTimeout = 5_000; socket.startHandshake()
                    val input = DataInputStream(socket.inputStream); val output = DataOutputStream(socket.outputStream)
                    val challenge = input.readFrame()
                    require(challenge.type == "challenge" && challenge.nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
                    if (challenge.role != COMPANION_TV) {
                        notice(R.string.companion_role_required, error = true)
                        error("device_role")
                    }
                    val nonce = challenge.nonce; val bytes = identity.certificate.publicKey.encoded
                    output.writeFrame(CompanionFrame(type = "auth", role = deviceRole, mode = "observe", nonce = nonce, name = deviceName,
                        key = Base64.getEncoder().encodeToString(bytes),
                        signature = Base64.getEncoder().encodeToString(identity.sign(authTranscript(nonce, peer.fingerprint, fingerprint(bytes), deviceRole, challenge.role)))))
                    val ready = input.readFrame(); require(ready.type == "ready" && ready.nonce == nonce)
                    var sequence = 0L
                    while (currentCoroutineContext().isActive && foreground && mutable.value.panel && mutable.value.target?.fingerprint != peer.fingerprint) {
                        require(mutable.value.paired.any { it.fingerprint == peer.fingerprint } && sessionLan()?.address == link.address)
                        output.writeFrame(CompanionFrame(type = "status", nonce = nonce, sequence = ++sequence))
                        val frame = input.readFrame(); require(frame.type == "state" && frame.nonce == nonce && frame.sequence == sequence)
                        mutable.update { it.copy(sessions = it.sessions + (peer.fingerprint to frame.copy(receiverId = peer.fingerprint))) }
                        delay(2_000)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutable.update { it.copy(sessions = it.sessions - peer.fingerprint) } }
                finally { runCatching { owned?.close() }; owned?.let { sessionWatchSockets.remove(peer.fingerprint, it) } }
                delay(5_000)
            }
        } finally {
            val job = currentCoroutineContext()[Job]
            if (job != null && sessionWatchJobs.remove(peer.fingerprint, job)) mutable.update { it.copy(sessions = it.sessions - peer.fingerprint) }
        }
    }
    fun attach(handler: suspend (CompanionFrame) -> Unit) { execute = handler }
    fun detach() { execute = null; settingsCanApply = { false } }
    fun attachSettings(service: CompanionSettingsService, canApply: () -> Boolean) {
        settingsService = service; settingsCanApply = canApply
        mutable.update { it.copy(rollbackAvailable = service.hasRollback()) }
    }

    fun foreground(visible: Boolean, isTv: Boolean) {
        foreground = visible
        // Only the isolated protocol test may simulate a different physical device.
        television = if (identityAlias != null) isTv else com.ambient.tv.ui.mobile.isTelevision(context)
        if (television) { stopDiscovery(); mutable.update { it.copy(devices = emptyList()) } }
        if (visible) {
            if (television && mutable.value.backgroundReceiver && mutable.value.enabled) startReceiverService()
            if (television) { updateReplacementTimeout(BackgroundPlaybackSession.snapshot.value); restoreQueue() }
            if (!television) CompanionShareTargets.publish(context, mutable.value.paired)
            if (television && mutable.value.enabled || !television && mutable.value.paired.isNotEmpty()) startServer()
            if (television) scope.launch(Dispatchers.Main) { maybeAdvanceQueue(BackgroundPlaybackSession.snapshot.value) }
            if (mutable.value.panel || pageSearching) discover()
            // The phone connects to a TV only when the user asks for it; resuming the app must not
            // dial the last TV (off-network that only produced "connection failed" notices).
        } else {
            releaseRemoteControl()
            rejectBackup("expired")
            closePairing(); stopDiscovery()
            if (!receiverActive()) { stopReceiver(); playbackArtwork.clear() }
            if (television || !remoteServiceRequested || !mutable.value.online) disconnect(clearTarget = false)
            else {
                if (mutable.value.remote?.inputGranted == true) send(CompanionFrame(type = "input_release"))
                mutable.update { it.copy(panel = false, remotePanel = false, nowPlayingPanel = false, settingsPanel = false) }
            }
        }
    }

    private fun receiverActive(): Boolean = if (television) foreground || receiverServiceActive || BackgroundPlaybackSession.snapshot.value.active || spotify?.shouldKeepReceiverInBackground == true else foreground && mutable.value.paired.isNotEmpty()
    private fun ensureRemoteService() {
        if (television || !foreground || remoteServiceRequested || remoteServiceFailed || identityAlias != null || mutable.value.currentPlayback() == null) return
        remoteServiceRequested = true
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, CompanionRemotePlaybackService::class.java)) }
            .onFailure { remoteServiceRequested = false; remoteServiceFailed = true; notice(R.string.companion_background_failed, error = true) }
    }
    internal fun remoteServiceStopped() {
        remoteServiceActive = false; remoteServiceRequested = false
        if (!foreground && !television) disconnect(clearTarget = false)
    }

    @Synchronized private fun stopReceiver() {
        pairOpening?.cancel(); pairOpening = null
        serverJob?.cancel(); serverJob = null
        runCatching { server?.close() }; server = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        sockets.keys.forEach { runCatching { it.close() } }
    }

    fun showPanel() {
        if (mutable.value.remotePanel) hideRemote()
        mutable.update { it.copy(panel = true, nowPlayingPanel = false, settingsPanel = false, pairingFlow = false, pairingResult = "") }
        discover()
    }
    fun showRemote() {
        if (television) return
        if (!mutable.value.online) { notice(R.string.companion_unreachable_notice, mutable.value.target?.name.orEmpty(), error = true); return }
        mutable.update { it.copy(remotePanel = true, panel = false, nowPlayingPanel = false, settingsPanel = false) }; send(CompanionFrame(type = "input_claim")) }
    fun hideRemote() { send(CompanionFrame(type = "input_release")); mutable.update { it.copy(remotePanel = false) } }
    fun showNowPlaying() {
        if (mutable.value.remotePanel) hideRemote()
        mutable.update { it.copy(nowPlayingPanel = true, panel = false, remotePanel = false, settingsPanel = false) }
    }
    fun hideNowPlaying() { mutable.update { it.copy(nowPlayingPanel = false) } }
    fun playbackCommand(type: String, observed: CompanionFrame? = mutable.value.currentPlayback(), position: Long = 0,
        optionGroup: String = "", optionKey: String = ""): Boolean {
        val current = mutable.value.currentPlayback() ?: return false
        val expected = observed ?: return false
        if (expected.media != null && !samePlaybackMedia(expected.media, current.media)) return false
        if (expected.playbackSessionId != current.playbackSessionId) return false
        if (expected.receiverId.isNotEmpty() && expected.receiverId != mutable.value.target?.fingerprint) return false
        if (type == "seek" && !expected.seekable) return false
        if (type !in setOf("play", "pause", "stop", "seek", "next", "previous", "select", "volume", "shuffle", "repeat", "retry")) return false
        if (expected.capabilities.isNotEmpty() && type !in expected.capabilities) return false
        if (expected.capabilities.isEmpty() && type !in setOf("play", "pause", "stop", "seek")) return false
        return send(CompanionFrame(type = type, media = expected.media, playbackSessionId = expected.playbackSessionId,
            position = if (type == "seek") position.coerceIn(0, expected.duration.coerceAtLeast(0)) else position,
            optionGroup = optionGroup, optionKey = optionKey))
    }
    fun armQueueSelection() {
        val current = mutable.value
        if (!current.online || current.remote?.let { "queue_add" in it.capabilities && (it.receiverId.isEmpty() || it.receiverId == current.target?.fingerprint) } != true) return
        mutable.update { it.copy(enqueueSelection = true, nowPlayingPanel = false, panel = false) }
    }
    fun cancelQueueSelection() { mutable.update { it.copy(enqueueSelection = false) } }
    fun startMedia(media: CompanionMedia, title: String = ""): Boolean {
        if (mutable.value.enqueueSelection && media.kind == "live:channel") { notice(R.string.companion_play_failed, error = true); return false }
        val enqueue = mutable.value.enqueueSelection
        if (enqueue && (mutable.value.remote?.capabilities?.contains("queue_add") != true ||
            mutable.value.currentPlayback()?.media?.kind?.startsWith("spotify:") == true && media.kind != "spotify:track")) {
            notice(R.string.companion_play_failed, error = true); return false
        }
        val result = send(CompanionFrame(type = if (enqueue) "queue_add" else "start", media = media, title = title.take(160)))
        if (result) cancelQueueSelection()
        return result
    }
    fun queueCommand(type: String, token: String = "", observed: CompanionFrame? = mutable.value.remote): Boolean {
        val frame = observed ?: return false
        if (frame.receiverId.isNotEmpty() && frame.receiverId != mutable.value.target?.fingerprint) return false
        if (!mutable.value.online || type !in frame.capabilities || type !in setOf("queue_remove", "queue_move", "queue_clear", "queue_play", "queue_page")) return false
        return send(CompanionFrame(type = type, payload = token, queueRevision = frame.queueRevision,
            position = if (type == "queue_page") token.toLongOrNull() ?: 0 else 0))
    }

    private fun playbackState(requestedQueueOffset: Int = 0): CompanionFrame {
        if (!television) return CompanionFrame(type = "state")
        val snapshot = BackgroundPlaybackSession.snapshot.value
        val music = spotify?.playback?.value ?: com.ambient.tv.spotify.SpotifyPlaybackState()
        val useSpotify = useSpotifyPlayback(snapshot, music)
        var frame = if (useSpotify) spotifyPlaybackFrame(music) else localPlaybackFrame(snapshot)
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val adjustable = !audio.isVolumeFixed && audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) > 0
        val source = CompanionPlaybackControls.current()
        val options = if (useSpotify) emptyList() else CompanionPlaybackControls.options().filter { foreground || it.kind == "subtitle" || it.kind == "audio" && source.audio == null }
        val nativeQueue = if (useSpotify) music.queue.take(64).mapIndexed { i, t -> CompanionQueueItem("spotify:$i:${t.id}", CompanionMedia("spotify:track", t.id), t.name.take(160)) } else playbackQueue.entries
        val capabilities = buildList {
            add("start_confirmation")
            if (foreground && settingsService != null) addAll(listOf("nav_sounds", "settings_sync", "settings_only"))
            add("queue_page")
            if (useSpotify && spotify?.webAuthorized == true || !useSpotify && playbackQueue.entries.size < 64) add("queue_add")
            if (!useSpotify) {
                addAll(listOf("queue_remove", "queue_move", "queue_clear"))
                if (foreground) add("queue_play")
            }
            if (frame.playbackActive) {
                add("stop")
                if (snapshot.replacingSource && snapshot.status.error != null && !useSpotify) {
                    if (foreground && frame.media != null) add("retry")
                } else { addAll(listOf("play", "pause")); if (frame.seekable) add("seek") }
                if (useSpotify || foreground && (source.next != null || playbackQueue.hasNext || (snapshot.metadata?.companionNext ?: snapshot.metadata?.nextUp) != null)) add("next")
                if (useSpotify || foreground && (source.previous != null || playbackQueue.hasPrevious) || frame.seekable && source.previous == null && (source.seek != null || snapshot.metadata?.timelineOffsetMs == 0L)) add("previous")
                if (useSpotify && spotify?.webAuthorized == true && music.supportsVolume == true || !useSpotify && adjustable) add("volume")
                if (useSpotify && spotify?.webAuthorized == true) addAll(listOf("shuffle", "repeat"))
                else if (options.isNotEmpty()) add("select")
            }
        }
        val queuePage = requestedQueueOffset.coerceIn(0, (nativeQueue.size - 1).coerceAtLeast(0))
        val startMedia = receiverStartMedia
        val startStatus = when {
            receiverStartId.isEmpty() -> ""
            startMedia?.kind?.startsWith("spotify:") == true -> receiverSpotifyStartStatus
            snapshot.generation != receiverStartGeneration && samePlaybackMedia(startMedia, frame.media) && frame.phase == "playing" -> "playing"
            snapshot.generation != receiverStartGeneration && frame.phase == "error" -> "error"
            else -> "accepted"
        }
        frame = frame.copy(capabilities = capabilities, options = options,
            startRequestId = receiverStartId, startStatus = startStatus,
            volume = if (useSpotify) music.volume.takeIf { music.supportsVolume == true } else if (adjustable) audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) else null,
            volumeMax = if (useSpotify) if (music.supportsVolume == true) 100 else 0 else if (adjustable) audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) else 0,
            queue = nativeQueue.drop(queuePage).take(8).map { it.copy(media = null, kind = it.media?.kind.orEmpty()) }, queueTotal = nativeQueue.size, queueOffset = queuePage,
            queueRevision = if (useSpotify) 0 else playbackQueue.revision,
            shuffle = music.shuffle.takeIf { useSpotify }, repeatMode = music.repeatMode.takeIf { useSpotify })
        if (useSpotify && SystemClock.elapsedRealtime() - lastSpotifyQueueRefresh > 5_000) {
            lastSpotifyQueueRefresh = SystemClock.elapsedRealtime(); spotify?.refreshQueue()
        }
        val metadata = snapshot.metadata
        return boundedPlaybackFrame(frame.copy(artwork = if (frame.playbackActive) playbackArtwork.update(frame.media,
            if (useSpotify) music.current?.album?.images?.firstOrNull()?.url else metadata?.artworkUrl,
            if (useSpotify) emptyMap() else metadata?.artworkHeaders.orEmpty()) else { playbackArtwork.clear(); "" }))
    }

    private suspend fun advanceQueue(current: CompanionMedia?, token: String? = null) {
        val next = requireNotNull(if (token == null) playbackQueue.entries.firstOrNull() else playbackQueue.entries.firstOrNull { it.token == token })
        require(mapper?.serverSwitchFor(requireNotNull(next.media)) == null) { "Activate this server on the TV before queue playback" }
        val originalIndex = playbackQueue.entries.indexOfFirst { it.token == next.token }
        val item = requireNotNull(playbackQueue.takeNext(token))
        CompanionQueuePlayback.pending = playbackQueue.hasNext
        var dispatched = false
        try {
            // Durable dequeue precedes navigation: restarting must not replay a consumed row.
            persistQueue()
            requireNotNull(execute).invoke(CompanionFrame(type = "start", media = requireNotNull(item.media)))
            dispatched = true
            current?.let(playbackQueue::remember)
            persistQueue()
        } catch (e: Exception) {
            if (!dispatched) {
                playbackQueue.restore(item, originalIndex); CompanionQueuePlayback.pending = true; persistQueue()
            }
            throw e
        }
    }
    private suspend fun applyQueueCommand(command: CompanionFrame) {
        require(command.type in playbackState().capabilities)
        when (command.type) {
            "queue_add" -> {
                val media = requireNotNull(command.media)
                require(media.kind != "live:channel")
                if (media.kind.startsWith("spotify:")) {
                    require(media.kind == "spotify:track" && validSpotifyMedia(media))
                    val repository = requireNotNull(spotify)
                    require(useSpotifyPlayback(BackgroundPlaybackSession.snapshot.value, repository.playback.value) && repository.webAuthorized)
                    try { withTimeout(6_000) { repository.addToQueue("spotify:track:${media.id}") } }
                    catch (_: TimeoutCancellationException) { error("Spotify queue request timed out") }
                } else {
                    require(!useSpotifyPlayback(BackgroundPlaybackSession.snapshot.value, spotify?.playback?.value ?: com.ambient.tv.spotify.SpotifyPlaybackState()))
                    requireNotNull(mapper).let { require(it.serverSwitchFor(media) == null); it.toRoute(media) }
                    playbackQueue.add(CompanionQueueItem(randomToken().take(16), media, command.title.take(160)))
                    CompanionQueuePlayback.pending = true
                }
            }
            "queue_remove" -> playbackQueue.remove(command.payload, command.queueRevision)
            "queue_move" -> { val parts = command.payload.split('|'); require(parts.size == 2); playbackQueue.move(parts[0], parts[1].toInt(), command.queueRevision) }
            "queue_clear" -> playbackQueue.clear(command.queueRevision)
            "queue_play" -> { require(command.queueRevision == playbackQueue.revision); advanceQueue(playbackState().media, command.payload) }
        }
        CompanionQueuePlayback.pending = playbackQueue.hasNext
        persistQueue()
    }
    private suspend fun applyPlaybackCommand(command: CompanionFrame) {
        val current = playbackState()
        require(matchesPlaybackCommand(command, current))
        require(command.type in current.capabilities)
        val snapshot = BackgroundPlaybackSession.snapshot.value
        val music = useSpotifyPlayback(snapshot, spotify?.playback?.value ?: com.ambient.tv.spotify.SpotifyPlaybackState())
        when (command.type) {
            "retry" -> requireNotNull(execute).invoke(CompanionFrame(type = "start", media = requireNotNull(current.media)))
            "play" -> if (music) spotify?.play() else BackgroundPlaybackSession.userPlay()
            "pause" -> if (music) spotify?.pause() else BackgroundPlaybackSession.userPause()
            "stop" -> { if (music) spotify?.stop() else BackgroundPlaybackSession.stop(); playbackQueue.clear(); CompanionQueuePlayback.pending = false; persistQueue() }
            "seek" -> {
                require(current.seekable && command.position in 0..current.duration)
                if (music) spotify?.seek(command.position)
                else {
                    BackgroundPlaybackSession.userSeek()
                    val sourceSeek = CompanionPlaybackControls.current().seek
                    val offset = snapshot.metadata?.timelineOffsetMs ?: 0
                    if (sourceSeek != null) sourceSeek(command.position)
                    else { require(command.position >= offset); BackgroundPlaybackSession.seekTo(command.position - offset) }
                }
            }
            "next" -> when {
                music -> spotify?.next()
                playbackQueue.hasNext -> advanceQueue(current.media)
                CompanionPlaybackControls.current().next != null -> CompanionPlaybackControls.current().next?.invoke()
                else -> {
                    val next = requireNotNull(snapshot.metadata?.companionNext ?: snapshot.metadata?.nextUp)
                    val media = requireNotNull(mapper?.nextUpMedia(next, snapshot.metadata?.companionMedia))
                    requireNotNull(execute).invoke(CompanionFrame(type = "start", media = media))
                    current.media?.let(playbackQueue::remember)
                    persistQueue()
                }
            }
            "previous" -> when {
                music -> spotify?.previous()
                CompanionPlaybackControls.current().previous != null -> CompanionPlaybackControls.current().previous?.invoke()
                playbackQueue.hasPrevious -> {
                    val previous = requireNotNull(playbackQueue.previousMedia)
                    require(mapper?.serverSwitchFor(previous) == null)
                    requireNotNull(execute).invoke(CompanionFrame(type = "start", media = previous))
                    playbackQueue.previous()
                    persistQueue()
                }
                else -> {
                    require(current.seekable)
                    BackgroundPlaybackSession.userSeek()
                    val sourceSeek = CompanionPlaybackControls.current().seek
                    if (sourceSeek != null) sourceSeek(0) else { require(snapshot.metadata?.timelineOffsetMs == 0L); BackgroundPlaybackSession.seekTo(0) }
                }
            }
            "select" -> {
                require(!music && current.options.any { it.kind == command.optionGroup && it.options.any { option -> option.key == command.optionKey } })
                CompanionPlaybackControls.select(command.optionGroup, command.optionKey)
            }
            "volume" -> {
                require(command.position in 0..current.volumeMax.toLong())
                if (music) spotify?.setVolume(command.position.toInt()) else {
                    val audio = context.getSystemService(android.media.AudioManager::class.java)
                    require(!audio.isVolumeFixed); audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, command.position.toInt(), 0)
                }
            }
            "shuffle" -> { require(music && command.position in 0..1); spotify?.setShuffle(command.position == 1L) }
            "repeat" -> { require(music && command.position in 0..2); spotify?.setRepeatMode(command.position.toInt()) }
        }
    }
    fun showSettingsTransfer() = showSettingsTransferTo(null)
    /** Opens the transfer page for [peer]; null lets the page pick the connected or only TV. */
    fun showSettingsTransferTo(peer: CompanionPeer?) {
        if (television) { showPanel(); return }
        if (mutable.value.remotePanel) hideRemote()
        mutable.update { it.copy(settingsPanel = true, transferTarget = peer?.fingerprint, panel = false, nowPlayingPanel = false, pairingFlow = false, pairingResult = "") }
    }
    fun hideSettingsTransfer() { mutable.update { it.copy(settingsPanel = false, transferTarget = null) } }

    /** Legacy callers cannot request a TV-to-phone transfer, regardless of content. */
    @Suppress("UNUSED_PARAMETER")
    fun requestBackupFromTv(mode: String, credentials: Boolean): Boolean {
        notice(companionBackupFailureMessage(BackupFailure.UNSUPPORTED), error = true)
        return false
    }

    fun remoteInput(input: CompanionInput) {
        if (mutable.value.remote?.inputGranted != true) return
        input.validate()
        val frame = CompanionFrame(type = "input", input = input)
        if (input.action in setOf("move", "scroll")) commands.trySend(frame) else send(frame)
    }
    fun releaseRemoteControl(session: String? = null) {
        synchronized(inputLease) {
            val previous = inputLease.name
            if (inputLease.release(session)) {
                leaseTimer?.cancel(); leaseTimer = null
                mutable.update { it.copy(controller = "") }
                scope.launch(Dispatchers.Main) { CompanionRemoteInput.hide() }
                notice(R.string.companion_control_stopped, previous)
            }
        }
    }
    private fun claimControl(session: String, name: String) {
        synchronized(inputLease) {
            val previous = inputLease.owner
            check(inputLease.claim(session, name, SystemClock.elapsedRealtime()))
            mutable.update { it.copy(controller = name) }
            if (previous != session) notice(R.string.companion_control_started, name)
            leaseTimer?.cancel()
            leaseTimer = scope.launch { delay(60_000); releaseRemoteControl(session) }
        }
    }

    fun sendSettings(raw: String, mode: String, credentials: Boolean, target: CompanionPeer? = mutable.value.target): Boolean {
        val reverse = target?.let { returnSessions[it.fingerprint] }
        val direct = mutable.value.online && target?.fingerprint == mutable.value.target?.fingerprint
        val s = mutable.value
        val gate = when {
            !companionReceiveModeAllowed(!television, mode) -> BackupFailure.UNSUPPORTED
            !direct && reverse == null -> BackupFailure.NOT_CONNECTED
            s.backupBusy || s.backupOffer != null || s.restoreDialog || s.prompt != null || s.pullRequest != null -> BackupFailure.BUSY
            settingsDirty && mode != "nav_sounds" -> BackupFailure.UNSAVED
            mode == "nav_sounds" && (credentials || (if (reverse != null) s.syncPeers[target.fingerprint] else s.remote?.capabilities)?.contains("nav_sounds") != true) -> BackupFailure.UNSUPPORTED
            mode == "preferences" && (credentials || (if (reverse != null) s.syncPeers[target.fingerprint] else s.remote?.capabilities)?.contains("settings_only") != true) -> BackupFailure.UNSUPPORTED
            raw.toByteArray().size !in 1..CompanionBackupPolicy.MAX_BYTES -> BackupFailure.TOO_LARGE
            else -> null
        }
        // A stale "pending" from an earlier transfer whose answer never arrived must not block forever.
        if (gate != null) { notice(companionBackupFailureMessage(gate), target?.name.orEmpty(), error = true); return false }
        val bytes = raw.toByteArray()
        val id = java.util.UUID.randomUUID().toString()
        val queue = commands
        outgoingBackupId = id
        mutable.update { it.copy(backupBusy = true, backupStatus = "sending") }
        backupSendJob = scope.launch {
            suspend fun transmit(frame: CompanionFrame) {
                ensureActive()
                val ack = CompletableDeferred<Boolean>()
                if (reverse != null) {
                    check(returnSessions[target.fingerprint] === reverse)
                    reverse.frames.send(frame to ack)
                } else {
                    check(commands === queue && mutable.value.online)
                    backupAck = ack
                    queue.send(frame)
                }
                if (!withTimeout(15_000) { ack.await() }) throw CompanionBackupRejected(backupAckError)
            }
            try {
                if (reverse == null) queue.send(CompanionFrame(type = "input_release"))
                transmit(CompanionFrame(type = "backup_begin", transferId = id, total = bytes.size, digest = fingerprint(bytes), mode = mode, credentials = credentials))
                var index = 0
                for (offset in bytes.indices step 4096) {
                    transmit(CompanionFrame(type = "backup_chunk", transferId = id, index = index++, payload = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, minOf(offset + 4096, bytes.size)))))
                }
                transmit(CompanionFrame(type = "backup_finish", transferId = id))
                notice(R.string.companion_backup_sent, target?.name.orEmpty())
            } catch (failure: Exception) {
                // withTimeout throws a CancellationException subtype: a silent timeout used to leave
                // the sender without any message. Real cancellation (job cancelled) still propagates.
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                val reason = when (failure) {
                    is TimeoutCancellationException -> BackupFailure.TIMEOUT
                    is CompanionBackupRejected -> if (failure.reason == BackupFailure.RECEIVER_BUSY) BackupFailure.RECEIVER_BUSY else BackupFailure.UNSUPPORTED
                    else -> BackupFailure.NOT_CONNECTED
                }
                if (commands === queue) {
                    if (reverse != null) reverse.frames.trySend(CompanionFrame(type = "backup_cancel", transferId = id) to CompletableDeferred())
                    else queue.trySend(CompanionFrame(type = "backup_cancel", transferId = id))
                    mutable.update { it.copy(backupStatus = "failed") }
                    notice(companionBackupFailureMessage(reason), target?.name.orEmpty(), error = true)
                }
            } finally {
                if (commands === queue && outgoingBackupId == id) { backupAck = null; mutable.update { it.copy(backupBusy = false) } }
            }
        }
        return true
    }

    private fun offerBackup(active: CompanionTransfer, peer: CompanionPeer, nonce: String) {
        check(foreground && mutable.value.backupOffer == null && !mutable.value.backupBusy && !mutable.value.restoreDialog && mutable.value.prompt == null)
        check(companionReceiveModeAllowed(television, active.mode))
        val service = requireNotNull(settingsService)
        val safe = service.prepareFile(active.finish(), active.credentials, active.mode)
        val parsed = service.preview(safe)
        releaseRemoteControl()
        offerRaw = safe; offerSession = nonce
        mutable.update { it.copy(backupOffer = CompanionBackupOffer(active.id, peer.name, active.mode, active.credentials,
            syncAccountChoices(parsed).count { !it.key.startsWith("files:") && !it.key.startsWith("dlna:") && !it.key.startsWith("iptv:") },
            parsed.fileSources.size + parsed.dlnaServers.size + parsed.iptvSources.orEmpty().size, exportedAt = parsed.exportedAtEpochMs,
            localAppliedAt = prefs.getLong(KEY_LAST_APPLIED, 0L), fromTelevision = !television), pulling = false) }
        backupResults[nonce] = active.id to "pending"
        notice(R.string.companion_backup_received, peer.name)
        offerExpiry?.cancel(); offerExpiry = scope.launch(Dispatchers.Main) { delay(120_000); rejectBackup("expired") }
    }

    private fun updateBackupResult(id: String, status: String, name: String) {
        if (id == outgoingBackupId && status in setOf("pending", "applied", "rejected", "expired", "failed") && status != mutable.value.backupStatus) {
            mutable.update { it.copy(backupStatus = status, backupBusy = false) }
            if (status != "pending") notice(when (status) {
                "applied" -> R.string.companion_backup_applied
                "failed" -> companionBackupFailureMessage(BackupFailure.APPLY)
                else -> R.string.companion_backup_declined
            }, name, error = status == "failed")
        }
    }

    /** Only called by local receiver UI; no protocol command can accept an import. */
    fun rejectBackup(status: String = "rejected") {
        if (mutable.value.backupBusy) return
        val offer = mutable.value.backupOffer ?: return
        offerSession?.let { backupResults[it] = offer.id to status }
        offerExpiry?.cancel(); offerExpiry = null
        offerRaw = null; offerSession = null
        mutable.update { it.copy(backupOffer = null, backupStatus = status) }
        notice(R.string.companion_backup_declined, offer.sender)
    }

    fun applyBackup(overwrite: Boolean, deviceSettings: Boolean) {
        val offer = mutable.value.backupOffer ?: return
        val raw = offerRaw ?: return
        val session = offerSession ?: return
        val service = settingsService ?: return
        if (!foreground || !companionReceiveModeAllowed(television, offer.mode) || !settingsCanApply() || mutable.value.backupBusy || (offer.mode in setOf("settings", "preferences", "nav_sounds") && !overwrite)) {
            notice(R.string.companion_backup_wait, error = true); return
        }
        offerExpiry?.cancel(); offerExpiry = null
        mutable.update { it.copy(backupBusy = true) }
        scope.launch {
            val success = runCatching { service.apply(raw, offer.mode, overwrite, deviceSettings) }.isSuccess
            withContext(Dispatchers.Main) {
                backupResults[session] = offer.id to if (success) "applied" else "failed"
                if (success) prefs.edit().putLong(KEY_LAST_APPLIED, System.currentTimeMillis()).apply()
                offerRaw = null; offerSession = null
                mutable.update { it.copy(backupOffer = null, backupBusy = false, backupStatus = if (success) "applied" else "failed", rollbackAvailable = service.hasRollback(),
                    settingsRevision = it.settingsRevision + if (success) 1 else 0) }
                notice(if (success) R.string.companion_backup_applied else companionBackupFailureMessage(BackupFailure.APPLY), offer.sender, error = !success)
            }
        }
    }

    fun showRestore() { releaseRemoteControl(); mutable.update { it.copy(restoreDialog = true, panel = false) } }
    fun closeRestore() { if (!mutable.value.backupBusy) mutable.update { it.copy(restoreDialog = false) } }
    fun restoreSettings() {
        val service = settingsService ?: return
        if (!mutable.value.restoreDialog || mutable.value.backupBusy || !settingsCanApply()) {
            notice(R.string.companion_backup_wait, error = true); return
        }
        mutable.update { it.copy(backupBusy = true) }
        scope.launch {
            val success = runCatching { service.restore() }.isSuccess
            withContext(Dispatchers.Main) {
                mutable.update { it.copy(restoreDialog = false, backupBusy = false, settingsRevision = it.settingsRevision + if (success) 1 else 0) }
                notice(if (success) R.string.companion_backup_restored else R.string.companion_backup_failed, error = !success)
            }
        }
    }
    /** A settings page that searched for TVs is left: stop, unless the pairing panel still uses it. */
    fun stopSearchUnlessPanel() {
        if (pageSearchUsers.updateAndGet { maxOf(0, it - 1) } > 0) return
        if (!television && !mutable.value.panel) stopDiscovery()
    }

    /** Screens that list TVs (settings page, sync, playback target) keep the search running. */
    private val pageSearchUsers = java.util.concurrent.atomic.AtomicInteger(0)
    private val pageSearching get() = pageSearchUsers.get() > 0

    fun startPageSearch() {
        pageSearchUsers.incrementAndGet()
        discover()
        startProbing()
    }

    private var probeJob: Job? = null
    private val lastProbe = ConcurrentHashMap<String, Long>()

    /**
     * mDNS can be blocked on some networks: while a TV-listing screen is open, paired TVs that the
     * search does not see are probed at their stored host:port (pinned TLS + challenge, 1.5 s),
     * at most every 30 s each. Success marks them reachable for the usual 2-minute TTL.
     */
    @Synchronized private fun startProbing() {
        if (television || probeJob?.isActive == true) return
        probeJob = scope.launch {
            delay(1_500) // Let mDNS answer first.
            while (isActive && pageSearchUsers.get() > 0 && foreground && LocalNetworkAccess.isGranted(context)) {
                val link = lan()
                val now = SystemClock.elapsedRealtime()
                val due = mutable.value.paired.filter { peer ->
                    val vpn = runCatching { connectionTransport?.store?.get(peer.fingerprint)?.wireGuard == true }.getOrDefault(false)
                    val onLan = link != null && runCatching { link.contains(InetAddress.getByName(peer.host)) }.getOrDefault(false)
                    peer.role != COMPANION_HANDHELD && companionNeedsProbe(peer, mutable.value, vpn, onLan, lastProbe[peer.fingerprint], now)
                }
                for (peer in due) {
                    if (!isActive || pageSearchUsers.get() == 0) break
                    lastProbe[peer.fingerprint] = SystemClock.elapsedRealtime()
                    mutable.update { it.copy(probing = it.probing + peer.fingerprint) }
                    val ok = link != null && probePeer(peer, link)
                    mutable.update { it.copy(probing = it.probing - peer.fingerprint,
                        lastSeen = if (ok) it.lastSeen + (peer.fingerprint to SystemClock.elapsedRealtime()) else it.lastSeen) }
                }
                delay(5_000)
            }
            mutable.update { it.copy(probing = emptySet()) }
        }
    }

    private suspend fun probePeer(peer: CompanionPeer, link: Lan): Boolean = withContext(Dispatchers.IO) {
        val raw = Socket()
        try {
            withTimeout(3_500) {
                runInterruptible {
                    link.network.bindSocket(raw)
                    raw.connect(InetSocketAddress(InetAddress.getByName(peer.host), peer.port), 1_500)
                    val socket = identity.clientContext(peer.fingerprint).socketFactory.createSocket(raw, peer.host, peer.port, true) as SSLSocket
                    socket.use {
                        it.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2"); it.soTimeout = 1_500; it.startHandshake()
                        val challenge = DataInputStream(it.inputStream).readFrame()
                        challenge.type == "challenge" && challenge.role == COMPANION_TV
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            false
        } catch (_: Exception) { false } finally { runCatching { raw.close() } }
    }

    fun hidePanel() {
        remoteAfterConnect = null
        transferAfterPairing = false
        if (mutable.value.sendingTo != null) disconnect(clearTarget = false)
        pendingConnection = null
        pendingPairingOpen = false
        mutable.update { it.copy(panel = false, sharedMedia = null) }
        // The settings page behind the panel keeps listing TVs.
        closePairing(); if (!pageSearching) stopDiscovery()
    }
    fun enableReceiver(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        mutable.update { it.copy(enabled = enabled) }
        if (enabled && LocalNetworkAccess.isGranted(context)) startServer() else if (!enabled) { enableBackgroundReceiver(false); closePairing(); stopReceiver() }
    }

    private data class Lan(val network: Network, val address: Inet4Address, val prefix: Int) {
        fun contains(remote: InetAddress): Boolean {
            if (remote !is Inet4Address || !isPrivateV4(remote.hostAddress.orEmpty())) return false
            val a = address.address; val b = remote.address
            return (0 until prefix).all { bit -> (a[bit / 8].toInt() xor b[bit / 8].toInt()) and (128 shr (bit % 8)) == 0 }
        }
    }
    @Volatile private var recentLan: Pair<Long, Lan?>? = null

    /**
     * The per-frame "still on the same LAN" check of an open session. Touchpad input arrives many
     * times per second and each [lan] scan costs several binder calls, so it reuses a result for
     * up to a second. Socket setup and pairing always scan fresh.
     */
    private fun sessionLan(): Lan? {
        val now = SystemClock.elapsedRealtime()
        recentLan?.let { (at, cached) -> if (now - at in 0 until LAN_RECHECK_MS) return cached }
        return lan().also { recentLan = now to it }
    }

    @Suppress("DEPRECATION")
    private fun lan(): Lan? = connectivity.allNetworks.firstNotNullOfOrNull { network ->
        val caps = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@firstNotNullOfOrNull null
        connectivity.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && isPrivateV4(it.address.hostAddress.orEmpty()) && it.prefixLength in 8..30
        }?.let { Lan(network, it.address as Inet4Address, it.prefixLength) }
    }

    @Synchronized private fun startServer() {
        if (!receiverActive() || (television && !mutable.value.enabled) || (!television && mutable.value.paired.isEmpty()) || !LocalNetworkAccess.isGranted(context) || serverJob?.isActive == true) return
        if (television && !queueLoaded) { restoreQueue(); return }
        serverJob = scope.launch {
            var listener: SSLServerSocket? = null
            try {
                val link = requireNotNull(lan())
                // Reuse the last port so a paired phone can reconnect from its stored address
                // when mDNS is filtered (mesh, AP isolation). A taken port falls back to a new one.
                fun open(port: Int) = (identity.serverContext().serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                    enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                    try {
                        bind(InetSocketAddress(link.address, port), 4)
                    } catch (failure: Exception) {
                        runCatching { close() }
                        throw failure
                    }
                }
                val lastPort = prefs.getInt(KEY_SERVER_PORT, 0).takeIf { it in 1024..65535 }
                val local = lastPort?.let { runCatching { open(it) }.getOrNull() } ?: open(0)
                if (local.localPort != lastPort) prefs.edit().putInt(KEY_SERVER_PORT, local.localPort).apply()
                listener = local
                synchronized(this@CompanionManager) {
                    ensureActive()
                    check(receiverActive() && (!television || mutable.value.enabled))
                    server = local
                    if (television) advertise(local.localPort)
                }
                while (isActive) {
                    val socket = local.accept() as SSLSocket
                    synchronized(this@CompanionManager) {
                        val ticket = if (server !== local || !receiverActive() || !acceptAddress(link, socket.inetAddress)) null
                            else admission.admit(socket.inetAddress.hostAddress.orEmpty())
                        if (ticket == null) {
                            socket.close()
                        } else {
                            sockets[socket] = ""
                            // Independently scheduled so listener cancellation cannot skip cleanup
                            // of an accepted socket before its coroutine has started.
                            scope.launch { try { serve(socket, link, ticket) } finally { ticket.release(); sockets.remove(socket); runCatching { socket.close() } } }
                        }
                    }
                }
            } catch (_: Exception) {
                if (isActive && foreground) notice(R.string.companion_unavailable, error = true)
            } finally {
                runCatching { listener?.close() }
                if (server === listener) server = null
            }
        }
    }

    fun openPairing() {
        if (!television) return
        diagnosticFailure = ""
        if (server != null && server?.inetAddress != lan()?.address) stopReceiver()
        enableReceiver(true)
        if (!LocalNetworkAccess.isGranted(context)) {
            pendingPairingOpen = true
            return
        }
        pendingPairingOpen = false
        pairOpening?.cancel()
        pairOpening = scope.launch {
            repeat(30) { if (server == null) delay(100) }
            val listener = server ?: return@launch
            closePairing()
            pairToken = randomToken(); pairCode = randomPairCode(); pairAttempts = 0
            pairDeadline = SystemClock.elapsedRealtime() + 120_000
            mutable.update { it.copy(
                invitation = CompanionInvitation(listener.inetAddress.hostAddress!!, listener.localPort, identity.pin, pairToken).link(),
                pairCode = formatPairCode(pairCode),
                pairExpiresAt = pairDeadline,
            ) }
            pairExpiry = scope.launch { delay(120_000); closePairing() }
        }
    }
    @Synchronized fun closePairing() {
        pendingPairingOpen = false
        pairToken = ""; pairCode = ""; pairAttempts = 0; pairDeadline = 0
        pairExpiry?.cancel(); pairExpiry = null
        confirmation?.complete(false); confirmation = null
        mutable.update { it.copy(invitation = null, pairCode = "", pairingWith = "", pairExpiresAt = 0L, prompt = null) }
    }
    fun confirm(accepted: Boolean) { confirmation?.complete(accepted) }

    /** Caller holds the monitor. The QR link carries [pairToken], a typed code [pairCode]. */
    private fun invitationAccepted(offered: String): Boolean {
        if (MessageDigest.isEqual(offered.toByteArray(), pairToken.toByteArray())) return true
        val typed = normalizePairCode(offered)
        return pairCode.isNotEmpty() && typed.length == PAIR_CODE_LENGTH &&
            MessageDigest.isEqual(typed.toByteArray(), pairCode.toByteArray())
    }

    /** Phone side: pair with a discovered TV using the short code shown on its screen. */
    fun pairWithCode(peer: CompanionPeer, code: String) {
        if (television) return
        val normalized = normalizePairCode(code)
        if (normalized.length != PAIR_CODE_LENGTH) { notice(R.string.companion_pair_code_invalid, error = true); return }
        if (peer.host.isBlank() || peer.port !in 1..65535) { notice(R.string.companion_connect_failed, peer.name, error = true); return }
        connect(peer, normalized)
    }

    private fun admitHandshake(address: InetAddress): Boolean =
        handshakeLimiter.admit(address.hostAddress.orEmpty(), SystemClock.elapsedRealtime())
    @Synchronized private fun rejectNotice() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRejected > 30_000) { lastRejected = now; notice(R.string.companion_rejected, error = true) }
    }

    private suspend fun approval(name: String, code: String): Boolean {
        val pending = CompletableDeferred<Boolean>()
        synchronized(this) {
            if (confirmation != null) return false
            confirmation = pending
            mutable.update { it.copy(prompt = CompanionPrompt(name, code)) }
        }
        return try { withTimeoutOrNull(110_000) { pending.await() } == true } finally {
            synchronized(this) {
                if (confirmation === pending) { confirmation = null; mutable.update { it.copy(prompt = null) } }
            }
        }
    }

    private suspend fun serve(socket: SSLSocket, link: Lan, ticket: CompanionAdmission.Ticket) {
        var peer: CompanionPeer? = null
        var observer = false
        var queueOffset = 0
        var session = ""
        var transfer: CompanionTransfer? = null
        var transferDeadline = 0L
        var returnSession: ReturnSession? = null
        var pairingStarted = false
        var newlyPaired = false
        // TLS and authentication must finish in time: soTimeout is per read, so a client that
        // trickles bytes would otherwise hold its unauthenticated slot indefinitely.
        val authDeadline = scope.launch { delay(AUTH_DEADLINE_MS); runCatching { socket.close() } }
        var admitted = false
        fun authenticated() {
            if (admitted) return
            authDeadline.cancel()
            require(ticket.promote())
            admitted = true
        }
        try {
            require(admitHandshake(socket.inetAddress))
            socket.soTimeout = 8_000
            socket.startHandshake()
            val input = DataInputStream(socket.inputStream); val output = DataOutputStream(socket.outputStream)
            val nonce = randomToken()
            session = nonce
            output.writeFrame(CompanionFrame(type = "challenge", role = deviceRole, nonce = nonce, name = deviceName))
            val auth = input.readFrame()
            require(auth.type == "auth" && auth.nonce == nonce && auth.key.length <= 256 && auth.signature.length <= 256)
            if (!companionRolesAllowed(deviceRole, auth.role)) {
                output.writeFrame(CompanionFrame(type = "error", error = "device_role", nonce = nonce))
                return
            }
            val publicBytes = Base64.getDecoder().decode(auth.key)
            val pin = fingerprint(publicBytes)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
            require(Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey); update(authTranscript(nonce, identity.pin, pin, auth.role, deviceRole)); verify(Base64.getDecoder().decode(auth.signature))
            })
            var trusted = mutable.value.paired.firstOrNull { it.fingerprint == pin || it.authenticationKey == pin }
            observer = auth.mode == "observe"
            require(!observer || trusted != null) // Observation can never open pairing or consent.
            if (trusted == null) {
                require(link.contains(socket.inetAddress)) // VPN sessions may reconnect trusted devices, never open pairing.
                require(television) // Handsets accept existing pairings only; never remote invitations.
                val invitationDeadline = synchronized(this) {
                    require(pairToken.isNotEmpty() && SystemClock.elapsedRealtime() < pairDeadline)
                    if (!invitationAccepted(auth.invitation)) {
                        // A wrong code is a typo or a guess; either way a few tries end this window,
                        // which is what keeps the short code out of reach of brute force.
                        pairAttempts++
                        if (pairAttempts >= MAX_PAIR_ATTEMPTS) {
                            closePairing()
                            notice(R.string.companion_pair_code_blocked, error = true)
                        } else {
                            notice(R.string.companion_pair_code_wrong, error = true)
                        }
                        error("Invitation mismatch")
                    }
                    // Consume before presenting UI, and bind approval to this exact window.
                    pairToken = ""; pairCode = ""
                    pairDeadline
                }
                // A valid invitation: the user's confirmation below may take much longer.
                authenticated()
                val name = safeDeviceName(auth.name)
                // The QR is spent: show who is pairing instead of an invitation that no longer works.
                pairingStarted = true
                mutable.update { it.copy(invitation = null, pairCode = "", pairExpiresAt = 0L, pairingWith = name) }
                socket.soTimeout = 115_000
                output.writeFrame(CompanionFrame(type = "confirm", nonce = nonce))
                val local = scope.async { approval(name, comparisonCode(nonce, identity.pin, pin)) }
                try {
                    val remote = input.readFrame()
                    require(remote.type == "confirmed" && remote.nonce == nonce && local.await())
                } finally { local.cancel() }
                trusted = CompanionPeer(pin, name, role = auth.role)
                synchronized(this) {
                    require(foreground && pairDeadline == invitationDeadline && SystemClock.elapsedRealtime() < invitationDeadline)
                    savePeer(trusted)
                    closePairing()
                }
                newlyPaired = true
                // Done: the pairing panel closes on its own and a success notice confirms it.
                scope.launch(Dispatchers.Main) { if (mutable.value.panel) hidePanel() }
            }
            authenticated()
            if (trusted.role != auth.role) { trusted = trusted.copy(role = auth.role); savePeer(trusted) }
            peer = trusted
            val sessionPin = trusted.fingerprint
            sockets.filterValues { it == sessionPin }.keys.filter { it !== socket }.forEach { runCatching { it.close() } }
            sockets[socket] = sessionPin
            if (!observer) {
                mutable.update { it.copy(connected = (it.connected + peer.name).distinct()) }
                if (newlyPaired) pairedNotice(peer) else connectionNotice(peer)
            }
            output.writeFrame(CompanionFrame(type = "ready", nonce = nonce, name = deviceName, networkMac = if (television) receiverMac(link) else ""))
            socket.soTimeout = 15_000
            val sequence = CompanionSequence(nonce)
            while (receiverActive() && mutable.value.paired.any { it.fingerprint == sessionPin }) {
                val request = input.readFrame()
                sequence.accept(request)
                require(acceptAddress(link, socket.inetAddress) && sessionLan()?.address == link.address)
                val reply = withContext(Dispatchers.Main) {
                    val mutatesPlayback = request.type in setOf("start", "queue_add", "queue_remove", "queue_move", "queue_clear", "queue_play", "play", "pause", "stop", "seek", "next", "previous", "select", "volume", "shuffle", "repeat", "retry")
                    if (mutatesPlayback) playbackMutation.lock()
                    try {
                        // Recheck on the dispatcher that executes commands, including after revocation.
                        require(receiverActive() && mutable.value.paired.any { it.fingerprint == sessionPin })
                        if (!observer && "duplex_sync" in request.capabilities) {
                            val back = returnSession ?: ReturnSession(nonce).also {
                                returnSession = it; returnSessions.put(sessionPin, it)?.close()
                            }
                            mutable.update { it.copy(syncPeers = it.syncPeers + (sessionPin to request.capabilities)) }
                            if (transferAfterPairing) { transferAfterPairing = false; showSettingsTransfer() }
                            if (request.syncAck != 0L) {
                                val pending = requireNotNull(back.pending)
                                require(request.syncAck == pending.first.sequence)
                                pending.second.complete(request.syncError.isEmpty()); back.pending = null
                            }
                            updateBackupResult(request.syncResultId, request.syncResult, peer.name)
                        } else if (returnSession != null) {
                            returnSessions.remove(sessionPin, returnSession)
                            returnSession?.close(); returnSession = null
                            mutable.update { it.copy(syncPeers = it.syncPeers - sessionPin) }
                        }
                        var error = ""
                        try {
                            if (request.type !in setOf("status", "input_release", "backup_cancel") &&
                                (mutable.value.backupOffer != null || mutable.value.backupBusy || mutable.value.restoreDialog || mutable.value.prompt != null || mutable.value.pullRequest != null)) {
                                error = "consent_pending"; error("Local consent is pending")
                            }
                            require(!observer || request.type == "status")
                            require(companionCommandAllowed(television, request.type))
                            if (television && !observer && request.syncPort in 1..65535) {
                                val endpoint = mutable.value.paired.first { it.fingerprint == sessionPin }
                                    .copy(host = socket.inetAddress.hostAddress.orEmpty(), port = request.syncPort)
                                if (mutable.value.paired.firstOrNull { it.fingerprint == sessionPin } != endpoint) savePeer(endpoint)
                            }
                            require(foreground || request.type == "start" && receiverServiceActive && mutable.value.backgroundReceiver || request.type in setOf("status", "play", "pause", "stop", "seek", "volume", "select", "shuffle", "repeat", "next", "previous", "queue_add", "queue_remove", "queue_move", "queue_clear", "queue_page", "input_release"))
                            when (request.type) {
                                "status" -> {
                                    if (synchronized(inputLease) { inputLease.owns(nonce, SystemClock.elapsedRealtime()) }) claimControl(nonce, peer.name)
                                }
                                "input_claim" -> {
                                    if (!synchronized(inputLease) { inputLease.owner == null || inputLease.owns(nonce, SystemClock.elapsedRealtime()) }) {
                                        error = "input_busy"; error("Controller already active")
                                    }
                                    claimControl(nonce, peer.name)
                                }
                                "input_release" -> releaseRemoteControl(nonce)
                                "input" -> {
                                    check(synchronized(inputLease) { inputLease.owns(nonce, SystemClock.elapsedRealtime()) })
                                    claimControl(nonce, peer.name)
                                    requireNotNull(request.input).validate()
                                    error = "input_unavailable"
                                    check(CompanionRemoteInput.dispatch(request.input))
                                    error = ""
                                }
                                "backup_begin" -> {
                                    check(settingsService != null && transfer == null)
                                    require(companionReceiveModeAllowed(television, request.mode))
                                    transfer = CompanionTransfer(request.transferId, request.total, request.digest, request.mode, request.credentials)
                                    transferDeadline = SystemClock.elapsedRealtime() + 120_000
                                }
                                "backup_chunk" -> {
                                    val active = requireNotNull(transfer)
                                    check(active.id == request.transferId && SystemClock.elapsedRealtime() < transferDeadline)
                                    active.append(request.index, request.payload)
                                }
                                "backup_finish" -> {
                                    val active = requireNotNull(transfer)
                                    check(active.id == request.transferId && SystemClock.elapsedRealtime() < transferDeadline)
                                    offerBackup(active, peer, nonce)
                                    transfer = null
                                }
                                "backup_cancel" -> { transfer = null; if (offerSession == nonce) rejectBackup() }
                                "backup_request" -> error("TV-to-phone transfers are unsupported")
                                "start" -> {
                                    requireNotNull(request.media)
                                    require(request.startRequestId.length <= 64)
                                    if (executeRemoteStart(request, sessionPin, socket)) {
                                        playbackQueue.clear(); CompanionQueuePlayback.pending = false
                                        persistQueue()
                                        notice(R.string.companion_remote_start, peer.name)
                                    }
                                }
                                "queue_page" -> { require(request.position in 0..63); queueOffset = request.position.toInt() }
                                "queue_add", "queue_remove", "queue_move", "queue_clear", "queue_play" -> applyQueueCommand(request)
                                "play", "pause", "stop", "seek", "next", "previous", "select", "volume", "shuffle", "repeat", "retry" -> applyPlaybackCommand(request)
                                else -> error("Unsupported command")
                            }
                        } catch (_: CancellationException) { throw CancellationException() }
                        catch (failure: Exception) { if (error.isEmpty()) error = if (failure is CompanionSourceUnavailableException) "source_unavailable" else if (failure is CompanionLaunchException) failure.reason else "unsupported"; if (request.type.startsWith("backup_")) transfer = null }
                        val back = returnSession
                        check(back?.pending == null) // Every return frame must be acknowledged by the next request.
                        val next = back?.frames?.tryReceive()?.getOrNull()
                        val returned = next?.let { CompanionSyncFrame.from(++back.sequence, it.first).also { frame -> back.pending = frame to it.second } }
                        val playback = playbackState(queueOffset).let { if (returned == null) it else it.copy(artwork = "", options = emptyList(), queue = emptyList()) }
                        val result = backupResults[nonce]
                        playback.copy(nonce = nonce, sequence = request.sequence, syncReturn = returned,
                            controller = mutable.value.controller, inputGranted = synchronized(inputLease) { inputLease.owns(nonce, SystemClock.elapsedRealtime()) },
                            transferId = result?.first.orEmpty(), backupStatus = result?.second.orEmpty(),
                            error = companionReplyError(error, playback.phase, request.type))
                    } finally { if (mutatesPlayback) playbackMutation.unlock() }
                }
                output.writeFrame(reply)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (peer != null && failure !is EOFException && failure !is SocketException) debugFailure("receiver", failure)
            // A reachability probe closes cleanly after the challenge: not a rejected pairing.
            if (peer == null && foreground && failure !is EOFException) rejectNotice()
            // A cancelled or timed-out confirmation spent the QR: offer a fresh one while the panel is open.
            if (pairingStarted && !newlyPaired) withContext(NonCancellable + Dispatchers.Main) {
                mutable.update { it.copy(pairingWith = "") }
                if (television && foreground && mutable.value.panel && mutable.value.invitation == null) openPairing()
            }
        } finally {
            authDeadline.cancel()
            releaseRemoteControl(session)
            withContext(NonCancellable + Dispatchers.Main) {
                if (offerSession == session && !mutable.value.backupBusy) rejectBackup("expired")
                if (mutable.value.pullRequest?.session == session) mutable.update { it.copy(pullRequest = null) }
                backupResults.remove(session)
                peer?.let { p ->
                    returnSession?.let { back ->
                        if (returnSessions.remove(p.fingerprint, back)) {
                            mutable.update { it.copy(syncPeers = it.syncPeers - p.fingerprint) }
                            if (mutable.value.backupStatus in setOf("sending", "pending")) mutable.update { it.copy(backupBusy = false, backupStatus = "failed") }
                        }
                        back.close()
                    }
                }
            }
            peer?.takeUnless { observer }?.let { p ->
                mutable.update { it.copy(connected = it.connected - p.name) }
                notice(R.string.companion_disconnected, p.name)
            }
        }
    }

    fun pair(link: String) {
        if (television) return
        val invite = runCatching { CompanionInvitation.parse(link) }.getOrNull()
        if (invite == null) { notice(R.string.companion_invalid_invitation, error = true); return }
        connect(CompanionPeer(invite.fingerprint, "TV", invite.host, invite.port), invite.token)
    }

    /** The single reachability answer used by every device list, picker and action guard. */
    fun reachability(peer: CompanionPeer, state: CompanionState = mutable.value, nowMs: Long = SystemClock.elapsedRealtime()): PeerReachability {
        if (television && peer.name in state.connected) return PeerReachability.CONNECTED
        val wakeable = !television && companionWakeable(runCatching { connectionTransport?.store?.get(peer.fingerprint) }.getOrNull())
        return companionReachability(peer.fingerprint, state, wakeable, nowMs)
    }

    /** Blocks an action on a paired device nothing can reach; says why in a notification. */
    private fun guardReachable(peer: CompanionPeer): Boolean {
        if (mutable.value.paired.none { it.fingerprint == peer.fingerprint } || reachability(peer).allowsActions()) return true
        notice(R.string.companion_unreachable_notice, peer.name, error = true)
        return false
    }

    @Synchronized fun connect(peer: CompanionPeer, invitation: String = "", initialMedia: CompanionMedia? = null, allowWake: Boolean = true, openRemote: Boolean = false) {
        if (television || !foreground || peer.role == COMPANION_HANDHELD) return
        if (invitation.isEmpty() && !guardReachable(peer)) return
        diagnosticFailure = ""
        stopSessionWatch(peer.fingerprint)
        if (!LocalNetworkAccess.isGranted(context)) {
            pendingConnection = PendingConnection(peer, invitation, initialMedia)
            // Resumed once access is granted; the panel offers the permission, so open it and say why.
            mutable.update { it.copy(target = peer, busy = false, panel = true, nowPlayingPanel = false, settingsPanel = false) }
            notice(R.string.companion_needs_local_network, error = true)
            return
        }
        pendingConnection = null
        val keepSettingsPanel = mutable.value.settingsPanel
        disconnect(clearTarget = true)
        remoteAfterConnect = peer.fingerprint.takeIf { openRemote }
        val queue = Channel<CompanionFrame>(16); commands = queue
        mutable.update { it.copy(target = peer, busy = true, connectionError = "", connectionStage = R.string.companion_connecting, settingsPanel = keepSettingsPanel, sendingTo = if (initialMedia != null) peer.name else null) }
        clientJob = scope.launch {
            var connected = false
            var clientSyncNonce = ""
            var ownedSocket: Socket? = null
            try {
                val configured = connectionTransport?.store?.get(peer.fingerprint)
                val vpn = configured?.wireGuard == true && mutable.value.paired.any { it.fingerprint == peer.fingerprint }
                val link = if (vpn) null else requireNotNull(lan()) { "other_network" }
                val route = if (vpn) {
                    mutable.update { it.copy(connectionStage = R.string.companion_connection_vpn) }
                    requireNotNull(connectionTransport).vpnRoute(peer)
                } else {
                    require(isPrivateV4(peer.host)) { "other_network" }
                    val address = InetAddress.getByName(peer.host)
                    require(requireNotNull(link).contains(address)) { "other_network" }
                    CompanionRoute(link.network, address, configured?.port?.takeIf { it > 0 } ?: peer.port, false)
                }
                fun own(raw: Socket) {
                    ownedSocket = raw
                    synchronized(this@CompanionManager) {
                        check(commands === queue)
                        clientSocket = raw
                    }
                }
                val raw = connectionTransport?.open(peer, route,
                    allowWake && invitation.isEmpty() && mutable.value.paired.any { it.fingerprint == peer.fingerprint }, ::own) { stage ->
                    mutable.update { it.copy(connectionStage = stage) }
                } ?: Socket().also { own(it); route.network.bindSocket(it); it.connect(InetSocketAddress(route.address, route.port), 5_000) }
                mutable.update { it.copy(connectionStage = R.string.companion_connecting) }
                val socket = identity.clientContext(peer.fingerprint).socketFactory.createSocket(raw, route.address.hostAddress, route.port, true) as SSLSocket
                ownedSocket = socket
                synchronized(this@CompanionManager) {
                    ensureActive(); check(commands === queue)
                    clientSocket = socket
                }
                socket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                socket.soTimeout = 8_000
                socket.startHandshake()
                val serverCertificate = socket.session.peerCertificates.single() as java.security.cert.X509Certificate
                val serverPin = fingerprint(serverCertificate.encoded)
                val input = DataInputStream(socket.inputStream); val output = DataOutputStream(socket.outputStream)
                val challenge = input.readFrame()
                require(challenge.type == "challenge" && challenge.nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
                    if (challenge.role != COMPANION_TV) {
                        notice(R.string.companion_role_required, error = true)
                        error("device_role")
                    }
                val nonce = challenge.nonce
                val bytes = identity.certificate.publicKey.encoded
                val pin = fingerprint(bytes)
                output.writeFrame(CompanionFrame(type = "auth", role = deviceRole, nonce = nonce, name = deviceName,
                    key = Base64.getEncoder().encodeToString(bytes), invitation = invitation,
                    signature = Base64.getEncoder().encodeToString(identity.sign(authTranscript(nonce, serverPin, pin, deviceRole, challenge.role)))))
                var response = input.readFrame()
                val name = safeDeviceName(challenge.name)
                val pairing = response.type == "confirm"
                if (pairing) {
                    require(invitation.isNotEmpty() && response.nonce == nonce)
                    require(approval(name, comparisonCode(nonce, serverPin, pin)))
                    output.writeFrame(CompanionFrame(type = "confirmed", nonce = nonce))
                    socket.soTimeout = 115_000
                    response = input.readFrame()
                }
                require(response.type == "ready" && response.nonce == nonce)
                val trusted = peer.copy(name = name, authenticationKey = fingerprint(serverCertificate.publicKey.encoded), role = challenge.role)
                synchronized(this@CompanionManager) {
                    ensureActive(); check(commands === queue)
                    if (initialMedia != null) require(mutable.value.paired.any { it.fingerprint == peer.fingerprint })
                    savePeer(trusted); connectionTransport?.store?.learnMac(trusted.fingerprint, response.networkMac); connected = true
                    mutable.update { it.copy(target = trusted, online = true, busy = false, lastSeen = it.lastSeen + (trusted.fingerprint to SystemClock.elapsedRealtime())) }
                }
                if (pairing) {
                    pairedNotice(trusted)
                    dropStaleNamesakes(trusted)
                    if (mutable.value.pairingFlow) mutable.update { it.copy(pairingResult = trusted.name) }
                } else connectionNotice(trusted)
                withContext(Dispatchers.Main) {
                    if (transferAfterPairing) { transferAfterPairing = false; showSettingsTransfer() }
                    else if (remoteAfterConnect == trusted.fingerprint) { remoteAfterConnect = null; showRemote() }
                }
                socket.soTimeout = 8_000
                var sequence = 0L
                clientSyncNonce = nonce
                var returnSequence = 0L
                var returnAck = 0L
                var returnError = ""
                var returnTransfer: CompanionTransfer? = null
                var returnDeadline = 0L
                var carried: CompanionFrame? = null
                if (initialMedia != null) queue.send(CompanionFrame(type = "start", media = initialMedia))
                while (isActive && (foreground || remoteServiceRequested)) {
                    require(if (route.vpn) connectionTransport?.alive(route) == true else sessionLan()?.address == link?.address)
                    var command = carried?.also { carried = null }
                        ?: withTimeoutOrNull(if (returnTransfer != null || returnAck != 0L) 50L else 1_000L) { queue.receive() } ?: CompanionFrame(type = "status")
                    // Each frame waits for the TV's answer, so touchpad moves queued meanwhile are
                    // summed into one frame instead of trailing behind the finger.
                    while (true) {
                        val next = queue.tryReceive().getOrNull() ?: break
                        val merged = coalesceCompanionInput(command, next)
                        if (merged == null) { carried = next; break }
                        command = merged
                    }
                    val result = withContext(Dispatchers.Main) { backupResults[nonce] }
                    val request = command.copy(nonce = nonce, sequence = ++sequence,
                        capabilities = emptyList(),
                        syncAck = returnAck, syncError = returnError,
                        syncResultId = result?.first.orEmpty(), syncResult = result?.second.orEmpty(),
                        syncPort = if (foreground && !route.vpn) server?.localPort ?: 0 else 0)
                    socket.soTimeout = if (command.type == "start") 30_000 else 8_000
                    output.writeFrame(request)
                    val state = input.readFrame()
                    require(state.type == "state" && state.nonce == nonce && state.sequence == sequence)
                    returnAck = 0L; returnError = ""
                    state.syncReturn?.let { returned ->
                        require(returned.sequence == returnSequence + 1)
                        returnSequence = returned.sequence; returnAck = returned.sequence
                        withContext(Dispatchers.Main) {
                            try {
                                check(commands === queue && foreground && mutable.value.paired.any { it.fingerprint == trusted.fingerprint })
                                val frame = returned.command()
                                if (frame.type != "backup_cancel") check(settingsService != null && !mutable.value.backupBusy && mutable.value.backupOffer == null && !mutable.value.restoreDialog && mutable.value.prompt == null)
                                when (frame.type) {
                                    "backup_begin" -> {
                                        check(returnTransfer == null)
                                        require(companionReceiveModeAllowed(television, frame.mode))
                                        returnTransfer = CompanionTransfer(frame.transferId, frame.total, frame.digest, frame.mode, frame.credentials)
                                        returnDeadline = SystemClock.elapsedRealtime() + 120_000
                                    }
                                    "backup_chunk", "backup_finish" -> {
                                        val active = requireNotNull(returnTransfer)
                                        check(active.id == frame.transferId && SystemClock.elapsedRealtime() < returnDeadline)
                                        if (frame.type == "backup_chunk") active.append(frame.index, frame.payload)
                                        else { offerBackup(active, trusted, nonce); returnTransfer = null }
                                    }
                                    "backup_cancel" -> { returnTransfer = null; if (offerSession == nonce) rejectBackup() }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { returnTransfer = null; returnError = "unsupported" }
                        }
                    }
                    synchronized(this@CompanionManager) {
                        ensureActive(); check(commands === queue)
                        require(state.type == "state" && state.nonce == nonce && state.sequence == sequence)
                        // Transfer frames report their own classified failure from sendSettings.
                        if (state.error.isNotEmpty() && !command.type.startsWith("backup_") && (command.type != "status" || mutable.value.remote?.error != state.error)) notice(
                            when { command.type.startsWith("input") -> R.string.companion_control_failed
                                command.type.startsWith("backup_") -> R.string.companion_backup_failed
                                else -> R.string.companion_play_failed }, error = true)
                        mutable.update { it.copy(remote = state.copy(receiverId = trusted.fingerprint)) }
                        if (command.type == "start" && command.startRequestId.isNotEmpty()) {
                            playbackStartAck?.takeIf { it.first == command.startRequestId }?.second?.complete(state)
                        }
                        ensureRemoteService()
                        if (command.type.startsWith("backup_")) { backupAckError = state.error; backupAck?.complete(companionBackupAckOk(state.error)) }
                        updateBackupResult(state.transferId, state.backupStatus, name)
                        if (command.type == "start" && mutable.value.sendingTo != null) {
                            if (state.error.isEmpty()) {
                                notice(R.string.companion_sent, name)
                                runCatching { context.getSystemService(android.content.pm.ShortcutManager::class.java)
                                    .reportShortcutUsed(CompanionShareTargets.id(trusted)) }
                                mutable.update { it.copy(sharedMedia = null, panel = false) }
                            }
                            mutable.update { it.copy(sendingTo = null) }
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (commands === queue) mutable.update { it.copy(connectionError = when (failure.message) {
                    "device_role" -> context.getString(R.string.companion_role_required)
                    "other_network" -> context.getString(R.string.companion_other_network)
                    else -> if (failure is IllegalArgumentException || failure is IllegalStateException) context.getString(R.string.companion_connect_failed)
                        else failure.message.orEmpty().take(240)
                } ) }
                if (failure !is EOFException && failure !is SocketException) debugFailure("sender", failure)
                if (isActive && failure.message != "device_role") notice(when {
                    connected -> R.string.companion_disconnected
                    failure.message == "other_network" -> R.string.companion_other_network
                    else -> R.string.companion_connect_failed
                }, peer.name, error = true)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (offerSession == clientSyncNonce && !mutable.value.backupBusy) rejectBackup("expired")
                    backupResults.remove(clientSyncNonce)
                }
                runCatching { ownedSocket?.close() }
                synchronized(this@CompanionManager) {
                    if (commands === queue) {
                        clientSocket = null
                        backupAck?.complete(false)
                        playbackStartAck?.second?.completeExceptionally(IllegalStateException("Disconnected"))
                        mutable.update { it.copy(lastSeen = if (connected) it.lastSeen + (peer.fingerprint to SystemClock.elapsedRealtime()) else it.lastSeen, online = false, busy = false, connectionStage = null, remote = null, prompt = null, sendingTo = null, enqueueSelection = false,
                            backupBusy = it.backupOffer != null && it.backupBusy, backupStatus = if (it.backupStatus in setOf("pending", "sending")) "failed" else it.backupStatus) }
                    }
                }
                queue.close()
            }
        }
    }

    @Synchronized fun disconnect(clearTarget: Boolean = true) {
        remoteAfterConnect = null
        remoteServiceRequested = false; remoteServiceFailed = false
        context.stopService(Intent(context, CompanionRemotePlaybackService::class.java))
        backupSendJob?.cancel(); backupSendJob = null; backupAck?.complete(false); backupAck = null
        outgoingBackupId = null
        directShareJob?.cancel(); directShareJob = null
        pendingConnection = null
        clientJob?.cancel(); clientJob = null
        // Invalidate the old connection before its finally block can reset a newer send.
        commands.close(); commands = Channel(16)
        runCatching { clientSocket?.close() }; clientSocket = null
        confirmation?.complete(false)
        if (mutable.value.online) notice(R.string.companion_disconnected, mutable.value.target?.name.orEmpty())
        mutable.update { it.copy(target = if (clearTarget) null else it.target, online = false, busy = false, remote = null, sendingTo = null,
            remotePanel = false, settingsPanel = false, nowPlayingPanel = false, enqueueSelection = false, backupBusy = it.backupOffer != null && it.backupBusy,
            backupStatus = if (it.backupStatus in setOf("pending", "sending")) "failed" else it.backupStatus) }
    }
    @Synchronized fun send(command: CompanionFrame): Boolean {
        if (!mutable.value.online || !commands.trySend(command).isSuccess) {
            notice(R.string.companion_connect_failed, error = true); return false
        }
        return true
    }
    /**
     * Phone: re-pairing a TV whose app data was reset gives it a new identity under the same name
     * and address. The old entry can never connect again; drop it unless it is demonstrably alive.
     * Name and address must both match, so two TVs of the same model are never confused.
     */
    private fun dropStaleNamesakes(fresh: CompanionPeer) {
        if (television || fresh.host.isBlank()) return
        mutable.value.paired.filter { it.name == fresh.name && it.host == fresh.host && it.fingerprint != fresh.fingerprint }
            .filter { reachability(it) !in setOf(PeerReachability.CONNECTED, PeerReachability.REACHABLE) }
            .forEach { revoke(it, silent = true) }
    }

    fun revoke(peer: CompanionPeer, silent: Boolean = false) {
        val saved = synchronized(this) {
            val peers = mutable.value.paired.filterNot { it.fingerprint == peer.fingerprint }
            // A revocation must never look done when it was not stored (e.g. storage full): keep
            // the pairing and say so instead of crashing the screen the button sits on.
            prefs.edit().putString("peers", companionJson.encodeToString(peers)).commit().also { ok ->
                if (ok) {
                    mutable.update { it.copy(paired = peers) }
                    if (!television) CompanionShareTargets.publish(context, peers)
                }
            }
        }
        if (!saved) { notice(R.string.companion_revoke_failed, peer.name, error = true); return }
        connectionTransport?.store?.remove(peer.fingerprint)
        scope.launch { runCatching { connectionTransport?.reconcile() } }
        stopSessionWatch(peer.fingerprint)
        sockets.filterValues { it == peer.fingerprint }.keys.forEach { runCatching { it.close() } }
        if (mutable.value.target?.fingerprint == peer.fingerprint) disconnect()
        if (!silent) notice(R.string.companion_revoked, peer.name)
    }

    fun handleIntent(intent: Intent?) {
        if (television || intent == null) return
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "ambienttv" && intent.data?.host == "now-playing") {
            showNowPlaying()
        } else if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "ambienttv" && intent.data?.host == "pair") {
            startPairingFlow(); pair(intent.data.toString())
        } else if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "ambienttv" && intent.data?.host == "devices") {
            showPanel()
        } else if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            if (mutable.value.sendingTo != null) disconnect(clearTarget = false)
            directShareJob?.cancel(); directShareJob = null
            val media = sharedMedia(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
            mutable.update { it.copy(sharedMedia = media, sendingTo = null) }
            if (media == null) notice(R.string.companion_share_unsupported, error = true)
            else {
                showPanel()
                val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
                if (shortcutId != null) {
                    val peer = CompanionShareTargets.peer(shortcutId, mutable.value.paired)
                    if (peer == null) notice(R.string.companion_target_removed, error = true)
                    else sendSharedMediaTo(peer)
                }
            }
        }
    }
    fun sendSharedMediaTo(peer: CompanionPeer) {
        val media = mutable.value.sharedMedia ?: return
        if (television || !foreground || mutable.value.paired.none { it.fingerprint == peer.fingerprint }) {
            notice(R.string.companion_target_removed, error = true); return
        }
        if (mutable.value.sendingTo != null) return
        if (!LocalNetworkAccess.isGranted(context)) {
            pendingConnection = PendingConnection(peer, "", media)
            mutable.update { it.copy(target = peer, sendingTo = peer.name) }
            return
        }
        if (mutable.value.online && mutable.value.target?.fingerprint == peer.fingerprint) {
            mutable.update { it.copy(sendingTo = peer.name) }
            if (!send(CompanionFrame(type = "start", media = media))) mutable.update { it.copy(sendingTo = null) }
            return
        }
        disconnect()
        mutable.update { it.copy(sendingTo = peer.name) }
        discover()
        directShareJob = scope.launch {
            // A receiver uses a fresh port when reopened. Resolve by pinned identity, not TV name.
            val found = withTimeoutOrNull(6_000) { state.first { s -> s.devices.any { it.fingerprint == peer.fingerprint } }
                .devices.first { it.fingerprint == peer.fingerprint } }
            withContext(Dispatchers.Main) {
                ensureActive()
                directShareJob = null
                if (!foreground || mutable.value.paired.none { it.fingerprint == peer.fingerprint }) {
                    mutable.update { it.copy(sendingTo = null) }
                    return@withContext
                }
                // Saved endpoint fallback is still certificate-pinned; never choose another TV.
                connect(found ?: peer, initialMedia = media)
            }
        }
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply { serviceName = "Ambient TV $deviceName"; serviceType = "_ambienttv._tcp."; this.port = port; setAttribute("role", COMPANION_TV); setAttribute("name", deviceName.take(40)); setAttribute("id", identity.pin); setAttribute("auth", fingerprint(identity.certificate.publicKey.encoded)) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (registration !== this) runCatching { nsd.unregisterService(this) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { notice(R.string.companion_unavailable, error = true) }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    fun discover() {
        if (television || !foreground || !LocalNetworkAccess.isGranted(context)) return
        markSearching()
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { stopDiscovery(); notice(R.string.companion_unavailable, error = true) }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                // mDNS names are unique per network; the fingerprint behind one is not repeated in
                // the loss event, so it is remembered at resolve time. Two TVs of the same model
                // no longer vanish together.
                val pin = resolvedServices.remove(info.serviceName)
                mutable.update { state ->
                    state.copy(devices = state.devices.filterNot { peer ->
                        if (pin != null) peer.fingerprint == pin else peer.name == companionDisplayName(info.serviceName, null)
                    })
                }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != "_ambienttv._tcp.") return
                enqueueResolve(info, attempt = 0)
            }
        }
        discovery = listener
        runCatching {
            multicast = context.getSystemService(android.net.wifi.WifiManager::class.java).createMulticastLock("ambient-companion").apply { setReferenceCounted(false); acquire() }
            nsd.discoverServices("_ambienttv._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { stopDiscovery(); notice(R.string.companion_unavailable, error = true) }
    }

    private var searchJob: Job? = null

    /** Shows the search for a few seconds; ends early once a TV turns up, else says none was found. */
    private fun markSearching() {
        searchJob?.cancel()
        mutable.update { it.copy(searching = true, searchEmpty = false) }
        searchJob = scope.launch {
            withTimeoutOrNull(8_000) { state.first { it.devices.isNotEmpty() } }
            mutable.update { it.copy(searching = false, searchEmpty = it.devices.isEmpty()) }
            if (mutable.value.searchEmpty && (mutable.value.panel || pageSearchUsers.get() > 0)) notice(R.string.companion_search_empty, error = true)
        }
    }
    private val resolvedServices = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Before Android 14 NsdManager resolves one service at a time and fails a second request at
    // once (FAILURE_ALREADY_ACTIVE): with two TVs, or a service reported twice, one of them never
    // appeared. Resolves therefore run one after another. API 34 uses ServiceInfoCallback.
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, Int>>()
    private var resolving = false
    private var serviceInfoCallback: NsdManager.ServiceInfoCallback? = null

    private fun enqueueResolve(info: NsdServiceInfo, attempt: Int) {
        synchronized(resolveQueue) {
            if (discovery == null || resolveQueue.size >= 32) return
            resolveQueue.addLast(info to attempt)
        }
        resolveNext()
    }

    private fun finishResolve() {
        serviceInfoCallback?.let { callback ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { nsd.unregisterServiceInfoCallback(callback) }
            }
            serviceInfoCallback = null
        }
        synchronized(resolveQueue) { resolving = false }
        resolveNext()
    }

    private fun resolveNext() {
        val (info, attempt) = synchronized(resolveQueue) {
            if (resolving) return
            resolveQueue.removeFirstOrNull()?.also { resolving = true } ?: return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 5) {
                            scope.launch { delay(250L * (attempt + 1)); enqueueResolve(info, attempt + 1) }
                        }
                        finishResolve()
                    }
                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        addResolved(serviceInfo)
                        finishResolve()
                    }
                    override fun onServiceLost() { finishResolve() }
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                serviceInfoCallback = callback
                nsd.registerServiceInfoCallback(info, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failed: NsdServiceInfo, error: Int) {
                        if (error == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 5) {
                            scope.launch { delay(250L * (attempt + 1)); enqueueResolve(failed, attempt + 1) }
                        }
                        finishResolve()
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        addResolved(resolved)
                        finishResolve()
                    }
                })
            }
        }.onFailure { finishResolve() }
    }

    private fun nsdHost(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        }
        @Suppress("DEPRECATION")
        return info.host
    }

    private fun addResolved(info: NsdServiceInfo) {
        if (television || discovery == null) return
        if (info.attributes["role"]?.toString(Charsets.UTF_8) != COMPANION_TV) return
        val pin = info.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
        val address = nsdHost(info) ?: return
        if (pin == identity.pin || !pin.matches(Regex("[a-f0-9]{64}")) || lan()?.contains(address) != true) return
        // DNS-SD only supplies an endpoint hint; TLS still proves the saved certificate/public-key pin.
        val auth = info.attributes["auth"]?.toString(Charsets.UTF_8).orEmpty()
        val trusted = mutable.value.paired.firstOrNull { it.fingerprint == pin || it.fingerprint == auth || it.authenticationKey.isNotEmpty() && it.authenticationKey == auth }
        val peer = trusted?.copy(host = address.hostAddress!!, port = info.port)
            ?: CompanionPeer(pin, companionDisplayName(info.serviceName, info.attributes["name"]?.toString(Charsets.UTF_8)), address.hostAddress!!, info.port, role = COMPANION_TV)
        resolvedServices[info.serviceName] = peer.fingerprint
        mutable.update { it.copy(devices = (it.devices.filterNot { d -> d.fingerprint == peer.fingerprint } + peer).takeLast(32),
            lastSeen = it.lastSeen + (peer.fingerprint to SystemClock.elapsedRealtime())) }
    }

    private fun stopDiscovery() {
        sessionWatchJobs.keys.toList().forEach(::stopSessionWatch)
        resolvedServices.clear()
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        serviceInfoCallback?.let { callback ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { nsd.unregisterServiceInfoCallback(callback) }
            }
            serviceInfoCallback = null
        }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        searchJob?.cancel(); searchJob = null
        mutable.update { it.copy(devices = emptyList(), searching = false, searchEmpty = false) }
    }
    /** Resumes the exact action that was blocked by Android's Local Network runtime permission. */
    fun onLocalNetworkPermissionGranted() {
        if (!LocalNetworkAccess.isGranted(context)) return
        if (television) {
            if (pendingPairingOpen) openPairing() else if (mutable.value.enabled) startServer()
            pendingConnection?.let { pending -> pendingConnection = null; connect(pending.peer, pending.invitation, pending.initialMedia) }
            return
        }
        startServer()
        discover()
        val pending = pendingConnection ?: return
        pendingConnection = null
        connect(pending.peer, pending.invitation, pending.initialMedia)
    }
    fun notice(message: Int, name: String = "", error: Boolean = false) {
        AppNotifications.post(AppNotice(key = "companion:$message", message = message, args = listOf(name),
            kind = NoticeKind.PLAYBACK, phase = if (error) NoticePhase.ERROR else NoticePhase.INFO,
            interruptPlayback = companionNoticeInterruptsPlayback(message, error)))
    }

    private fun debugFailure(side: String, failure: Exception) {
        // Never log exception messages or wire frames: parser errors can contain invitations.
        diagnosticFailure = "$side: ${failure.javaClass.simpleName} at ${failure.stackTrace.firstOrNull()}"
        if (com.ambient.tv.BuildConfig.DEBUG) android.util.Log.d("AmbientCompanion", diagnosticFailure)
    }
}

/**
 * Routine connection churn (a phone screen turning off disconnects it) must not cover a playing
 * film. Errors, remote control, remote starts and incoming transfers still show during playback.
 */
internal fun companionNoticeInterruptsPlayback(message: Int, error: Boolean): Boolean =
    error || message !in setOf(
        R.string.companion_connected,
        R.string.companion_disconnected,
        R.string.companion_control_stopped,
        R.string.companion_revoked,
        R.string.companion_sent,
        R.string.companion_backup_sent,
    )

private const val MAX_PAIR_ATTEMPTS = 5
private const val LAN_RECHECK_MS = 1_000L
private const val KEY_SERVER_PORT = "server_port"
private class CompanionBackupRejected(val reason: String) : IllegalStateException(reason)
private const val KEY_LAST_APPLIED = "last_settings_applied_at"
/** Time for TLS, the challenge and the auth frame before an unauthenticated socket is closed. */
private const val AUTH_DEADLINE_MS = 10_000L

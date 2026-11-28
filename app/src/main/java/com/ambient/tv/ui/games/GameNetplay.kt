package com.ambient.tv.ui.games

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A device in the local network hosting the same game. */
data class NetplayPeer(val name: String, val host: String, val port: Int)

sealed interface NetplayState {
    data object Idle : NetplayState
    /** Waiting for a second player; [name] is how others see this device. */
    data class Hosting(val name: String) : NetplayState
    /** No game state is disclosed until the host explicitly accepts this peer. */
    data class Approval(val peer: String) : NetplayState
    data object Joining : NetplayState
    /** Playing together: this device is player [localPlayer] (1 host, 2 guest). */
    data class Connected(val peer: String, val localPlayer: Int) : NetplayState
    data class Failed(val reason: NetplayFailure) : NetplayState
}

enum class NetplayFailure { NETWORK, OTHER_GAME, OTHER_CORE, CLOSED, STATE, REJECTED }

/**
 * Two-player netplay between two Ambient TV devices in the same network, as lockstep: both run the
 * same core build from the host's state, every frame with both players' buttons, each device's own
 * input [DELAY_FRAMES] frames ahead so the network round trip stays hidden. The host is player 1,
 * the guest player 2. Buttons, both analog sticks and trigger pressures are shared.
 *
 * Found by NSD (mDNS) in the local network; only hosts of the same game file and core build appear.
 */
class GameNetplay(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The local player's input: buttons (RetroPad bitmask) and sticks. */
    private val localInput: () -> NetplayInput,
) {
    var state by mutableStateOf<NetplayState>(NetplayState.Idle)
        private set
    var peers by mutableStateOf<List<NetplayPeer>>(emptyList())
        private set
    /** True while the other device does not send (its menu is open, or it is slow). */
    var waiting by mutableStateOf(false)
        private set
    /** The exchanged state is frozen; local controls must not resume or alter it. */
    var synchronizing by mutableStateOf(false)
        private set

    private val nsd: NsdManager? get() = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var job: Job? = null
    private var view: GLRetroView? = null
    private val sender = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(SEND_QUEUE_LIMIT))
    private var pendingApproval: CompletableDeferred<Boolean>? = null

    fun approvePeer() { pendingApproval?.complete(true) }
    fun rejectPeer() { pendingApproval?.complete(false) }

    // Lockstep, touched on the core's thread (tick) and the reader thread.
    private val remote = ConcurrentHashMap<Int, NetplayInput>()
    private val local = ConcurrentHashMap<Int, NetplayInput>()
    @Volatile private var frame = 0
    @Volatile private var sentUpTo = -1
    @Volatile private var localPort = 0
    @Volatile private var stalledSince = 0L

    val active: Boolean get() = state is NetplayState.Connected
    val busy: Boolean get() = state !is NetplayState.Idle && state !is NetplayState.Failed

    val deviceName: String = Build.MODEL.ifBlank { "Ambient TV" }

    /** Hosts [game]: announces it and waits for one player. */
    fun host(view: GLRetroView, identity: NetplayIdentity) {
        stop()
        if (!GameSerializationPolicy(RetroCoreRuntime.core.getSerializationQuirks()).frameExact) {
            state = NetplayState.Failed(NetplayFailure.STATE)
            return
        }
        this.view = view
        state = NetplayState.Hosting(deviceName)
        job = scope.launch(Dispatchers.IO) {
            try {
                val listening = ServerSocket(0).also {
                    it.soTimeout = HOST_ACCEPT_TIMEOUT_MS
                    server = it
                }
                register(listening.localPort, identity)
                val client = listening.accept()
                listening.close()
                server = null
                unregister()
                client.tcpNoDelay = true
                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                socket = client
                val input = DataInputStream(client.getInputStream().buffered())
                val output = DataOutputStream(client.getOutputStream().buffered())
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("protocol")
                val peerRom = input.readUTF()
                val peerCore = input.readUTF()
                val peerName = input.readUTF()
                var refusal = when {
                    peerRom != identity.rom -> NetplayFailure.OTHER_GAME
                    peerCore != identity.core -> NetplayFailure.OTHER_CORE
                    else -> null
                }
                if (refusal == null) {
                    val approval = CompletableDeferred<Boolean>()
                    withContext(Dispatchers.Main) {
                        pendingApproval = approval
                        state = NetplayState.Approval(peerName)
                    }
                    val accepted = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { approval.await() } == true
                    withContext(Dispatchers.Main) {
                        if (pendingApproval === approval) pendingApproval = null
                    }
                    if (!accepted) refusal = NetplayFailure.REJECTED
                }
                output.writeInt(MAGIC)
                output.writeInt(refusal?.ordinal ?: -1)
                if (refusal != null) {
                    output.flush()
                    throw NetplayRefused(refusal)
                }
                // The guest starts from exactly this moment of the game.
                withContext(Dispatchers.Main) {
                    synchronizing = true
                    view.suppressAutoResume = true
                    view.pauseEmulation()
                }
                val serializeSize = view.getSerializeSize()
                if (serializeSize !in 1L..MAX_STATE.toLong()) throw IOException("state size")
                val bytes = view.serializeState()
                if (bytes.size !in 1..MAX_STATE) throw IOException("state size")
                output.writeInt(bytes.size)
                output.write(bytes)
                output.writeUTF(deviceName)
                output.flush()
                begin(view, input, output, port = 0, peer = peerName)
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    /** Looks for hosts of the same game; [peers] fills in. */
    fun discover(identity: NetplayIdentity) {
        stopDiscovery()
        peers = emptyList()
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) {
                scope.launch { peers = peers.filterNot { it.name == service.serviceName } }
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val rom = info.attributes["rom"]?.let { String(it) }
                            val host = info.host?.hostAddress ?: return
                            if (rom != identity.rom) return
                            val peer = NetplayPeer(info.serviceName, host, info.port)
                            scope.launch { peers = (peers.filterNot { it.name == peer.name } + peer) }
                        }
                    })
                }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopDiscovery() {
        discovery?.let { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        discovery = null
    }

    /** Joins [peer] as player 2; the game continues from the host's state. */
    fun join(view: GLRetroView, peer: NetplayPeer, identity: NetplayIdentity) {
        stop()
        if (!GameSerializationPolicy(RetroCoreRuntime.core.getSerializationQuirks()).frameExact) {
            state = NetplayState.Failed(NetplayFailure.STATE)
            return
        }
        this.view = view
        state = NetplayState.Joining
        job = scope.launch(Dispatchers.IO) {
            try {
                val client = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(peer.host, peer.port), 10_000)
                    soTimeout = HANDSHAKE_TIMEOUT_MS
                }
                socket = client
                val input = DataInputStream(client.getInputStream().buffered())
                val output = DataOutputStream(client.getOutputStream().buffered())
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(identity.rom)
                output.writeUTF(identity.core)
                output.writeUTF(deviceName)
                output.flush()
                if (input.readInt() != MAGIC) throw IOException("protocol")
                val refusal = input.readInt()
                if (refusal >= 0) throw NetplayRefused(NetplayFailure.entries.getOrElse(refusal) { NetplayFailure.CLOSED })
                val size = input.readInt()
                if (size !in 1..MAX_STATE) throw IOException("state size")
                val bytes = ByteArray(size).also { input.readFully(it) }
                val hostName = input.readUTF()
                withContext(Dispatchers.Main) {
                    synchronizing = true
                    view.suppressAutoResume = true
                    view.pauseEmulation()
                }
                if (!view.unserializeState(bytes)) throw NetplayRefused(NetplayFailure.STATE)
                begin(view, input, output, port = 1, peer = hostName)
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private suspend fun begin(view: GLRetroView, input: DataInputStream, output: DataOutputStream, port: Int, peer: String) {
        remote.clear()
        local.clear()
        frame = 0
        localPort = port
        // The first frames run without input on both sides: that is the delay.
        for (f in 0 until DELAY_FRAMES) {
            remote[f] = NetplayInput.NONE
            local[f] = NetplayInput.NONE
        }
        sentUpTo = DELAY_FRAMES - 1
        stalledSince = 0L
        withContext(Dispatchers.Main) {
            view.frameSpeed = 1
            view.rewindEnabled = false
            RetroCoreRuntime.core.setNetplayActive(true)
            view.netplayTick = { tick(view, output) }
            synchronizing = false
            state = NetplayState.Connected(peer, port + 1)
            if (!view.suppressAutoResume) view.resumeEmulation()
        }
        // Stop a stalled or malicious peer from retaining socket and session indefinitely.
        socket?.soTimeout = ACTIVE_READ_TIMEOUT_MS
        // Reader: the other player's buttons until the connection ends.
        try {
            while (true) {
                when (input.readByte().toInt()) {
                    PACKET_INPUT -> {
                        val f = input.readInt()
                        if (!acceptableFrame(f, frame) || (!remote.containsKey(f) && remote.size >= MAX_FRAME_AHEAD)) {
                            throw IOException("Invalid or excessive netplay frame $f")
                        }
                        remote[f] = NetplayInput.read(input)
                    }
                    PACKET_BYE -> break
                    else -> throw IOException("packet")
                }
            }
            withContext(Dispatchers.Main) { end(NetplayFailure.CLOSED) }
        } catch (error: Exception) {
            if (currentCoroutineContext().isActive) withContext(Dispatchers.Main) { end(NetplayFailure.CLOSED) }
        }
    }

    /** One display frame on the core's thread: send own input ahead, step when both are there. */
    private fun tick(view: GLRetroView, output: DataOutputStream) {
        val target = frame + DELAY_FRAMES
        if (sentUpTo < target) {
            val state = localInput()
            local[target] = state
            sentUpTo = target
            runCatching {
                sender.execute {
                    runCatching {
                        synchronized(output) {
                            output.writeByte(PACKET_INPUT)
                            output.writeInt(target)
                            state.write(output)
                            output.flush()
                        }
                    }.onFailure {
                        scope.launch(Dispatchers.Main) { end(NetplayFailure.NETWORK) }
                    }
                }
            }.onFailure {
                scope.launch(Dispatchers.Main) { end(NetplayFailure.NETWORK) }
            }
        }
        val theirs = remote[frame]
        val mine = local[frame]
        if (theirs == null || mine == null) {
            val now = System.nanoTime()
            if (stalledSince == 0L) stalledSince = now
            if (now - stalledSince > WAIT_NOTICE_NS && !waiting) scope.launch { waiting = true }
            RetroCoreRuntime.core.renderFrameOnly()
            return
        }
        if (stalledSince != 0L) {
            stalledSince = 0L
            scope.launch { waiting = false }
        }
        val host = if (localPort == 0) mine else theirs
        val guest = if (localPort == 0) theirs else mine
        host.apply(0)
        guest.apply(1)
        LibretroDroid.stepForNetplay(view)
        remote.remove(frame - 1)
        local.remove(frame - 1)
        frame++
    }

    private fun end(reason: NetplayFailure?) {
        view?.let { view ->
            view.netplayTick = null
            runCatching { RetroCoreRuntime.core.setNetplayActive(false) }
        }
        closeSockets()
        waiting = false
        synchronizing = false
        state = if (reason == null) NetplayState.Idle else NetplayState.Failed(reason)
    }

    private suspend fun fail(error: Exception) {
        // Stopped on purpose (stop() closes the sockets): not a failure.
        if (error is kotlinx.coroutines.CancellationException || !currentCoroutineContext().isActive) return
        Timber.w(error, "Netplay failed")
        val reason = (error as? NetplayRefused)?.reason ?: NetplayFailure.NETWORK
        withContext(Dispatchers.Main) {
            unregister()
            end(reason)
        }
    }

    /** Ends hosting, searching or playing together; the game continues alone. */
    fun stop() {
        pendingApproval?.complete(false)
        pendingApproval = null
        job?.cancel()
        job = null
        stopDiscovery()
        unregister()
        end(null)
    }

    fun release() {
        stop()
        sender.shutdown()
    }

    private fun register(port: Int, identity: NetplayIdentity) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "$deviceName · ${identity.title}".take(60)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("rom", identity.rom)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun unregister() {
        registration?.let { listener -> runCatching { nsd?.unregisterService(listener) } }
        registration = null
    }

    private fun closeSockets() {
        runCatching { server?.close() }
        server = null
        val open = socket
        socket = null
        if (open != null) runCatching { open.close() }
    }

    private class NetplayRefused(val reason: NetplayFailure) : IOException(reason.name)

    companion object {
        const val SERVICE_TYPE = "_ambienttv-np._tcp."
        const val DELAY_FRAMES = 3
        private const val MAGIC = 0x41544e50 // "ATNP"
        private const val VERSION = 5
        private const val PACKET_INPUT = 1
        private const val PACKET_BYE = 2
        private const val MAX_STATE = 64 * 1024 * 1024
        private const val MAX_FRAME_AHEAD = 64
        private const val SEND_QUEUE_LIMIT = 128
        private const val HOST_ACCEPT_TIMEOUT_MS = 5 * 60_000
        private const val HANDSHAKE_TIMEOUT_MS = 40_000
        private const val APPROVAL_TIMEOUT_MS = 30_000L
        private const val ACTIVE_READ_TIMEOUT_MS = 30_000

        /** Only a short, bounded window ahead of the frame currently being emulated is legal. */
        internal fun acceptableFrame(incoming: Int, current: Int): Boolean =
            incoming >= 0 && incoming >= current &&
                incoming.toLong() <= current.toLong() + MAX_FRAME_AHEAD
        private const val WAIT_NOTICE_NS = 500_000_000L

        /**
         * Hash the WHOLE ROM rather than the first/last ranges. Two disc images with
         * different middle sectors must never be mistaken for identical games.
         * Called on Dispatchers.IO by the Netplay panel; never on the render thread.
         */
        fun identity(file: File, coreStamp: String, title: String): NetplayIdentity =
            NetplayIdentity(sha256(file), coreStamp, title)

        /** Full streaming SHA-256; a fixed-size buffer also handles multi-gigabyte games. */
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).buffered(256 * 1024).use { stream ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /**
         * Two players need the same core binary AND rendering/core options/known BIOS.
         * The guest receives the host state, but differing emulation settings would
         * still desynchronize subsequent frames.
         */
        fun configurationStamp(
            coreStamp: String,
            vulkan: Boolean,
            options: Map<String, String>,
            firmware: List<File>,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(byteArrayOf(
                    (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(), bytes.size.toByte(),
                ))
                digest.update(bytes)
            }
            add("ambient-netplay-v5")
            add(coreStamp)
            add(if (vulkan) "vulkan" else "gles")
            options.toSortedMap().forEach { (key, value) -> add(key); add(value) }
            firmware.sortedBy { it.name }.forEach { file ->
                add(file.name)
                add(if (file.isFile) sha256(file) else "missing")
            }
            return "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

data class NetplayIdentity(val rom: String, val core: String, val title: String)

/** One frame: buttons, signed stick axes and unsigned L2/R2 pressure (0…0x7fff). */
data class NetplayInput(
    val buttons: Int, val leftX: Int, val leftY: Int, val rightX: Int, val rightY: Int,
    val leftTrigger: Int = if (buttons and (1 shl 12) != 0) 0x7fff else 0,
    val rightTrigger: Int = if (buttons and (1 shl 13) != 0) 0x7fff else 0,
) {
    fun write(out: DataOutputStream) {
        out.writeInt(buttons)
        out.writeShort(leftX)
        out.writeShort(leftY)
        out.writeShort(rightX)
        out.writeShort(rightY)
        out.writeShort(leftTrigger.coerceIn(0, MAX))
        out.writeShort(rightTrigger.coerceIn(0, MAX))
    }

    /** Hands this frame to the core as player [port]. */
    fun apply(port: Int) {
        RetroCoreRuntime.core.setInputPortSnapshot(port, buttons, axis(leftX), axis(leftY), axis(rightX), axis(rightY),
            pressure(leftTrigger), pressure(rightTrigger))
    }

    companion object {
        val NONE = NetplayInput(0, 0, 0, 0, 0)
        private const val MAX = 0x7fff

        fun read(input: DataInputStream): NetplayInput {
            val buttons = input.readInt()
            val leftX = input.readShort().toInt()
            val leftY = input.readShort().toInt()
            val rightX = input.readShort().toInt()
            val rightY = input.readShort().toInt()
            val leftTrigger = input.readUnsignedShort()
            val rightTrigger = input.readUnsignedShort()
            if (buttons !in 0..0xffff || leftTrigger > MAX || rightTrigger > MAX) throw IOException("Invalid netplay input")
            return NetplayInput(buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger)
        }

        fun axis(value: Int): Float = (value.coerceIn(-MAX, MAX) / MAX.toFloat())
        private fun pressure(value: Int): Float = value.coerceIn(0, MAX) / MAX.toFloat()
    }
}

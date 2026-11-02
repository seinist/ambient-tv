package com.ambient.tv.companion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Provider items use semantic references. Explicitly shared media links use kind=url. */
@Serializable
data class CompanionMedia(
    val kind: String,
    val id: String,
    val source: String = "",
    val restart: Boolean = false,
    val trailer: Boolean = false,
    val mediaIndex: Int? = null,
    val offsetId: String = "",
    val collectionId: String = "",
    val collectionSort: String = "",
)

@Serializable
data class CompanionFrame(
    val version: Int = 1,
    val type: String,
    /** Authenticated device role; absent in legacy versions. */
    val role: String = "",
    val nonce: String = "",
    val sequence: Long = 0,
    val name: String = "",
    val key: String = "",
    val signature: String = "",
    val invitation: String = "",
    val media: CompanionMedia? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val title: String = "",
    val error: String = "",
    val input: CompanionInput? = null,
    val controller: String = "",
    val inputGranted: Boolean = false,
    val transferId: String = "",
    val payload: String = "",
    val total: Int = 0,
    val digest: String = "",
    val index: Int = 0,
    val mode: String = "accounts",
    val credentials: Boolean = false,
    /** Login sessions (tokens) in a settings transfer; null (older senders) means [credentials]. */
    val sessions: Boolean? = null,
    val backupStatus: String = "",
    /** Nullable for compatibility with older TVs which only report a title and playing flag. */
    val active: Boolean? = null,
    val playbackState: String = "",
    val playbackSessionId: Long = 0,
    val subtitle: String = "",
    val parentIds: List<String> = emptyList(),
    val isLive: Boolean = false,
    val canSeek: Boolean? = null,
    /** Small JPEG, never a provider URL, auth header or streaming token. */
    val artwork: String = "",
    val playWhenReady: Boolean? = null,
    /** Empty means a legacy receiver: basic controls retain their old fallback. */
    val capabilities: List<String> = emptyList(),
    val options: List<CompanionOptionGroup> = emptyList(),
    val volume: Int? = null,
    val volumeMax: Int = 0,
    val queue: List<CompanionQueueItem> = emptyList(),
    val queueTotal: Int = 0,
    val queueRevision: Long = 0,
    val queueOffset: Int = 0,
    val optionGroup: String = "",
    val optionKey: String = "",
    val shuffle: Boolean? = null,
    val repeatMode: Int? = null,
    /** Filled locally by the sender with the verified peer pin, never trusted from the wire. */
    val receiverId: String = "",
    /** Correlates a requested start with receiver-confirmed playback, independently of transfers. */
    val startRequestId: String = "",
    val startStatus: String = "",
    /** Paired-only sync listener; host always comes from the authenticated socket. */
    val syncPort: Int = 0,
    /** Optional NIC address, disclosed only after peer authentication. */
    val networkMac: String = "",
    /** Settings-only return channel inside the authenticated request/response stream. */
    val syncReturn: CompanionSyncFrame? = null,
    val syncAck: Long = 0,
    val syncError: String = "",
    val syncResultId: String = "",
    val syncResult: String = "",


)

@Serializable
data class CompanionOption(val key: String, val label: String, val selected: Boolean = false)
@Serializable
data class CompanionOptionGroup(val kind: String, val options: List<CompanionOption>)
@Serializable
data class CompanionQueueItem(val token: String, val media: CompanionMedia? = null, val title: String = "", val kind: String = "")

@Serializable
data class CompanionPeer(val fingerprint: String, val name: String, val host: String = "", val port: Int = 0,
    /** Learned only from the pinned server certificate, for authenticating a reverse connection. */
    val authenticationKey: String = "",
    val role: String = "",
)

data class CompanionInvitation(val host: String, val port: Int, val fingerprint: String, val token: String) {
    fun link() = "ambienttv://pair?host=$host&port=$port&key=$fingerprint&token=$token"
    companion object {
        fun parse(value: String): CompanionInvitation {
            require(value.length <= 512)
            val uri = URI(value.trim())
            require(uri.scheme == "ambienttv" && uri.host == "pair" && uri.fragment == null)
            val fields = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val host = fields.getValue("host")
            require(host.matches(Regex("[0-9.]{7,15}"))) // Literal LAN address only, no DNS / URL indirection.
            require(isPrivateV4(host))
            val port = fields.getValue("port").toInt().also { require(it in 1..65535) }
            val pin = fields.getValue("key").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val token = fields.getValue("token").also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) }
            return CompanionInvitation(host, port, pin, token)
        }
    }
}

internal fun isPrivateV4(host: String): Boolean {
    val p = host.split('.').map { it.toIntOrNull() ?: return false }
    if (p.size != 4 || p.any { it !in 0..255 }) return false
    return p[0] == 10 || p[0] == 192 && p[1] == 168 || p[0] == 172 && p[1] in 16..31
}

/**
 * TV and phone are updated independently. Unknown fields from a newer peer are ignored instead of
 * failing every frame; authentication, sequence and command validation do not depend on them,
 * and unknown command types are still rejected as "unsupported" by the receiver.
 */
internal val companionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Short pairing code for typing on the phone. The alphabet leaves out every character that is
 * easy to mix up on a TV across the room (I, L, O, U, 0, 1), so 30 characters remain: eight of
 * them are ~39 bits, and the TV ends the pairing window after a few wrong tries, which is what
 * actually rules out guessing.
 */
internal const val PAIR_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
internal const val PAIR_CODE_LENGTH = 8

internal fun randomPairCode(random: SecureRandom = SecureRandom()): String =
    (1..PAIR_CODE_LENGTH).map { PAIR_CODE_ALPHABET[random.nextInt(PAIR_CODE_ALPHABET.length)] }.joinToString("")

/**
 * What the user typed → the form the TV compares: upper case, and only characters the alphabet
 * contains (so the separator, spaces and a mistyped look-alike drop out). A code that loses a
 * character this way no longer has [PAIR_CODE_LENGTH] and is rejected as invalid rather than
 * silently compared against something else.
 */
internal fun normalizePairCode(value: String): String =
    value.take(64).uppercase().filter { it in PAIR_CODE_ALPHABET }

internal fun formatPairCode(code: String): String = code.chunked(4).joinToString("-")

internal fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
internal fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal const val COMPANION_TV = "tv"
internal const val COMPANION_HANDHELD = "handheld"

/** Unknown legacy roles require an update; never infer a role from a device name. */
internal fun companionRolesAllowed(serverRole: String, clientRole: String): Boolean =
    serverRole == COMPANION_TV && clientRole == COMPANION_HANDHELD ||
        serverRole == COMPANION_HANDHELD && clientRole == COMPANION_TV

internal fun authTranscript(nonce: String, serverPin: String, clientPin: String,
    clientRole: String = "", serverRole: String = ""): ByteArray =
    ("AmbientTV Companion v1\nauth\n$nonce\n$serverPin\n$clientPin" +
        if (clientRole.isEmpty() && serverRole.isEmpty()) "" else "\n$clientRole\n$serverRole").toByteArray(Charsets.UTF_8)
internal fun comparisonCode(nonce: String, serverPin: String, clientPin: String): String =
    fingerprint(authTranscript(nonce, serverPin, clientPin)).take(8).uppercase().chunked(4).joinToString(" ")
internal fun safeDeviceName(name: String) = name.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }.take(40).ifBlank { "Android" }

/** Length framing prevents an unauthenticated socket from allocating unbounded lines. */
internal fun DataInputStream.readFrame(): CompanionFrame {
    val size = readInt()
    require(size in 1..16_384)
    val bytes = ByteArray(size)
    readFully(bytes)
    return companionJson.decodeFromString<CompanionFrame>(bytes.toString(Charsets.UTF_8)).also { require(it.version == 1) }
}
internal fun DataOutputStream.writeFrame(frame: CompanionFrame) {
    val bytes = companionJson.encodeToString(frame).toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..16_384)
    writeInt(bytes.size); write(bytes); flush()
}

/** Per TLS connection; reconnects use a fresh unpredictable nonce and never retry a command. */
internal class CompanionSequence(private val nonce: String) {
    private var last = 0L
    fun accept(frame: CompanionFrame) {
        require(frame.nonce == nonce && frame.sequence == last + 1 && frame.sequence > 0)
        last = frame.sequence
    }
}

/**
 * Bounds unauthenticated TLS work without allowing one noisy LAN client to lock
 * every other companion out of pairing or reconnecting.
 */
internal class CompanionHandshakeLimiter(
    private val limit: Int = 20,
    private val windowMs: Long = 60_000,
    private val maxPeers: Int = 64,
) {
    private val windows = LinkedHashMap<String, Pair<Long, Int>>()

    init { require(limit > 0 && windowMs > 0 && maxPeers > 0) }

    @Synchronized fun admit(address: String, now: Long): Boolean {
        val previous = windows[address]
        val start = previous?.first?.takeIf { now - it <= windowMs } ?: now
        val attempts = if (start == previous?.first) previous.second + 1 else 1
        windows[address] = start to attempts
        while (windows.size > maxPeers) windows.entries.iterator().also { it.next(); it.remove() }
        return attempts <= limit
    }
}

/**
 * Connection budget of the receiver. Unauthenticated sockets (TLS, challenge, auth frame) share
 * a few slots, at most [perHost] from one address, and only an authenticated one moves on to a
 * session slot. One LAN client can then no longer occupy every slot and lock paired devices out.
 */
internal class CompanionAdmission(
    private val pending: Int = 4,
    private val perHost: Int = 2,
    private val sessions: Int = 8,
) {
    private var pendingCount = 0
    private var sessionCount = 0
    private val pendingByHost = HashMap<String, Int>()

    init { require(pending > 0 && perHost > 0 && sessions > 0) }

    inner class Ticket internal constructor(private val host: String) {
        private var state = PENDING

        /** Authenticated: trades the unauthenticated slot for a session slot; false when none is free. */
        fun promote(): Boolean {
            synchronized(this@CompanionAdmission) {
                if (state != PENDING) return state == SESSION
                leavePending(host)
                if (sessionCount >= sessions) {
                    state = RELEASED
                    return false
                }
                sessionCount++
                state = SESSION
                return true
            }
        }

        /** Frees whatever this connection holds; repeated calls are harmless. */
        fun release() {
            synchronized(this@CompanionAdmission) {
                when (state) {
                    PENDING -> leavePending(host)
                    SESSION -> sessionCount--
                }
                state = RELEASED
            }
        }
    }

    /** A ticket for a new unauthenticated socket from [host], or null to close it right away. */
    @Synchronized fun admit(host: String): Ticket? {
        val fromHost = pendingByHost[host] ?: 0
        if (pendingCount >= pending || fromHost >= perHost) return null
        pendingCount++
        pendingByHost[host] = fromHost + 1
        return Ticket(host)
    }

    private fun leavePending(host: String) {
        pendingCount--
        val left = (pendingByHost[host] ?: 1) - 1
        if (left <= 0) pendingByHost.remove(host) else pendingByHost[host] = left
    }

    private companion object {
        const val PENDING = 0
        const val SESSION = 1
        const val RELEASED = 2
    }
}

internal fun sharedYouTubeId(text: String): String? {
    val candidates = Regex("https://[^\\s<>]+").findAll(text.take(4096))
    return candidates.mapNotNull { match -> runCatching {
        val uri = URI(match.value)
        require(uri.userInfo == null && uri.port == -1)
        val id = when (uri.host?.lowercase()) {
            "youtu.be" -> uri.path.removePrefix("/")
            "youtube.com", "www.youtube.com", "m.youtube.com" -> when {
                uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substringAfter('=')
                uri.path.startsWith("/shorts/") -> uri.path.substringAfter("/shorts/")
                uri.path.startsWith("/live/") -> uri.path.substringAfter("/live/")
                else -> null
            }
            else -> null
        }
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }.getOrNull() }.firstOrNull()
}

/** Exactly one URL per share. Never guess which of several links the user meant to play. */
internal fun sharedMedia(text: String): CompanionMedia? {
    if (text.length > 8192 || text.any { it == '\u0000' }) return null
    val urls = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()
    if (urls.size != 1) return null
    val url = urls.single()
    sharedYouTubeId(url)?.let { return CompanionMedia("youtube", it) }
    return com.ambient.tv.data.shared.SharedMediaLink.parse(url)?.let { CompanionMedia("url", it.url) }
}

/**
 * Sums two consecutive touchpad frames of the same kind (move or scroll). Returns null when they
 * cannot be merged: another frame type, or a sum outside the protocol's -1..1 range.
 */
internal fun coalesceCompanionInput(first: CompanionFrame, next: CompanionFrame): CompanionFrame? {
    val a = first.input ?: return null
    val b = next.input ?: return null
    if (first.type != "input" || next.type != "input" || a.action != b.action || a.action !in setOf("move", "scroll")) return null
    val x = a.x + b.x
    val y = a.y + b.y
    if (x !in -1f..1f || y !in -1f..1f) return null
    return first.copy(input = a.copy(x = x, y = y))
}

/** Data imports and remote controls are TV-only; neither role supports pulling TV data. */
internal fun companionCommandAllowed(television: Boolean, type: String): Boolean = type != "backup_request" && (television || type in setOf("status", "input_release"))

/** Non-recursive envelope: a peer cannot tunnel playback/input commands through settings sync. */
@Serializable
 data class CompanionSyncFrame(
    val sequence: Long,
    val type: String,
    val transferId: String,
    val total: Int = 0,
    val digest: String = "",
    val mode: String = "accounts",
    val credentials: Boolean = false,
    val index: Int = 0,
    val payload: String = "",
    val sessions: Boolean? = null,
) {
    fun command(): CompanionFrame {
        require(sequence > 0 && type in setOf("backup_begin", "backup_chunk", "backup_finish", "backup_cancel"))
        return CompanionFrame(type = type, transferId = transferId, total = total, digest = digest,
            mode = mode, credentials = credentials, index = index, payload = payload, sessions = sessions)
    }
    companion object {
        fun from(sequence: Long, frame: CompanionFrame) = CompanionSyncFrame(sequence, frame.type,
            frame.transferId, frame.total, frame.digest, frame.mode, frame.credentials, frame.index, frame.payload,
            frame.sessions)
    }
}

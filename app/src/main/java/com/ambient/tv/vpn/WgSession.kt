package com.ambient.tv.vpn

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

internal class WgSession(
    private val staticPrivate: ByteArray,
    peerStaticPublic: ByteArray,
    presharedKey: ByteArray = ByteArray(32),
    /** Monotonic clock for key and cookie lifetimes; injectable for tests. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val peerPublic = peerStaticPublic.copyOf()
    private val psk = presharedKey.copyOf(32)
    private val staticPublic = WgCrypto.x25519(staticPrivate)
    private val staticStatic = WgCrypto.x25519(staticPrivate, peerPublic)
    private val random = SecureRandom()
    private val lock = Any()

    private var chain = ByteArray(32)
    private var hash = ByteArray(32)
    private var localEphemeral = ByteArray(32)
    private var handshakeLocalIndex = 0
    private var handshakeRemoteIndex = 0
    private var state = State.ZERO
    private var currentKeypair: Keypair? = null
    private var previousKeypair: Keypair? = null
    /** Responder keypair: only used once the initiator proved it has it (first transport packet). */
    private var nextKeypair: Keypair? = null
    private var cookie: ByteArray? = null
    private var cookieAtMs = 0L
    /** mac1 of the last handshake message we sent: the AAD of a cookie reply to it. */
    private var lastSentMac1: ByteArray? = null
    private var lastPeerTimestamp: ByteArray? = null

    val established: Boolean get() = synchronized(lock) { currentKeypair?.let { !it.expired(clockMs()) } == true }

    fun initiation(nowMs: Long = System.currentTimeMillis()): ByteArray = synchronized(lock) {
        localEphemeral = ByteArray(32).also { random.nextBytes(it) }
        hash = WgCrypto.blake2s(WgCrypto.initialHash + peerPublic)
        chain = WgCrypto.initialChain.copyOf()
        val ephemeralPublic = WgCrypto.x25519(localEphemeral)
        chain = WgCrypto.kdf1(chain, ephemeralPublic)
        hash = WgCrypto.blake2s(hash + ephemeralPublic)
        val staticSecret = WgCrypto.x25519(localEphemeral, peerPublic)
        val (nextChain, staticKey) = WgCrypto.kdf2(chain, staticSecret)
        chain = nextChain
        val encryptedStatic = WgCrypto.seal(staticKey, WgCrypto.zeroNonce, hash, staticPublic)
        hash = WgCrypto.blake2s(hash + encryptedStatic)
        val (timestampChain, timestampKey) = WgCrypto.kdf2(chain, staticStatic)
        chain = timestampChain
        val encryptedTime = WgCrypto.seal(timestampKey, WgCrypto.zeroNonce, hash, WgCrypto.tai64n(nowMs))
        hash = WgCrypto.blake2s(hash + encryptedTime)
        handshakeLocalIndex = randomIndex()
        state = State.INITIATION_CREATED
        ByteArray(148).also { msg ->
            putInt(msg, 0, TYPE_INITIATION)
            putInt(msg, 4, handshakeLocalIndex)
            System.arraycopy(ephemeralPublic, 0, msg, 8, 32)
            System.arraycopy(encryptedStatic, 0, msg, 40, 48)
            System.arraycopy(encryptedTime, 0, msg, 88, 28)
            writeMacs(msg, peerPublic)
        }
    }

    /** A malformed or forged message (e.g. a low-order point) is rejected, never thrown. */
    fun consumeInitiation(msg: ByteArray): Boolean = synchronized(lock) {
        try { consumeInitiationLocked(msg) } catch (_: Exception) { false }
    }

    private fun consumeInitiationLocked(msg: ByteArray): Boolean {
        if (msg.size != 148 || intAt(msg, 0) != TYPE_INITIATION || !mac1Valid(msg, staticPublic)) return false
        var localHash = WgCrypto.blake2s(WgCrypto.initialHash + staticPublic)
        var localChain = WgCrypto.kdf1(WgCrypto.initialChain, msg.copyOfRange(8, 40))
        localHash = WgCrypto.blake2s(localHash + msg.copyOfRange(8, 40))
        val ss = WgCrypto.x25519(staticPrivate, msg.copyOfRange(8, 40))
        val (chainAfterStatic, key) = WgCrypto.kdf2(localChain, ss)
        localChain = chainAfterStatic
        val peerIdentity = WgCrypto.open(key, WgCrypto.zeroNonce, localHash, msg.copyOfRange(40, 88)) ?: return false
        if (!peerIdentity.contentEquals(peerPublic)) return false
        localHash = WgCrypto.blake2s(localHash + msg.copyOfRange(40, 88))
        val (chainAfterIdentity, timeKey) = WgCrypto.kdf2(localChain, staticStatic)
        localChain = chainAfterIdentity
        val timestamp = WgCrypto.open(timeKey, WgCrypto.zeroNonce, localHash, msg.copyOfRange(88, 116)) ?: return false
        if (!newerTimestamp(timestamp, lastPeerTimestamp)) return false
        lastPeerTimestamp = timestamp.copyOf()
        localHash = WgCrypto.blake2s(localHash + msg.copyOfRange(88, 116))
        hash = localHash
        chain = localChain
        handshakeRemoteIndex = intAt(msg, 4)
        localEphemeral = msg.copyOfRange(8, 40)
        state = State.INITIATION_CONSUMED
        return true
    }

    fun response(): ByteArray? = synchronized(lock) {
        if (state != State.INITIATION_CONSUMED) return null
        val ephemeralPrivate = ByteArray(32).also { random.nextBytes(it) }
        val ephemeralPublic = WgCrypto.x25519(ephemeralPrivate)
        hash = WgCrypto.blake2s(hash + ephemeralPublic)
        chain = WgCrypto.kdf1(chain, ephemeralPublic)
        chain = WgCrypto.kdf1(chain, WgCrypto.x25519(ephemeralPrivate, localEphemeral))
        chain = WgCrypto.kdf1(chain, WgCrypto.x25519(ephemeralPrivate, peerPublic))
        val (nextChain, tau, key) = WgCrypto.kdf3(chain, psk)
        chain = nextChain
        hash = WgCrypto.blake2s(hash + tau)
        val empty = WgCrypto.seal(key, WgCrypto.zeroNonce, hash, ByteArray(0))
        hash = WgCrypto.blake2s(hash + empty)
        handshakeLocalIndex = randomIndex()
        val msg = ByteArray(92)
        putInt(msg, 0, TYPE_RESPONSE)
        putInt(msg, 4, handshakeLocalIndex)
        putInt(msg, 8, handshakeRemoteIndex)
        System.arraycopy(ephemeralPublic, 0, msg, 12, 32)
        System.arraycopy(empty, 0, msg, 44, 16)
        writeMacs(msg, peerPublic)
        state = State.RESPONSE_CREATED
        deriveKeys(initiator = false)
        msg
    }

    fun consumeResponse(msg: ByteArray): Boolean = synchronized(lock) {
        try { consumeResponseLocked(msg) } catch (_: Exception) { false }
    }

    private fun consumeResponseLocked(msg: ByteArray): Boolean {
        if (msg.size != 92 || intAt(msg, 0) != TYPE_RESPONSE || state != State.INITIATION_CREATED) return false
        if (intAt(msg, 8) != handshakeLocalIndex || !mac1Valid(msg, staticPublic)) return false
        var localHash = WgCrypto.blake2s(hash + msg.copyOfRange(12, 44))
        var localChain = WgCrypto.kdf1(chain, msg.copyOfRange(12, 44))
        localChain = WgCrypto.kdf1(localChain, WgCrypto.x25519(localEphemeral, msg.copyOfRange(12, 44)))
        localChain = WgCrypto.kdf1(localChain, WgCrypto.x25519(staticPrivate, msg.copyOfRange(12, 44)))
        val (nextChain, tau, key) = WgCrypto.kdf3(localChain, psk)
        localChain = nextChain
        localHash = WgCrypto.blake2s(localHash + tau)
        if (WgCrypto.open(key, WgCrypto.zeroNonce, localHash, msg.copyOfRange(44, 60)) == null) return false
        hash = WgCrypto.blake2s(localHash + msg.copyOfRange(44, 60))
        chain = localChain
        handshakeRemoteIndex = intAt(msg, 4)
        state = State.RESPONSE_CONSUMED
        deriveKeys(initiator = true)
        return true
    }

    fun consumeCookieReply(msg: ByteArray): Boolean = synchronized(lock) {
        if (msg.size != 64 || intAt(msg, 0) != TYPE_COOKIE || intAt(msg, 4) != handshakeLocalIndex) return false
        // The cookie is sealed with the mac1 of the message it answers as additional data.
        val aad = lastSentMac1 ?: return false
        val key = WgCrypto.blake2s("cookie--".encodeToByteArray() + peerPublic)
        val decoded = runCatching {
            WgCrypto.openXChaCha20Poly1305(key, msg.copyOfRange(8, 32), aad, msg.copyOfRange(32, 64))
        }.getOrNull() ?: return false
        if (decoded.size != 16) return false
        cookie = decoded
        cookieAtMs = clockMs()
        true
    }

    // Transport packets only read the keypair under the session lock; encryption runs outside it,
    // so sending and receiving no longer serialise on one lock (handshakes still do).
    fun sealPacket(plain: ByteArray): ByteArray? {
        val pair = synchronized(lock) { currentKeypair }?.takeUnless { it.expired(clockMs()) } ?: return null
        val counter = pair.sendCounter.getAndIncrement()
        if (counter !in 0 until REJECT_AFTER_MESSAGES) return null
        val nonce = transportNonce(counter)
        val encrypted = WgCrypto.seal(pair.sendKey, nonce, ByteArray(0), plain)
        return ByteArray(16 + encrypted.size).also { msg ->
            putInt(msg, 0, TYPE_TRANSPORT)
            putInt(msg, 4, pair.remoteIndex)
            for (i in 0 until 8) msg[8 + i] = (counter ushr (8 * i)).toByte()
            System.arraycopy(encrypted, 0, msg, 16, encrypted.size)
        }
    }

    fun openPacket(msg: ByteArray): ByteArray? {
        if (msg.size < 32 || intAt(msg, 0) != TYPE_TRANSPORT) return null
        val receiver = intAt(msg, 4)
        val pair = keypairFor(receiver) ?: return null
        val counter = longAt(msg, 8)
        if (counter !in 0 until REJECT_AFTER_MESSAGES || !pair.canAccept(counter)) return null
        val plain = WgCrypto.open(pair.recvKey, transportNonce(counter), ByteArray(0), msg.copyOfRange(16, msg.size)) ?: return null
        if (!pair.markAccepted(counter)) return null
        confirm(pair)
        return plain
    }

    /**
     * Seals [length] bytes of [plain] as a transport message into [out] (needs 32 extra bytes).
     * Returns the message size, or -1 without a session. Used by the per-packet tunnel loop.
     */
    fun sealPacketInto(plain: ByteArray, length: Int, out: ByteArray): Int {
        val pair = synchronized(lock) { currentKeypair }?.takeUnless { it.expired(clockMs()) } ?: return -1
        val counter = pair.sendCounter.getAndIncrement()
        if (counter !in 0 until REJECT_AFTER_MESSAGES) return -1
        putInt(out, 0, TYPE_TRANSPORT)
        putInt(out, 4, pair.remoteIndex)
        for (i in 0 until 8) out[8 + i] = (counter ushr (8 * i)).toByte()
        return 16 + WgCrypto.sealInto(pair.sendKey, transportNonce(counter), plain, length, out, 16)
    }

    /** Opens a transport message of [length] bytes into [out]; plaintext size or -1. */
    fun openPacketInto(msg: ByteArray, length: Int, out: ByteArray): Int {
        if (length < 32 || intAt(msg, 0) != TYPE_TRANSPORT) return -1
        val receiver = intAt(msg, 4)
        val pair = keypairFor(receiver) ?: return -1
        val counter = longAt(msg, 8)
        if (counter !in 0 until REJECT_AFTER_MESSAGES || !pair.canAccept(counter)) return -1
        val size = WgCrypto.openInto(pair.recvKey, transportNonce(counter), msg, 16, length - 16, out)
        if (size < 0 || !pair.markAccepted(counter)) return -1
        confirm(pair)
        return size
    }

    private fun keypairFor(receiver: Int): Keypair? = synchronized(lock) {
        when (receiver) {
            currentKeypair?.localIndex -> currentKeypair
            previousKeypair?.localIndex -> previousKeypair
            nextKeypair?.localIndex -> nextKeypair
            else -> null
        }
    }?.takeUnless { it.expired(clockMs()) }

    /** The initiator used the responder keypair: it becomes the current one. */
    private fun confirm(pair: Keypair) {
        if (synchronized(lock) { nextKeypair !== pair }) return
        synchronized(lock) {
            if (nextKeypair === pair) {
                previousKeypair = currentKeypair
                currentKeypair = pair
                nextKeypair = null
            }
        }
    }

    fun messageType(msg: ByteArray, length: Int = msg.size): Int = if (length >= 4) intAt(msg, 0) else 0

    private fun deriveKeys(initiator: Boolean) {
        val (first, second) = WgCrypto.kdf2(chain, ByteArray(0))
        val now = clockMs()
        if (initiator) {
            previousKeypair = currentKeypair
            currentKeypair = Keypair(handshakeLocalIndex, handshakeRemoteIndex, first, second, now)
            nextKeypair = null
        } else {
            // Responder: keep sending on the current keypair until the initiator's first packet.
            nextKeypair = Keypair(handshakeLocalIndex, handshakeRemoteIndex, second, first, now)
        }
        state = State.ZERO
    }

    private fun writeMacs(msg: ByteArray, receiverPublic: ByteArray) {
        val mac1Key = WgCrypto.blake2s("mac1----".encodeToByteArray() + receiverPublic)
        val mac1 = WgCrypto.blake2s(msg.copyOf(msg.size - 32), mac1Key, 16)
        System.arraycopy(mac1, 0, msg, msg.size - 32, 16)
        lastSentMac1 = mac1.copyOf(16)
        // Cookies expire after COOKIE_SECRET_MAX_AGE (120 s); an old one is dropped, not reused.
        if (cookie != null && clockMs() - cookieAtMs >= COOKIE_MAX_AGE_MS) cookie = null
        val currentCookie = cookie
        if (currentCookie != null) {
            val mac2 = WgCrypto.blake2s(msg.copyOf(msg.size - 16), currentCookie, 16)
            System.arraycopy(mac2, 0, msg, msg.size - 16, 16)
        }
    }

    private fun mac1Valid(msg: ByteArray, receiverPublic: ByteArray): Boolean {
        val key = WgCrypto.blake2s("mac1----".encodeToByteArray() + receiverPublic)
        val mac = WgCrypto.blake2s(msg.copyOf(msg.size - 32), key, 16)
        return java.security.MessageDigest.isEqual(mac, msg.copyOfRange(msg.size - 32, msg.size - 16))
    }

    private fun newerTimestamp(candidate: ByteArray, previous: ByteArray?): Boolean {
        if (candidate.size != 12) return false
        if (previous == null) return true
        for (i in candidate.indices) {
            val a = candidate[i].toInt() and 0xff
            val b = previous[i].toInt() and 0xff
            if (a != b) return a > b
        }
        return false
    }

    private fun transportNonce(counter: Long) = ByteArray(12).also { nonce ->
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (8 * i)).toByte()
    }

    private fun randomIndex(): Int {
        var value = 0
        while (value == 0 || value == currentKeypair?.localIndex || value == previousKeypair?.localIndex || value == nextKeypair?.localIndex) value = random.nextInt()
        return value
    }

    private fun putInt(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte()
        target[at + 1] = (value ushr 8).toByte()
        target[at + 2] = (value ushr 16).toByte()
        target[at + 3] = (value ushr 24).toByte()
    }

    private fun intAt(source: ByteArray, at: Int): Int =
        (source[at].toInt() and 255) or
            ((source[at + 1].toInt() and 255) shl 8) or
            ((source[at + 2].toInt() and 255) shl 16) or
            ((source[at + 3].toInt() and 255) shl 24)

    private fun longAt(source: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((source[at + i].toLong() and 255) shl (8 * i))
        return value
    }

    private class Keypair(
        val localIndex: Int,
        val remoteIndex: Int,
        val sendKey: ByteArray,
        val recvKey: ByteArray,
        private val createdAtMs: Long = 0L,
    ) {
        /** REJECT-AFTER-TIME: keys older than 180 s are never used again. */
        fun expired(nowMs: Long): Boolean = nowMs - createdAtMs >= REJECT_AFTER_TIME_MS

        val sendCounter = AtomicLong(0)
        private val replay = WgReplayWindow(REPLAY_WINDOW_SIZE)

        @Synchronized fun canAccept(counter: Long): Boolean = replay.canAccept(counter)

        @Synchronized fun markAccepted(counter: Long): Boolean = replay.markAccepted(counter)
    }

    private enum class State { ZERO, INITIATION_CREATED, INITIATION_CONSUMED, RESPONSE_CREATED, RESPONSE_CONSUMED }

    companion object {
        const val TYPE_INITIATION = 1
        const val TYPE_RESPONSE = 2
        const val TYPE_COOKIE = 3
        const val TYPE_TRANSPORT = 4
        private const val REPLAY_WINDOW_SIZE = 2048L
        const val REJECT_AFTER_TIME_MS = 180_000L
        const val COOKIE_MAX_AGE_MS = 120_000L
        /** REJECT-AFTER-MESSAGES is 2^64 − 2^13 − 1; a signed counter stops well before it. */
        const val REJECT_AFTER_MESSAGES = Long.MAX_VALUE - 8192L

        /** Plaintext padded to a multiple of 16 bytes, never beyond [mtu]; keepalives stay empty. */
        fun paddedLength(length: Int, mtu: Int): Int = minOf(mtu, (length + 15) and 15.inv()).coerceAtLeast(length)
    }
}

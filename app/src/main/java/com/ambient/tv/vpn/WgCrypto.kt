package com.ambient.tv.vpn

import java.util.Arrays
import org.bouncycastle.math.ec.rfc7748.X25519
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object WgCrypto {
    private val iv = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )
    private val sigma = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    fun blake2s(data: ByteArray, key: ByteArray? = null, outLen: Int = 32): ByteArray {
        val secret = key ?: ByteArray(0)
        require(secret.size <= 32 && outLen in 1..32)
        val h = iv.copyOf()
        h[0] = h[0] xor 0x01010000 xor (secret.size shl 8) xor outLen
        val block = ByteArray(64)
        var offset = 0
        var bytes = 0L
        if (secret.isNotEmpty()) {
            System.arraycopy(secret, 0, block, 0, secret.size)
            offset = 64
            bytes = 64
        }
        var index = 0
        while (index < data.size) {
            if (offset == 64) {
                compress(h, block, bytes, false)
                offset = 0
            }
            val n = minOf(64 - offset, data.size - index)
            System.arraycopy(data, index, block, offset, n)
            offset += n
            bytes += n
            index += n
        }
        if (offset < 64) Arrays.fill(block, offset, 64, 0)
        compress(h, block, bytes, true)
        return ByteArray(outLen) { word -> (h[word / 4] ushr (8 * (word % 4))).toByte() }
    }

    private fun compress(h: IntArray, block: ByteArray, count: Long, last: Boolean) {
        val m = IntArray(16) { i ->
            (block[i * 4].toInt() and 255) or
                ((block[i * 4 + 1].toInt() and 255) shl 8) or
                ((block[i * 4 + 2].toInt() and 255) shl 16) or
                ((block[i * 4 + 3].toInt() and 255) shl 24)
        }
        val v = IntArray(16)
        System.arraycopy(h, 0, v, 0, 8)
        System.arraycopy(iv, 0, v, 8, 8)
        v[12] = v[12] xor count.toInt()
        v[13] = v[13] xor (count ushr 32).toInt()
        if (last) v[14] = v[14] xor -1
        for (round in 0 until 10) {
            val s = sigma[round]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        v[a] = v[a] + v[b] + x
        v[d] = Integer.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 12)
        v[a] = v[a] + v[b] + y
        v[d] = Integer.rotateRight(v[d] xor v[a], 8)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 7)
    }

    fun hmacBlake2s(key: ByteArray, vararg parts: ByteArray): ByteArray {
        var k = key
        if (k.size > 64) k = blake2s(k)
        val block = ByteArray(64)
        System.arraycopy(k, 0, block, 0, k.size)
        val ipad = ByteArray(64) { (block[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(64) { (block[it].toInt() xor 0x5c).toByte() }
        val inner = blake2s(ipad + parts.concat())
        return blake2s(opad + inner)
    }

    fun kdf1(key: ByteArray, input: ByteArray): ByteArray {
        val prk = hmacBlake2s(key, input)
        return hmacBlake2s(prk, byteArrayOf(1))
    }

    fun kdf2(key: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmacBlake2s(key, input)
        val t0 = hmacBlake2s(prk, byteArrayOf(1))
        val t1 = hmacBlake2s(prk, t0, byteArrayOf(2))
        return t0 to t1
    }

    fun kdf3(key: ByteArray, input: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val prk = hmacBlake2s(key, input)
        val t0 = hmacBlake2s(prk, byteArrayOf(1))
        val t1 = hmacBlake2s(prk, t0, byteArrayOf(2))
        val t2 = hmacBlake2s(prk, t1, byteArrayOf(3))
        return Triple(t0, t1, t2)
    }

    // Cipher.getInstance walks the provider list; one instance per thread is re-initialised with
    // each packet's key and nonce instead.
    private val sealCipher: ThreadLocal<Cipher> = ThreadLocal.withInitial { Cipher.getInstance("ChaCha20-Poly1305") }
    private val openCipher: ThreadLocal<Cipher> = ThreadLocal.withInitial { Cipher.getInstance("ChaCha20-Poly1305") }

    // withInitial never yields null, but Kotlin sees ThreadLocal.get() as nullable.
    private fun ThreadLocal<Cipher>.current(): Cipher = checkNotNull(get())

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = sealCipher.current()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    /** Transport seal without AAD into [out] at [outOffset]; returns the bytes written. */
    fun sealInto(key: ByteArray, nonce: ByteArray, plain: ByteArray, plainLength: Int, out: ByteArray, outOffset: Int): Int {
        val cipher = sealCipher.current()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return cipher.doFinal(plain, 0, plainLength, out, outOffset)
    }

    /** Transport open without AAD of [length] bytes at [offset] into [out]; -1 when authentication fails. */
    fun openInto(key: ByteArray, nonce: ByteArray, cipherText: ByteArray, offset: Int, length: Int, out: ByteArray): Int = runCatching {
        val cipher = openCipher.current()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.doFinal(cipherText, offset, length, out, 0)
    }.getOrDefault(-1)

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipherText: ByteArray): ByteArray? = runCatching {
        val cipher = openCipher.current()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        cipher.doFinal(cipherText)
    }.getOrNull()

    fun sealXChaCha20Poly1305(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 24)
        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val shortNonce = ByteArray(12)
        System.arraycopy(nonce, 16, shortNonce, 4, 8)
        return seal(subKey, shortNonce, aad, plain)
    }

    fun openXChaCha20Poly1305(key: ByteArray, nonce: ByteArray, aad: ByteArray, cipherText: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 24) return null
        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val shortNonce = ByteArray(12)
        System.arraycopy(nonce, 16, shortNonce, 4, 8)
        return open(subKey, shortNonce, aad, cipherText)
    }

    private fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32 && nonce16.size == 16)
        val state = intArrayOf(
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
            leInt(key, 0), leInt(key, 4), leInt(key, 8), leInt(key, 12),
            leInt(key, 16), leInt(key, 20), leInt(key, 24), leInt(key, 28),
            leInt(nonce16, 0), leInt(nonce16, 4), leInt(nonce16, 8), leInt(nonce16, 12),
        )
        repeat(10) {
            quarterRound(state, 0, 4, 8, 12); quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14); quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15); quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13); quarterRound(state, 3, 4, 9, 14)
        }
        return ByteArray(32).also { out ->
            val words = intArrayOf(state[0], state[1], state[2], state[3], state[12], state[13], state[14], state[15])
            words.forEachIndexed { i, value -> putLeInt(out, i * 4, value) }
        }
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun leInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or ((bytes[at + 3].toInt() and 0xff) shl 24)

    private fun putLeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte()
        bytes[at + 2] = (value ushr 16).toByte(); bytes[at + 3] = (value ushr 24).toByte()
    }

    /**
     * Bouncy Castle's RFC 7748 X25519 (constant time, no secret-dependent branches). The platform
     * JCA only offers X25519/XDH from Android 13 (API 33), below minSdk 29; Bouncy Castle is
     * already in the APK and works on every API level. Without [point] it returns the public key.
     */
    fun x25519(scalar: ByteArray, point: ByteArray? = null): ByteArray {
        require(scalar.size == 32 && (point == null || point.size == 32))
        val result = ByteArray(32)
        if (point == null) {
            X25519.generatePublicKey(scalar, 0, result, 0)
            return result
        }
        // WireGuard requires rejecting an all-zero result (a low-order peer point); Bouncy Castle
        // reports it without branching on the content. No message: the tunnel then reports the
        // app's own translated failure text.
        if (!X25519.calculateAgreement(scalar, 0, point, 0, result, 0)) throw java.security.InvalidKeyException()
        return result
    }

    fun tai64n(epochMs: Long = System.currentTimeMillis()): ByteArray {
        val nanos = ((epochMs % 1000) * 1_000_000).toInt() and 0xFFFFFF.inv()
        val out = ByteArray(12)
        val tai = epochMs / 1000 + 0x400000000000000aL
        for (i in 0 until 8) out[7 - i] = (tai ushr (8 * i)).toByte()
        out[8] = (nanos ushr 24).toByte()
        out[9] = (nanos ushr 16).toByte()
        out[10] = (nanos ushr 8).toByte()
        out[11] = nanos.toByte()
        return out
    }

    val zeroNonce = ByteArray(12)
    val construction = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".encodeToByteArray()
    val identifier = "WireGuard v1 zx2c4 Jason@zx2c4.com".encodeToByteArray()
    val initialChain = blake2s(construction)
    val initialHash = blake2s(initialChain + identifier)

    private fun Array<out ByteArray>.concat(): ByteArray = fold(ByteArray(0)) { a, b -> a + b }
    private operator fun ByteArray.plus(other: ByteArray): ByteArray = copyOf(size + other.size).also { System.arraycopy(other, 0, it, size, other.size) }
}

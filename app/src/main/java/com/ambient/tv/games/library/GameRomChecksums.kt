package com.ambient.tv.games.library

import java.text.Normalizer
import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32

/** Alternate catalog checksum without changing the ROM or the provider's original checksum. */
internal object GameRomChecksums {
    data class Result(val crc32: String?, val datCrc32: String? = null)
    private val CRC = Regex("[0-9A-Fa-f]{8}")
    private val EXCLUDED = setOf("zip", "7z", "rar", "gz", "cue", "m3u", "gdi", "chd",
        "iso", "cso", "zso", "wbfs", "rvz", "ciso", "pbp")

    fun canHash(filename: String, size: Long): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val limit = when (extension) {
            "nds", "dsi", "ids" -> 512L
            "3ds", "cci", "cxi" -> 4096L
            else -> 128L
        }
        return extension !in EXCLUDED && size in 1..limit * 1024 * 1024
    }

    /** One bounded streaming pass over a raw ROM, never an in-memory ROM or cache copy. */
    fun read(systemId: String, size: Long, readAt: (Long, ByteArray, Int) -> Int): Result {
        require(size > 0)
        val crc = CRC32()
        val chunk = ByteArray(128 * 1024)
        val prefix = ByteArray(minOf(512L, size).toInt())
        var offset = 0L
        while (offset < size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val length = minOf(chunk.size.toLong(), size - offset).toInt()
            val read = readAt(offset, chunk, length)
            if (read <= 0 || read > length) throw IOException("ROM read truncated")
            if (offset < prefix.size) chunk.copyInto(prefix, offset.toInt(), 0,
                minOf(read, prefix.size - offset.toInt()))
            crc.update(chunk, 0, read)
            offset += read
        }
        return forRom(systemId, size, prefix, hex(crc.value))
    }

    /**
     * SNES copier dumps prepend 512 bytes to a whole number of 32 KiB ROM banks. ZIP/7z/GZIP
     * already expose the whole-file CRC and we already read this prefix for the internal title.
     * CRC(A + B) = shift(CRC(A), length(B)) xor CRC(B), so removing the prefix needs no full
     * decompression, ROM allocation, second network pass, or cache copy.
     */
    fun forRom(systemId: String, size: Long, prefix: ByteArray?, crc32: String?): Result {
        val raw = crc32?.takeIf { it.matches(CRC) }?.uppercase(Locale.ROOT)
        if (raw != null && systemId == "nds") return Result(raw, ndsPaddedCrc(size, prefix, raw))
        if (raw == null || systemId != "snes" || size < 32 * 1024 + 512 ||
            size % (32 * 1024) != 512L || prefix == null || prefix.size < 512) return Result(raw)
        val header = CRC32().apply { update(prefix, 0, 512) }.value
        return Result(raw, hex(raw.toLong(16) xor shift(header, size - 512)))
    }

    /**
     * A trimmed DS dump omits trailing cartridge padding. Try its nominal capacity with
     * erased (FF) bytes as a second checksum, never replacing the actual ROM identity.
     * Only an exact DAT CRC match can confirm this hypothesis; no ROM bytes are synthesized.
     */
    private fun ndsPaddedCrc(size: Long, prefix: ByteArray?, raw: String): String? {
        if (prefix == null || prefix.size < 0x84 || size < 0x200) return null
        // Only standard DS cartridges. DSi has additional layout/size rules.
        if (prefix[0x12].toInt() != 0) return null
        if ((0x0c..0x0f).any { (prefix[it].toInt() and 255).let { c -> c !in 48..57 && c !in 65..90 } }) return null
        val capacity = prefix[0x14].toInt() and 255
        if (capacity !in 0..12) return null
        val nominal = 128L * 1024 shl capacity
        val used = (0..3).fold(0L) { acc, i -> acc or ((prefix[0x80 + i].toLong() and 255) shl (8 * i)) }
        if (used < 0x200 || used > size || size >= nominal) return null
        var remaining = nominal - size
        var blockLength = 1L
        var blockCrc = CRC32().apply { update(255) }.value
        var result = raw.toLong(16)
        // CRC concatenation by powers of two: bounded memory and no padding-sized IO.
        while (remaining > 0) {
            if (remaining and 1L != 0L) result = shift(result, blockLength) xor blockCrc
            remaining = remaining ushr 1
            if (remaining > 0) {
                blockCrc = shift(blockCrc, blockLength) xor blockCrc
                blockLength *= 2
            }
        }
        return hex(result)
    }

    fun hex(crc: Long): String = crc.toString(16).uppercase(Locale.ROOT).padStart(8, '0')

    /** Apply the reflected CRC-32 polynomial for [bytes] zero bytes in O(log(bytes)). */
    private fun shift(crc: Long, bytes: Long): Long {
        fun apply(matrix: LongArray, value: Long): Long {
            var bits = value
            var result = 0L
            var index = 0
            while (bits != 0L) {
                if (bits and 1L != 0L) result = result xor matrix[index]
                bits = bits ushr 1
                index += 1
            }
            return result
        }
        fun square(matrix: LongArray) = LongArray(32) { apply(matrix, matrix[it]) }
        var matrix = LongArray(32) { if (it == 0) 0xEDB88320L else 1L shl (it - 1) }
        repeat(3) { matrix = square(matrix) } // one bit -> one byte
        var remaining = bytes
        var result = crc
        while (remaining > 0) {
            if (remaining and 1L != 0L) result = apply(matrix, result)
            remaining = remaining ushr 1
            if (remaining > 0) matrix = square(matrix)
        }
        return result
    }
}

/** Catalog searches ignore title punctuation/spacing, but always return the exact DAT name. */
internal fun gameDatSearchKey(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD).filter(Char::isLetterOrDigit)

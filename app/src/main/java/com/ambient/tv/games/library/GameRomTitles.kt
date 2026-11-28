package com.ambient.tv.games.library

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Low-I/O title hints. Reads at most small, fixed windows from a seekable file.
 * These are internal cartridge/disc labels, NOT verified commercial release titles.
 * Unsupported, truncated or suspicious data must fall back to the file name.
 */
object GameRomTitles {
    private val sjis: Charset = Charset.forName("Shift_JIS")

    fun extract(systemId: String, fileName: String, read: (Long, Int) -> ByteArray?): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return runCatching {
            when (systemId) {
                "gb" -> text(read(0x134, 16), StandardCharsets.US_ASCII)
                "gba" -> text(read(0xa0, 12), StandardCharsets.US_ASCII)
                "nds" -> nds(read)
                "snes" -> snes(read)
                "megadrive" -> if (ext in setOf("md", "gen", "68k")) {
                    text(read(0x150, 48), StandardCharsets.US_ASCII)
                        ?: text(read(0x120, 48), StandardCharsets.US_ASCII)
                } else null
                "n64" -> n64(read(0, 0x40))
                "gamecube", "wii" -> if (ext in setOf("iso", "gcm", "tgc", "wbfs")) {
                    // Disc title from the plain disc header, when exposed at offset 0x20.
                    if (ext == "wbfs") null else text(read(0x20, 0x60), sjis)
                } else null
                "psp" -> when (ext) {
                    "pbp" -> pbpTitle(read)
                    "iso" -> pspIsoTitle(read)
                    "cso", "zso" -> compressedIsoReader(read)?.let(::pspIsoTitle)
                    else -> null
                }
                else -> null
            }
        }.getOrNull()
    }

    /** The cartridge's twelve-byte internal ID is not a display title. Read its UTF-16 banner
     * instead, keeping the read bounded and falling back to the filename if unavailable. */
    private fun nds(read: (Long, Int) -> ByteArray?): String? {
        val header = read(0, 0x6c) ?: return null
        if (header.size < 0x6c) return null
        val offset = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(0x68).toLong() and 0xffffffffL
        if (offset !in 0x200L..(512L * 1024 * 1024 - 0x840)) return null
        val versionBytes = read(offset, 2) ?: return null
        if (versionBytes.size != 2) return null
        val count = when (u16(versionBytes, 0)) {
            1 -> 6
            2 -> 7
            3, 0x103 -> 8
            else -> return null
        }
        val preferred = when (Locale.getDefault().language) {
            "ja" -> 0; "fr" -> 2; "de" -> 3; "it" -> 4; "es" -> 5; "zh" -> 6; "ko" -> 7
            else -> 1
        }
        for (index in (listOf(preferred, 1, 0) + (0 until count)).distinct().filter { it < count }) {
            val bytes = read(offset + 0x240 + index * 0x100, 0x100) ?: continue
            if (bytes.size != 0x100) continue
            // The first nonempty line is the game title; following lines often name the publisher.
            val title = String(bytes, StandardCharsets.UTF_16LE).substringBefore('\u0000')
                .lineSequence().map(String::trim).firstOrNull(String::isNotBlank) ?: continue
            if (title.length in 2..96 && title.any(Char::isLetterOrDigit) &&
                title.none { it.isISOControl() || it == '\ufffd' || it == '\uffff' }) return title
        }
        return null
    }

    /** SNES has LoROM/HiROM variants and sometimes a 512-byte copier header. */
    private fun snes(read: (Long, Int) -> ByteArray?): String? {
        val candidates = listOf(0x7fc0L, 0xffc0L, 0x81c0L, 0x101c0L)
        val found = candidates.mapNotNull { offset ->
            val bytes = read(offset, 0x20) ?: return@mapNotNull null
            if (bytes.size < 0x20) return@mapNotNull null
            val name = text(bytes.copyOfRange(0, 21), StandardCharsets.US_ASCII) ?: return@mapNotNull null
            val inverse = u16(bytes, 0x1c)
            val checksum = u16(bytes, 0x1e)
            name to (inverse + checksum == 0xffff)
        }
        return found.firstOrNull { it.second }?.first ?: found.firstOrNull()?.first
    }

    private fun n64(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 0x34) return null
        val normalized = bytes.copyOf()
        when (bytes.take(4).map { it.toInt() and 0xff }) {
            listOf(0x37, 0x80, 0x40, 0x12) ->
                for (i in normalized.indices step 2) {
                    if (i + 1 < normalized.size) {
                        val b = normalized[i]; normalized[i] = normalized[i + 1]; normalized[i + 1] = b
                    }
                }
            listOf(0x40, 0x12, 0x37, 0x80) ->
                for (i in normalized.indices step 4) {
                    if (i + 3 < normalized.size) {
                        val a = normalized[i]; val b = normalized[i + 1]
                        normalized[i] = normalized[i + 3]; normalized[i + 1] = normalized[i + 2]
                        normalized[i + 2] = b; normalized[i + 3] = a
                    }
                }
            listOf(0x80, 0x37, 0x12, 0x40) -> Unit
            else -> return null
        }
        return text(normalized.copyOfRange(0x20, 0x34), StandardCharsets.US_ASCII)
    }

    /** PSP UMD images use ISO9660 with PSP_GAME/PARAM.SFO. Only the directory records and
     * the bounded SFO are read, never the game image itself. */
    private fun pspIsoTitle(read: (Long, Int) -> ByteArray?): String? {
        val sector = 2048
        val pvd = read(16L * sector, sector) ?: return null
        if (pvd.size != sector || pvd[0] != 1.toByte() ||
            String(pvd, 1, 5, StandardCharsets.US_ASCII) != "CD001" || pvd[6] != 1.toByte()) return null
        fun record(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
            if (offset < 0 || offset + 34 > bytes.size) return null
            val length = bytes[offset].toInt() and 255
            if (length < 34 || offset + length > bytes.size) return null
            val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val extent = le.getInt(offset + 2).toLong() and 0xffffffffL
            val size = le.getInt(offset + 10).toLong() and 0xffffffffL
            if (extent == 0L || size !in 1L..65536L) return null
            return extent * sector to size.toInt()
        }
        fun find(directory: Pair<Long, Int>, name: String, isDirectory: Boolean): Pair<Long, Int>? {
            val bytes = read(directory.first, directory.second) ?: return null
            if (bytes.size != directory.second) return null
            var offset = 0
            while (offset < bytes.size) {
                val length = bytes[offset].toInt() and 255
                if (length == 0) { offset = (offset / sector + 1) * sector; continue }
                if (length < 34 || offset + length > bytes.size || offset % sector + length > sector) return null
                val nameLength = bytes[offset + 32].toInt() and 255
                if (nameLength > length - 33) return null
                val filename = String(bytes, offset + 33, nameLength, StandardCharsets.US_ASCII)
                    .substringBefore(';').trimEnd('.')
                val flags = bytes[offset + 25].toInt() and 255
                if (filename.equals(name, ignoreCase = true) && ((flags and 2) != 0) == isDirectory &&
                    flags and 0x80 == 0) return record(bytes, offset)
                offset += length
            }
            return null
        }
        val root = record(pvd, 156) ?: return null
        val game = find(root, "PSP_GAME", true) ?: return null
        val sfo = find(game, "PARAM.SFO", false) ?: return null
        return read(sfo.first, sfo.second)?.let(::sfoTitle)
    }

    /** Bounded random reads of CISO v1 and ZISO images (2048-byte sectors). The index is read
     * per requested sector rather than allocating a table proportional to the image size. */
    private fun compressedIsoReader(read: (Long, Int) -> ByteArray?): ((Long, Int) -> ByteArray?)? {
        val header = read(0, 24) ?: return null
        if (header.size != 24) return null
        val magic = String(header, 0, 4, StandardCharsets.US_ASCII)
        if (magic !in setOf("CISO", "ZISO") || header[20] != 1.toByte()) return null
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val indexStart = le.getInt(4).toLong() and 0xffffffffL
        val size = le.getLong(8)
        val block = le.getInt(16)
        val align = header[21].toInt() and 255
        if (indexStart != 24L || size !in 2048L..(8L * 1024 * 1024 * 1024) ||
            block != 2048 || size % block != 0L || align > 12) return null
        val indexEnd = indexStart + (size / block + 1) * 4
        var lastIndex = -1L
        var lastData: ByteArray? = null
        fun sector(index: Long): ByteArray? {
            if (index == lastIndex) return lastData
            val entries = read(indexStart + index * 4, 8) ?: return null
            if (entries.size != 8) return null
            val table = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
            val first = table.getInt(0)
            val start = (first.toLong() and 0x7fffffffL) shl align
            val end = (table.getInt(4).toLong() and 0x7fffffffL) shl align
            if (start < indexEnd || end <= start || end - start > block + (1L shl align) + 64) return null
            val data = if (first < 0) {
                if (end - start < block) return null
                read(start, block)?.takeIf { it.size == block }
            } else {
                val packed = read(start, (end - start).toInt()) ?: return null
                if (magic == "ZISO") decodeLz4Block(packed, block)
                else {
                    val inflater = java.util.zip.Inflater(true)
                    try {
                        inflater.setInput(packed)
                        val result = ByteArray(block)
                        var written = 0
                        while (written < block && !inflater.finished()) {
                            val count = inflater.inflate(result, written, block - written)
                            if (count == 0) break
                            written += count
                        }
                        result.takeIf { written == block && inflater.finished() }
                    } finally { inflater.end() }
                }
            } ?: return null
            lastIndex = index
            lastData = data
            return data
        }
        return reader@ { offset, count ->
            if (offset < 0 || count !in 1..65536 || offset > size - count) return@reader null
            val result = ByteArray(count)
            var copied = 0
            while (copied < count) {
                val position = offset + copied
                val within = (position % block).toInt()
                val data = sector(position / block) ?: return@reader null
                val length = minOf(count - copied, block - within)
                data.copyInto(result, copied, within, within + length)
                copied += length
            }
            result
        }
    }

    /** Raw LZ4 sector decoder for ZISO, with strict source/output bounds and overlap handling. */
    private fun decodeLz4Block(input: ByteArray, size: Int): ByteArray? {
        val out = ByteArray(size)
        var at = 0
        var written = 0
        fun length(initial: Int): Int? {
            var value = initial
            if (initial == 15) do {
                if (at >= input.size) return null
                val extra = input[at++].toInt() and 255
                value += extra
                if (value > size) return null
            } while (extra == 255)
            return value
        }
        while (at < input.size && written < size) {
            val token = input[at++].toInt() and 255
            val literals = length(token ushr 4) ?: return null
            if (literals > input.size - at || literals > size - written) return null
            input.copyInto(out, written, at, at + literals)
            at += literals
            written += literals
            if (written == size) return out
            if (at + 2 > input.size) return null
            val distance = u16(input, at)
            at += 2
            val match = (length(token and 15) ?: return null) + 4
            if (distance == 0 || distance > written || match > size - written) return null
            repeat(match) { out[written] = out[written - distance]; written++ }
        }
        return out.takeIf { written == size }
    }

    /**
     * PBP embeds a PARAM.SFO section. This intentionally reads only its bounded header,
     * entry table and the TITLE value; arbitrary package contents are never loaded.
     */
    private fun pbpTitle(read: (Long, Int) -> ByteArray?): String? {
        val header = read(0, 40) ?: return null
        if (header.size < 40 || header[0] != 0.toByte() || header[1] != 'P'.code.toByte() ||
            header[2] != 'B'.code.toByte() || header[3] != 'P'.code.toByte()) return null
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val begin = buf.getInt(8).toLong() and 0xffffffffL
        val end = buf.getInt(12).toLong() and 0xffffffffL
        if (end <= begin || end - begin > 1024 * 1024) return null
        val sfo = read(begin, minOf((end - begin).toInt(), 64 * 1024)) ?: return null
        return sfoTitle(sfo)
    }

    private fun sfoTitle(sfo: ByteArray): String? {
        if (sfo.size < 20 || sfo[0] != 0.toByte() || sfo[1] != 'P'.code.toByte() ||
            sfo[2] != 'S'.code.toByte() || sfo[3] != 'F'.code.toByte()) return null
        val psf = ByteBuffer.wrap(sfo).order(ByteOrder.LITTLE_ENDIAN)
        val keyOffset = psf.getInt(8); val dataOffset = psf.getInt(12); val count = psf.getInt(16)
        if (keyOffset !in 20 until sfo.size || dataOffset !in 20 until sfo.size || count !in 1..512 ||
            20L + count.toLong() * 16 > sfo.size) return null
        for (i in 0 until count) {
            val row = 20 + i * 16
            val keyIndex = keyOffset + (psf.getShort(row).toInt() and 0xffff)
            val len = psf.getInt(row + 4)
            val valueIndex = dataOffset.toLong() + (psf.getInt(row + 12).toLong() and 0xffffffffL)
            if (keyIndex !in 20 until sfo.size || len !in 1..128 ||
                valueIndex < 0 || valueIndex + len > sfo.size) continue
            val nul = (keyIndex until minOf(dataOffset, sfo.size))
                .firstOrNull { sfo[it] == 0.toByte() } ?: continue
            if (nul < 0 || nul >= dataOffset || nul - keyIndex > 80) continue
            val key = String(sfo, keyIndex, nul - keyIndex, StandardCharsets.US_ASCII)
            if (key == "TITLE") return text(sfo.copyOfRange(valueIndex.toInt(), (valueIndex + len).toInt()), StandardCharsets.UTF_8)
        }
        return null
    }

    private fun u16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)

    private fun text(data: ByteArray?, encoding: Charset): String? {
        if (data == null || data.isEmpty()) return null
        val raw = String(data, encoding).substringBefore('\u0000')
            .trim(' ', '\u0000', '\uffff', '\u00ff')
            .replace(Regex("[\\s\\u0000-\\u001f]+"), " ").trim()
        if (raw.length < 3 || raw.length > 96 || raw.count { it.isLetterOrDigit() } < 3 ||
            raw.any { it.isISOControl() || it == '\ufffd' } ||
            raw.toSet().size < 3) return null
        return raw
    }
}

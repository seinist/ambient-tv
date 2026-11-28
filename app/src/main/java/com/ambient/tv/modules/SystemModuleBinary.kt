package com.ambient.tv.modules

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Adler32

/** Structural checks before ART or the Android dynamic linker sees a payload. */
internal object SystemModuleBinary {
    data class Native(val soname: String?, val needed: Set<String>)
    private val soName = Regex("lib[A-Za-z0-9_+.-]+[.]so([.][0-9]+)*")
    private fun reject(message: String): Nothing = throw SystemModuleTrustException(message)

    fun elf(file: File, abi: String, pageSize: Long): Native = RandomAccessFile(file, "r").use { r ->
        val (machine, wide) = when (abi) {
            "arm64-v8a" -> 183 to true
            "armeabi-v7a" -> 40 to false
            "x86_64" -> 62 to true
            "x86" -> 3 to false
            else -> reject("Invalid ELF ABI")
        }
        val length = r.length()
        fun bounds(at: Long, size: Long) {
            if (at < 0 || size < 0 || at > length || size > length - at) reject("ELF range out of bounds")
        }
        fun u16(at: Long): Int {
            bounds(at, 2); r.seek(at)
            return java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(r.readShort()))
        }
        fun u32(at: Long): Long {
            bounds(at, 4); r.seek(at)
            return Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
        }
        fun u64(at: Long): Long {
            bounds(at, 8); r.seek(at)
            return java.lang.Long.reverseBytes(r.readLong()).also { if (it < 0) reject("Unsupported ELF integer") }
        }
        fun word(at: Long) = if (wide) u64(at) else u32(at)
        val header = if (wide) 64 else 52
        bounds(0, header.toLong())
        val ident = ByteArray(16).also(r::readFully)
        if (!ident.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70)) ||
            ident[4].toInt() != (if (wide) 2 else 1) || ident[5].toInt() != 1 ||
            ident[6].toInt() != 1 || u16(16) != 3 || u16(18) != machine ||
            u32(20) != 1L || u16(if (wide) 52 else 40) != header)
            reject("Not a compatible Android ELF shared object")
        val ph = word(if (wide) 32 else 28)
        val entry = u16(if (wide) 54 else 42)
        val count = u16(if (wide) 56 else 44)
        if (entry != (if (wide) 56 else 32) || count !in 1..1024 || ph < header)
            reject("Invalid ELF program header table")
        bounds(ph, entry.toLong() * count)
        val requiredAlignment = maxOf(16384L, pageSize)
        if (requiredAlignment and (requiredAlignment - 1) != 0L) reject("Invalid device page size")
        data class Segment(val offset: Long, val address: Long, val size: Long)
        val loads = mutableListOf<Segment>()
        var dynamic: Segment? = null
        var executable = false
        for (i in 0 until count) {
            val p = ph + entry.toLong() * i
            val type = u32(p)
            val flags = u32(p + if (wide) 4 else 24)
            val offset = word(p + if (wide) 8 else 4)
            val address = word(p + if (wide) 16 else 8)
            val size = word(p + if (wide) 32 else 16)
            val mem = word(p + if (wide) 40 else 20)
            val alignment = word(p + if (wide) 48 else 28)
            bounds(offset, size)
            if (type == 3L) reject("ELF interpreter is not allowed")
            if (type == 0x6474e551L && flags and 1L != 0L) reject("Executable ELF stack")
            if (type == 1L) {
                if (mem < size || mem > Long.MAX_VALUE - address ||
                    alignment < requiredAlignment || alignment and (alignment - 1) != 0L ||
                    offset % alignment != address % alignment || flags and 3L == 3L)
                    reject("Unsafe or unaligned ELF load segment")
                loads += Segment(offset, address, size)
                if (flags and 1L != 0L && size > 0) executable = true
            }
            if (type == 2L) {
                if (dynamic != null) reject("Duplicate ELF dynamic table")
                dynamic = Segment(offset, address, size)
            }
        }
        if (loads.isEmpty() || !executable) reject("ELF executable segment missing")
        val d = dynamic ?: reject("ELF dynamic table missing")
        fun map(address: Long, size: Long): Long {
            if (size < 0) reject("Invalid ELF mapped size")
            val candidates = loads.filter {
                address >= it.address && address - it.address <= it.size &&
                    size <= it.size - (address - it.address)
            }
            val s = candidates.singleOrNull() ?: reject("ELF dynamic address not uniquely mapped")
            return s.offset + (address - s.address)
        }
        if (map(d.address, d.size) != d.offset) reject("ELF dynamic table mapping mismatch")
        val step = if (wide) 16L else 8L
        if (d.size % step != 0L || d.size / step !in 1..65536) reject("Invalid ELF dynamic table size")
        var strAddress: Long? = null
        var strSize: Long? = null
        var sonameIndex: Long? = null
        val needed = mutableListOf<Long>()
        var terminated = false
        var at = d.offset
        while (at < d.offset + d.size) {
            val tag = word(at)
            val value = word(at + step / 2)
            when (tag) {
                0L -> { terminated = true; break }
                1L -> { if (needed.size >= 256) reject("Too many ELF dependencies"); needed += value }
                5L -> { if (strAddress != null) reject("Duplicate ELF string table"); strAddress = value }
                10L -> { if (strSize != null) reject("Duplicate ELF string table size"); strSize = value }
                14L -> { if (sonameIndex != null) reject("Duplicate ELF SONAME"); sonameIndex = value }
                15L, 22L, 29L -> reject("ELF RPATH, RUNPATH or text relocations not allowed")
                30L -> if (value and 4L != 0L) reject("ELF text relocations not allowed")
            }
            at += step
        }
        if (!terminated) reject("Unterminated ELF dynamic table")
        val size = strSize ?: reject("ELF dynamic string table missing")
        if (size !in 1..(16L * 1024 * 1024)) reject("ELF string table too large")
        val base = map(strAddress ?: reject("ELF string address missing"), size)
        fun name(index: Long): String {
            if (index !in 0 until size) reject("Invalid ELF dependency name")
            r.seek(base + index)
            val result = StringBuilder()
            repeat(minOf(256L, size - index).toInt()) {
                val b = r.readUnsignedByte()
                if (b == 0) return result.toString().also {
                    if (!soName.matches(it)) reject("Unsafe ELF dependency name")
                }
                if (b !in 33..126) reject("Invalid ELF dependency name")
                result.append(b.toChar())
            }
            reject("Unterminated ELF dependency name")
        }
        Native(sonameIndex?.let(::name), needed.map(::name).toSet())
    }

    /** Standard standalone DEX only; ART remains the final bytecode verifier. */
    data class DexInfo(val definitions: Set<String>, val references: Set<String>)

    fun dex(file: File): Set<String> = inspectDex(file).definitions

    fun inspectDex(file: File): DexInfo = RandomAccessFile(file, "r").use { r ->
        val size = r.length()
        if (size !in 112L..(256L * 1024 * 1024)) reject("Invalid DEX length")
        val header = ByteArray(112).also(r::readFully)
        val magic = header.copyOfRange(0, 8).toString(Charsets.US_ASCII)
        if (magic !in setOf("dex\n035\u0000", "dex\n037\u0000", "dex\n038\u0000", "dex\n039\u0000", "dex\n040\u0000"))
            reject("Unsupported standalone DEX format")
        val b = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun u32(at: Int) = Integer.toUnsignedLong(b.getInt(at))
        if (u32(32) != size || u32(36) != 112L || u32(40) != 0x12345678L)
            reject("Invalid DEX header")
        fun table(count: Long, offset: Long, width: Long) {
            if (count == 0L) {
                if (offset != 0L) reject("Invalid empty DEX table")
            } else if (offset < 112 || offset % 4 != 0L || offset > size || count > (size - offset) / width)
                reject("DEX table outside file")
        }
        table(u32(44), u32(48), 1)
        val map = u32(52)
        if (map < 112 || map % 4 != 0L || map > size - 4) reject("Invalid DEX map offset")
        for ((at, width) in listOf(56 to 4L, 64 to 4L, 72 to 12L, 80 to 8L, 88 to 8L, 96 to 32L, 104 to 1L))
            table(u32(at), u32(at + 4), width)
        r.seek(map)
        val mapCount = Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
        if (mapCount !in 1..65536 || mapCount > (size - map - 4) / 12) reject("Invalid DEX map size")
        val types = mutableSetOf<Int>()
        var lastOffset = -1L
        repeat(mapCount.toInt()) {
            val type = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(r.readShort()))
            val reserved = r.readShort().toInt()
            val count = Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
            val offset = Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
            if (!types.add(type) || reserved != 0 || count == 0L || offset >= size || offset <= lastOffset)
                reject("Invalid DEX map entry")
            lastOffset = offset
        }
        val adler = Adler32()
        val sha = MessageDigest.getInstance("SHA-1")
        adler.update(header, 12, 100)
        sha.update(header, 32, 80)
        r.seek(112)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = r.read(buffer)
            if (n == -1) break
            adler.update(buffer, 0, n)
            sha.update(buffer, 0, n)
        }
        if (adler.value != u32(8) || !sha.digest().contentEquals(header.copyOfRange(12, 32)))
            reject("DEX header checksum or signature mismatch")

        // Inspect all type IDs once, including array component references.
        fun uintAt(offset: Long): Long {
            if (offset < 0 || offset > size - 4) reject("DEX class reference outside file")
            r.seek(offset)
            return Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
        }
        val classCount = u32(96)
        val typeCount = u32(64)
        if (classCount > 65536 || typeCount > 65536) reject("Too many DEX types or class definitions")
        val names = ArrayList<Pair<String?, Boolean>>(typeCount.toInt())
        val classPattern = Regex("L[A-Za-z0-9_/$-]+;")
        repeat(typeCount.toInt()) { index ->
            val string = uintAt(u32(68) + index.toLong() * 4)
            if (string >= u32(56)) reject("Invalid DEX type string index")
            val offset = uintAt(u32(60) + string * 4)
            if (offset !in 112 until size) reject("Invalid DEX type descriptor offset")
            r.seek(offset)
            var length = 0L
            var end = false
            for (shift in 0..28 step 7) {
                val value = r.readUnsignedByte()
                length = length or ((value and 127).toLong() shl shift)
                if (value and 128 == 0) { end = true; break }
            }
            if (!end || length !in 1..1024 || length >= size - r.filePointer)
                reject("Invalid DEX type descriptor length")
            val descriptor = ByteArray(length.toInt()).also(r::readFully)
            if (r.readUnsignedByte() != 0 || descriptor.any { (it.toInt() and 255) !in 33..126 })
                reject("Invalid DEX type descriptor encoding")
            val raw = descriptor.toString(Charsets.US_ASCII)
            val component = raw.trimStart('[')
            if (raw.length - component.length > 255) reject("Invalid DEX array type")
            if (classPattern.matches(component)) {
                names += component.substring(1, component.length - 1).replace('/', '.') to (raw == component)
            } else if (component.length == 1 && component[0] in "ZBSCIJFD" || raw == "V") {
                names += null to false
            } else reject("Invalid DEX type descriptor")
        }
        val classes = linkedSetOf<String>()
        repeat(classCount.toInt()) { index ->
            val type = uintAt(u32(100) + index.toLong() * 32)
            if (type >= typeCount) reject("Invalid DEX class type index")
            val (name, isClass) = names[type.toInt()]
            if (!isClass || name == null || !classes.add(name))
                reject("Invalid or duplicate DEX class definition")
        }
        DexInfo(classes.toSet(), names.mapNotNull { it.first }.toSet())
    }
}

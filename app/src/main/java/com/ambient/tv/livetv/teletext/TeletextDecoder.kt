package com.ambient.tv.livetv.teletext

/**
 * EBU teletext (ETS 300 706, level 1.5 subset) from a DVB transport stream (EN 300 472).
 *
 * Fed with raw TS bytes from any position; finds the teletext PID through PAT/PMT, reassembles its
 * PES packets and builds pages per magazine. Independent of the video player, so every engine gets
 * the same pages and subtitles. Not thread-safe for feeding; the page store is read under [lock].
 */
class TeletextDecoder(
    /** A page finished arriving (next header of its magazine, or any header in serial mode). */
    private val onPageCommitted: (TeletextPage) -> Unit = {},
    /** PMT or the set of known subtitle pages changed. */
    private val onServiceChanged: () -> Unit = {},
) {
    val lock = Any()

    // Transport stream
    private val carry = ByteArray(TS_PACKET)
    private var carryLength = 0
    private var pmtPid = -1
    private var programNumber = -1
    private var transportStreamId = -1
    /** PID of the teletext elementary stream, -1 until the PMT names one. */
    @Volatile var teletextPid = -1
        private set
    /** Pages the PMT's teletext descriptor announces (initial page, subtitle pages and their language). */
    @Volatile var announcedPages: List<TeletextAnnouncedPage> = emptyList()
        private set
    /** The PMT has been read: from here on [teletextPid] and [aitPid] say what the stream carries. */
    @Volatile var pmtSeen = false
        private set
    /** PID of the HbbTV application information table, -1 when the channel signals no HbbTV. */
    @Volatile var aitPid = -1
        private set
    /** An AIT section has been read (possibly without a usable application). */
    @Volatile var aitSeen = false
        private set
    /** HbbTV applications the complete AIT announces, autostart first. */
    @Volatile var hbbtvApps: List<HbbtvApplication> = emptyList()
        private set
    /** AITs may be split across section_number 0..last_section_number. */
    private var aitVersion = -1
    private var aitLastSection = -1
    private val aitAppsBySection = linkedMapOf<Int, List<HbbtvApplication>>()
    /** Teletext packets decoded so far; 0 means the stream carries none (yet). */
    @Volatile var packetCount = 0L
        private set

    // PAT, PMT and AIT sections can all span several TS packets.
    private val patSections = PsiSectionAssembler(::parsePat)
    private val pmtSections = PsiSectionAssembler(::parsePmt)
    private val aitSections = PsiSectionAssembler(::parseAitSection)

    // PES
    private var pes = ByteArray(4096)
    private var pesLength = 0
    private var pesActive = false

    // Pages
    private val receiving = arrayOfNulls<TeletextPage>(9)
    private var serialMode = false
    private val pages = HashMap<Int, LinkedHashMap<Int, TeletextPage>>()
    private val subtitlePagesSeen = sortedSetOf<Int>()
    /** Latest header row of any page: clock and station name while a page is being searched. */
    @Volatile var lastHeader: TeletextPage? = null
        private set

    /** A new connection (or a seek) continues at an unrelated byte: drop partial packets and pages. */
    fun discontinuity() {
        carryLength = 0
        patSections.reset()
        pmtSections.reset()
        aitSections.reset()
        pesActive = false
        pesLength = 0
        synchronized(lock) { receiving.fill(null) }
    }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var i = offset
        val end = offset + length
        while (i < end) {
            if (carryLength == 0) {
                if (data[i] != SYNC) { i++; continue }
                if (end - i >= TS_PACKET) { packet(data, i); i += TS_PACKET; continue }
            }
            val n = minOf(TS_PACKET - carryLength, end - i)
            System.arraycopy(data, i, carry, carryLength, n)
            carryLength += n
            i += n
            if (carryLength == TS_PACKET) {
                if (carry[0] == SYNC) packet(carry, 0)
                carryLength = 0
            }
        }
    }

    private fun packet(buf: ByteArray, p: Int) {
        val b1 = buf[p + 1].u
        val pid = ((b1 and 0x1F) shl 8) or buf[p + 2].u
        val sections = when (pid) {
            0 -> patSections
            pmtPid -> pmtSections
            aitPid -> aitSections
            else -> null
        }
        if (sections != null) { sections.packet(buf, p); return }
        val start = b1 and 0x40 != 0
        val afc = (buf[p + 3].u shr 4) and 3
        if (afc == 0 || afc == 2) return
        var payload = p + 4
        if (afc == 3) payload += 1 + buf[p + 4].u
        val end = p + TS_PACKET
        if (payload >= end) return
        if (pid == teletextPid) pesData(buf, payload, end, start)
    }

    private fun parsePat(buf: ByteArray) {
        if (buf.size < 12 || buf[0].u != 0x00 || buf[5].u and 1 == 0) return
        val last = buf.size - 4 // without CRC
        if ((last - 8) % 4 != 0) return
        val tsid = (buf[3].u shl 8) or buf[4].u
        var i = 8
        while (i + 4 <= last) {
            val program = (buf[i].u shl 8) or buf[i + 1].u
            val pid = ((buf[i + 2].u and 0x1F) shl 8) or buf[i + 3].u
            if (program != 0) {
                if (pid != pmtPid || program != programNumber || tsid != transportStreamId) {
                    val wasKnown = pmtSeen
                    pmtPid = pid
                    programNumber = program
                    transportStreamId = tsid
                    pmtSections.reset()
                    aitSections.reset()
                    pmtSeen = false
                    teletextPid = -1
                    announcedPages = emptyList()
                    aitPid = -1
                    resetAitApplications()
                    pesActive = false
                    pesLength = 0
                    synchronized(lock) { receiving.fill(null) }
                    if (wasKnown) onServiceChanged()
                }
                return
            }
            i += 4
        }
    }

    private fun parsePmt(buf: ByteArray) {
        if (buf.size < 16 || buf[0].u != 0x02 || buf[5].u and 1 == 0) return
        if (((buf[3].u shl 8) or buf[4].u) != programNumber) return
        val programInfo = ((buf[10].u and 0x0F) shl 8) or buf[11].u
        var i = 12 + programInfo
        val last = buf.size - 4 // without CRC
        if (i > last) return
        var foundTeletext = -1
        var foundAit = -1
        val announced = mutableListOf<TeletextAnnouncedPage>()
        while (i < last) {
            if (i + 5 > last) return
            val pid = ((buf[i + 1].u and 0x1F) shl 8) or buf[i + 2].u
            val esInfo = ((buf[i + 3].u and 0x0F) shl 8) or buf[i + 4].u
            var d = i + 5
            val dEnd = d + esInfo
            if (dEnd > last) return
            while (d < dEnd) {
                if (d + 2 > dEnd) return
                val tag = buf[d].u
                val len = buf[d + 1].u
                if (d + 2 + len > dEnd) return
                when (tag) {
                    0x56, 0x46 -> if (foundTeletext < 0) {
                        foundTeletext = pid
                        var e = d + 2
                        while (e + 5 <= d + 2 + len) {
                            val language = String(byteArrayOf(buf[e], buf[e + 1], buf[e + 2]), Charsets.ISO_8859_1)
                            val type = buf[e + 3].u shr 3
                            val magazine = (buf[e + 3].u and 7).let { if (it == 0) 8 else it }
                            announced += TeletextAnnouncedPage((magazine shl 8) or buf[e + 4].u, language, type)
                            e += 5
                        }
                    }
                    // application_signalling_descriptor: this PID carries the AIT.
                    0x6F -> if (foundAit < 0) foundAit = pid
                }
                d += 2 + len
            }
            i += 5 + esInfo
        }
        var changed = !pmtSeen
        pmtSeen = true
        if (foundTeletext != teletextPid) {
            teletextPid = foundTeletext
            pesActive = false
            synchronized(lock) { receiving.fill(null) }
            changed = true
        }
        if (announced != announcedPages) { announcedPages = announced; changed = true }
        if (foundAit != aitPid) {
            aitPid = foundAit
            aitSections.reset()
            resetAitApplications()
            changed = true
        }
        if (changed) onServiceChanged()
    }

    private fun parseAitSection(buf: ByteArray) {
        if (buf.size < 12 || buf[0].u != 0x74 || buf[5].u and 1 == 0) return
        val apps = parseAit(buf, buf.size) ?: return
        val version = (buf[5].u shr 1) and 0x1F
        val sectionNumber = buf[6].u
        val lastSection = buf[7].u
        if (sectionNumber > lastSection) return

        if (version != aitVersion || lastSection != aitLastSection) {
            aitVersion = version
            aitLastSection = lastSection
            aitAppsBySection.clear()
            aitSeen = false
            hbbtvApps = emptyList()
        }
        val previousApps = hbbtvApps
        val previousSeen = aitSeen
        aitAppsBySection[sectionNumber] = apps
        hbbtvApps = (0..lastSection)
            .flatMap { aitAppsBySection[it].orEmpty() }
            .distinctBy { it.url }
            .sortedBy { it.controlCode }
        // Do not stop the probe after section 0 if the broadcaster announced more sections.
        aitSeen = aitSeen || (0..lastSection).all(aitAppsBySection::containsKey)
        if (previousApps != hbbtvApps || previousSeen != aitSeen) onServiceChanged()
    }

    private fun resetAitApplications() {
        aitVersion = -1
        aitLastSection = -1
        aitAppsBySection.clear()
        aitSeen = false
        hbbtvApps = emptyList()
    }

    /**
     * A broadcaster/profile that advertises an AIT PID but never sends every section must not keep
     * a probe connection open forever. Preserve any applications already received and mark the
     * table complete after the outer probe timeout.
     */
    fun finishAitProbe() {
        if (aitPid < 0 || aitSeen) return
        aitSeen = true
        onServiceChanged()
    }

    private fun pesData(buf: ByteArray, payload: Int, end: Int, start: Boolean) {
        if (start) {
            if (pesActive) processPes()
            pesActive = true
            pesLength = 0
        }
        if (!pesActive) return
        val n = end - payload
        if (pesLength + n > pes.size) pes = pes.copyOf(maxOf(pes.size * 2, pesLength + n))
        System.arraycopy(buf, payload, pes, pesLength, n)
        pesLength += n
        // PES_packet_length is set for teletext: process as soon as the packet is whole.
        if (pesLength >= 6) {
            val declared = (pes[4].u shl 8) or pes[5].u
            if (declared > 0 && pesLength >= 6 + declared) { processPes(); pesActive = false }
        }
    }

    private fun processPes() {
        val b = pes
        if (pesLength < 9 || b[0].u != 0 || b[1].u != 0 || b[2].u != 1) return
        val declared = (b[4].u shl 8) or b[5].u
        val size = if (declared > 0) minOf(pesLength, 6 + declared) else pesLength
        var i = 9 + b[8].u
        if (i >= size) return
        val dataIdentifier = b[i].u
        if (dataIdentifier !in 0x10..0x1F) return
        i++
        val unit = ByteArray(UNIT)
        while (i + 2 <= size) {
            val id = b[i].u
            val len = b[i + 1].u
            if ((id == 0x02 || id == 0x03) && len == UNIT && i + 2 + UNIT <= size) {
                for (k in 0 until UNIT) unit[k] = REVERSED[b[i + 2 + k].u]
                teletextPacket(unit)
            }
            i += 2 + len
        }
    }

    /** One 42-byte teletext packet: [unit] holds field/line, framing code, then address and data. */
    internal fun teletextPacket(unit: ByteArray) {
        val a0 = hamming84(unit[2].u)
        val a1 = hamming84(unit[3].u)
        if (a0 < 0 || a1 < 0) return
        packetCount++
        val magazine = (a0 and 7).let { if (it == 0) 8 else it }
        val row = (a0 shr 3) or (a1 shl 1)
        synchronized(lock) {
            when (row) {
                0 -> header(magazine, unit)
                in 1..24 -> receiving[magazine]?.let { page ->
                    page.rows[row] = ByteArray(40) { k -> (unit[4 + k].u and 0x7F).toByte() }
                }
                27 -> receiving[magazine]?.let { links(magazine, it, unit) }
            }
        }
    }

    private fun header(magazine: Int, unit: ByteArray) {
        val h = IntArray(8) { hamming84(unit[4 + it].u) }
        if (h.any { it < 0 }) return
        val units = h[0]
        val tens = h[1]
        val subcode = h[2] or ((h[3] and 7) shl 4) or (h[4] shl 8) or ((h[5] and 3) shl 12)
        val erase = h[3] and 8 != 0
        val newsflash = h[5] and 4 != 0
        val subtitle = h[5] and 8 != 0
        serialMode = h[7] and 1 != 0
        val c12 = (h[7] shr 1) and 1
        val c13 = (h[7] shr 2) and 1
        val c14 = (h[7] shr 3) and 1
        val national = (c12 shl 2) or (c13 shl 1) or c14
        // Commit what this header ends: its own magazine, or every magazine in serial mode.
        if (serialMode) for (m in 1..8) commit(m) else commit(magazine)
        val headerText = ByteArray(32) { k -> (unit[12 + k].u and 0x7F).toByte() }
        val number = (magazine shl 8) or (tens shl 4) or units
        val page = TeletextPage(number, subcode, national, subtitle, newsflash, headerText)
        lastHeader = page
        // "FF": time filling header, no page follows.
        if (units > 9 || tens > 9) { receiving[magazine] = null; return }
        if (!erase) pages[number]?.get(subcode)?.let { old -> for (r in 1..24) page.rows[r] = old.rows[r] }
        receiving[magazine] = page
    }

    private fun links(magazine: Int, page: TeletextPage, unit: ByteArray) {
        if (hamming84(unit[4].u) != 0) return
        val result = IntArray(6)
        for (l in 0 until 6) {
            val o = 5 + l * 6
            val units = hamming84(unit[o].u)
            val tens = hamming84(unit[o + 1].u)
            val m1 = hamming84(unit[o + 3].u)
            val m23 = hamming84(unit[o + 5].u)
            if (units < 0 || tens < 0 || m1 < 0 || m23 < 0) { result[l] = 0; continue }
            val relative = (m1 shr 3) or ((m23 shr 2) shl 1)
            val m = ((magazine and 7) xor relative).let { if (it == 0) 8 else it }
            result[l] = if (units > 9 || tens > 9) 0 else (m shl 8) or (tens shl 4) or units
        }
        page.links = result
    }

    private fun commit(magazine: Int) {
        val page = receiving[magazine] ?: return
        receiving[magazine] = null
        val subpages = pages.getOrPut(page.number) { LinkedHashMap() }
        subpages.remove(page.subcode)
        subpages[page.subcode] = page
        while (subpages.size > MAX_SUBPAGES) subpages.remove(subpages.keys.first())
        if (page.subtitle && subtitlePagesSeen.add(page.number)) onServiceChanged()
        onPageCommitted(page)
    }

    /** PMT read and, when the channel signals HbbTV, its AIT too: what the stream offers is known. */
    fun probeComplete(): Boolean = pmtSeen && (aitPid < 0 || aitSeen)

    /** Received pages, displayable numbers only, ascending. */
    fun pageNumbers(): List<Int> = synchronized(lock) { pages.keys.filter(::teletextDisplayable).sorted() }

    /** Subcodes of [number] in arrival order (the rotation order of the broadcaster). */
    fun subcodes(number: Int): List<Int> = synchronized(lock) { pages[number]?.keys?.toList().orEmpty() }

    /** [subcode] of [number], or its most recent subpage; a page still arriving counts too. */
    fun page(number: Int, subcode: Int? = null): TeletextPage? = synchronized(lock) {
        val stored = pages[number]
        val inProgress = receiving.firstOrNull { it?.number == number && (subcode == null || it.subcode == subcode) }
        when {
            subcode != null -> stored?.get(subcode) ?: inProgress
            else -> stored?.values?.lastOrNull() ?: inProgress
        }?.copy()
    }

    fun subtitlePagesSeen(): List<Int> = synchronized(lock) { subtitlePagesSeen.toList() }

    companion object {
        const val TS_PACKET = 188
        private const val UNIT = 44
        private const val MAX_SUBPAGES = 64
        private const val SYNC = 0x47.toByte()

        private val REVERSED = ByteArray(256) { v ->
            var r = 0
            for (bit in 0 until 8) if (v and (1 shl bit) != 0) r = r or (1 shl (7 - bit))
            r.toByte()
        }

        /**
         * Hamming 8/4 (ETS 300 706 §8.2): data bits D1..D4 at bits 1, 3, 5, 7; parity at 0, 2, 4, 6.
         * One bit error is corrected, two are rejected (-1).
         */
        fun hamming84(value: Int): Int = HAMMING[value and 0xFF]

        private val HAMMING = IntArray(256) { v ->
            fun bit(n: Int) = (v shr (n - 1)) and 1 // b1..b8
            val a = bit(1) xor bit(2) xor bit(6) xor bit(8)
            val b = bit(2) xor bit(3) xor bit(4) xor bit(8)
            val c = bit(2) xor bit(4) xor bit(5) xor bit(6)
            val d = (1..8).sumOf { bit(it) } and 1
            var d1 = bit(2); var d2 = bit(4); var d3 = bit(6); var d4 = bit(8)
            val checksOk = a == 1 && b == 1 && c == 1
            when {
                checksOk -> Unit // no error, or only P4 (b7) flipped
                d == 1 -> return@IntArray -1 // checks fail but overall parity holds: two errors
                a == 0 && b == 0 && c == 0 -> d1 = d1 xor 1
                b == 0 && c == 0 -> d2 = d2 xor 1
                a == 0 && c == 0 -> d3 = d3 xor 1
                a == 0 && b == 0 -> d4 = d4 xor 1
                else -> Unit // a parity bit itself was wrong
            }
            d1 or (d2 shl 1) or (d3 shl 2) or (d4 shl 3)
        }
    }
}

/** A page announced in the PMT: [type] 1 = initial page, 2 = subtitles, 5 = subtitles for the hard of hearing. */
data class TeletextAnnouncedPage(val number: Int, val language: String, val type: Int) {
    val subtitle: Boolean get() = type == 2 || type == 5
    val hearingImpaired: Boolean get() = type == 5
}

private val Byte.u: Int get() = toInt() and 0xFF

/** Page numbers are hex digits (0x100 = page 100); only 0-9 digits are meant to be shown. */
fun teletextDisplayable(number: Int): Boolean =
    number in 0x100..0x8FF && (number shr 4 and 0xF) <= 9 && (number and 0xF) <= 9

/** 0x150 → 150. */
fun teletextDecimal(number: Int): Int =
    (number shr 8) * 100 + (number shr 4 and 0xF) * 10 + (number and 0xF)

/** 150 → 0x150; null outside 100..899. */
fun teletextNumber(decimal: Int): Int? =
    if (decimal !in 100..899) null else ((decimal / 100) shl 8) or (((decimal / 10) % 10) shl 4) or (decimal % 10)

/**
 * An HbbTV application from the AIT: [controlCode] 1 = autostart, 2 = present.
 * [url] joins the HTTP transport's base URL and the initial path.
 */
data class HbbtvApplication(val url: String, val name: String?, val controlCode: Int)

/** Applications of one AIT section ([section] starts at table_id), autostart first; null if not an HbbTV AIT. */
internal fun parseAit(section: ByteArray, length: Int): List<HbbtvApplication>? {
    fun u(i: Int) = section[i].toInt() and 0xFF
    if (length < 16 || u(0) != 0x74) return null
    val end = length - 4 // CRC
    val applicationType = (u(3) shl 8) or u(4)
    if (applicationType != 0x0010) return null
    val baseByLabel = HashMap<Int, String>()
    fun transport(d: Int, len: Int) {
        if (len < 3) return
        val protocol = (u(d) shl 8) or u(d + 1)
        val label = u(d + 2)
        if (protocol != 0x0003 || len < 4) return
        val baseLength = u(d + 3)
        if (4 + baseLength > len) return
        baseByLabel[label] = String(section, d + 4, baseLength, Charsets.UTF_8)
    }
    val commonLength = ((u(8) and 0x0F) shl 8) or u(9)
    var p = 10
    while (p + 2 <= minOf(10 + commonLength, end)) {
        val tag = u(p); val len = u(p + 1)
        if (p + 2 + len > end) break
        if (tag == 0x02) transport(p + 2, len)
        p += 2 + len
    }
    p = 10 + commonLength
    if (p + 2 > end) return emptyList()
    val loopEnd = minOf(p + 2 + (((u(p) and 0x0F) shl 8) or u(p + 1)), end)
    p += 2
    val apps = mutableListOf<HbbtvApplication>()
    while (p + 9 <= loopEnd) {
        val control = u(p + 6)
        val descriptorsEnd = minOf(p + 9 + (((u(p + 7) and 0x0F) shl 8) or u(p + 8)), loopEnd)
        val labels = mutableListOf<Int>()
        var name: String? = null
        var path = ""
        var d = p + 9
        while (d + 2 <= descriptorsEnd) {
            val tag = u(d); val len = u(d + 1)
            val body = d + 2
            if (body + len > descriptorsEnd) break
            when (tag) {
                0x02 -> { transport(body, len); if (len >= 3) labels += u(body + 2) }
                0x01 -> if (len >= 4 && name == null) {
                    val nameLength = u(body + 3)
                    if (4 + nameLength <= len) name = String(section, body + 4, nameLength, Charsets.UTF_8).trim()
                }
                0x15 -> path = String(section, body, len, Charsets.UTF_8)
            }
            d = body + len
        }
        val base = labels.firstNotNullOfOrNull { baseByLabel[it] } ?: baseByLabel.values.firstOrNull()
        if (base != null && control in 1..2) {
            // ETS 102 809: the URL is the base followed by the initial path, as broadcast.
            val url = base + path
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) apps += HbbtvApplication(url, name?.takeIf { it.isNotBlank() }, control)
        }
        p = descriptorsEnd
    }
    return apps.sortedBy { it.controlCode }
}

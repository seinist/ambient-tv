package com.ambient.tv.livetv.teletext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeletextDecoderTest {
    // --- encoders (the broadcaster's side) ---

    private fun ham(nibble: Int): Int {
        val d1 = nibble and 1; val d2 = (nibble shr 1) and 1; val d3 = (nibble shr 2) and 1; val d4 = (nibble shr 3) and 1
        val p1 = 1 xor d1 xor d3 xor d4
        val p2 = 1 xor d1 xor d2 xor d4
        val p3 = 1 xor d1 xor d2 xor d3
        val p4 = 1 xor (p1 xor d1 xor p2 xor d2 xor p3 xor d3 xor d4)
        return p1 or (d1 shl 1) or (p2 shl 2) or (d2 shl 3) or (p3 shl 4) or (d3 shl 5) or (p4 shl 6) or (d4 shl 7)
    }

    private fun odd(c: Int): Int = if (Integer.bitCount(c and 0x7F) % 2 == 0) (c and 0x7F) or 0x80 else c and 0x7F

    private fun reverse(v: Int): Int { var r = 0; for (b in 0 until 8) if (v and (1 shl b) != 0) r = r or (1 shl (7 - b)); return r }

    /** 44-byte data unit content in transmission (bit-reversed) order. */
    private fun unit(magazine: Int, row: Int, data: IntArray): ByteArray {
        val raw = IntArray(44)
        raw[0] = 0x02; raw[1] = 0xE4
        raw[2] = ham((magazine and 7) or ((row and 1) shl 3))
        raw[3] = ham(row shr 1)
        for (i in 0 until 40) raw[4 + i] = data.getOrElse(i) { odd(0x20) }
        return ByteArray(44) { reverse(raw[it]).toByte() }
    }

    private fun header(magazine: Int, tens: Int, units: Int, text: String, subcode: Int = 0, erase: Boolean = true,
                       subtitle: Boolean = false, national: Int = 1): ByteArray {
        val c12 = (national shr 2) and 1; val c13 = (national shr 1) and 1; val c14 = national and 1
        val data = IntArray(40)
        data[0] = ham(units); data[1] = ham(tens)
        data[2] = ham(subcode and 0xF)
        data[3] = ham(((subcode shr 4) and 7) or (if (erase) 8 else 0))
        data[4] = ham((subcode shr 8) and 0xF)
        data[5] = ham(((subcode shr 12) and 3) or (if (subtitle) 8 else 0))
        data[6] = ham(0)
        data[7] = ham((c12 shl 1) or (c13 shl 2) or (c14 shl 3))
        val padded = text.padEnd(32).take(32)
        for (i in 0 until 32) data[8 + i] = odd(padded[i].code)
        return unit(magazine, 0, data)
    }

    private fun row(magazine: Int, row: Int, codes: IntArray) = unit(magazine, row, IntArray(40) { odd(codes.getOrElse(it) { 0x20 }) })
    private fun text(s: String) = IntArray(s.length) { s[it].code }

    private fun pes(units: List<ByteArray>): ByteArray {
        val payload = java.io.ByteArrayOutputStream()
        payload.write(byteArrayOf(0x80.toByte(), 0x00, 0x00)) // flags, no PTS, header length 0
        payload.write(0x10) // EBU data
        units.forEach { payload.write(0x02); payload.write(0x2C); payload.write(it) }
        val body = payload.toByteArray()
        return byteArrayOf(0, 0, 1, 0xBD.toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
    }

    private fun ts(pid: Int, data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        var first = true
        while (offset < data.size) {
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            packet[2] = pid.toByte()
            packet[3] = 0x10
            val n = minOf(184, data.size - offset)
            System.arraycopy(data, offset, packet, 4, n)
            offset += n
            first = false
            out.write(packet)
        }
        return out.toByteArray()
    }

    private fun section(body: ByteArray): ByteArray {
        val length = body.size - 3 + 4
        body[1] = (0xB0 or (length shr 8)).toByte()
        body[2] = length.toByte()
        return byteArrayOf(0) + body + byteArrayOf(0, 0, 0, 0) // pointer field, …, dummy CRC
    }

    private val pat = section(byteArrayOf(0x00, 0, 0, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0x00))
    private val pmt = section(byteArrayOf(0x02, 0, 0, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
        0x06, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x0C,
        0x56, 0x0A, 'd'.code.toByte(), 'e'.code.toByte(), 'u'.code.toByte(), ((1 shl 3) or 1).toByte(), 0x00,
        'd'.code.toByte(), 'e'.code.toByte(), 'u'.code.toByte(), ((2 shl 3) or 1).toByte(), 0x50))

    private fun stream(vararg units: ByteArray): ByteArray =
        ts(0, pat) + ts(0x1000, pmt) + ts(0x101, pes(units.toList()))

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** PMT with teletext on 0x101 and an AIT (application_signalling_descriptor) on 0x102. */
    private val pmtWithAit = section(byteArrayOf(0x02, 0, 0, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00) +
        bytes(0x06, 0xE1, 0x01, 0xF0, 0x07, 0x56, 0x05, 'd'.code, 'e'.code, 'u'.code, (1 shl 3) or 1, 0x00) +
        bytes(0x05, 0xE1, 0x02, 0xF0, 0x05, 0x6F, 0x03, 0x00, 0x10, 0x01))

    private fun aitSection(base: String, path: String, name: String, control: Int = 1): ByteArray {
        val transportCommon = bytes(0x02, 5 + base.length, 0x00, 0x03, 0x01, base.length) + base.toByteArray() + bytes(0x00)
        val appDescriptors = bytes(0x02, 0x03, 0x00, 0x03, 0x01) +
            bytes(0x01, 4 + name.length, 'd'.code, 'e'.code, 'u'.code, name.length) + name.toByteArray() +
            bytes(0x15, path.length) + path.toByteArray()
        val app = bytes(0, 0, 0, 0x13, 0, 1, control, 0xF0 or (appDescriptors.size shr 8), appDescriptors.size and 0xFF) + appDescriptors
        val body = bytes(0x74, 0, 0, 0x00, 0x10, 0xC1, 0, 0, 0xF0 or (transportCommon.size shr 8), transportCommon.size and 0xFF) +
            transportCommon + bytes(0xF0 or (app.size shr 8), app.size and 0xFF) + app
        val length = body.size - 3 + 4
        body[1] = (0xB0 or (length shr 8)).toByte(); body[2] = length.toByte()
        return byteArrayOf(0) + body + byteArrayOf(0, 0, 0, 0)
    }

    // --- tests ---

    @Test fun hammingDecodesCorrectsAndRejects() {
        for (n in 0..15) {
            val encoded = ham(n)
            assertEquals(n, TeletextDecoder.hamming84(encoded))
            for (bit in 0 until 8) assertEquals("bit $bit of $n", n, TeletextDecoder.hamming84(encoded xor (1 shl bit)))
            assertEquals(-1, TeletextDecoder.hamming84(encoded xor 0b11))
        }
    }

    @Test fun findsTeletextThroughPatAndPmtAndAnnouncedPages() {
        val decoder = TeletextDecoder()
        decoder.feed(stream(header(1, 0, 0, "ARD Text")))
        assertEquals(0x101, decoder.teletextPid)
        assertEquals(listOf(TeletextAnnouncedPage(0x100, "deu", 1), TeletextAnnouncedPage(0x150, "deu", 2)), decoder.announcedPages)
        assertTrue(decoder.announcedPages[1].subtitle)
    }

    @Test fun pageIsCommittedByTheNextHeaderWithGermanCharacters() {
        val committed = mutableListOf<Int>()
        val decoder = TeletextDecoder(onPageCommitted = { committed += it.number })
        decoder.feed(stream(
            header(1, 0, 0, "ARD Text 12:00"),
            row(1, 1, text("Gr[n und \\l ~")),
            header(1, 0, 1, "ARD Text 12:00"),
        ))
        assertEquals(listOf(0x100), committed)
        val page = decoder.page(0x100)
        assertNotNull(page)
        val rows = layoutTeletextPage(page!!)
        assertEquals("GrÄn und Öl ß", rows[1].text().trimEnd())
        assertTrue(rows[0].text().contains("ARD Text 12:00"))
        assertEquals(listOf(0x100), decoder.pageNumbers())
    }

    @Test fun splitPacketsAndGarbageResync() {
        val decoder = TeletextDecoder()
        val bytes = byteArrayOf(1, 2, 3) + stream(header(1, 2, 3, "x"), row(1, 2, text("Hallo")), header(1, 9, 9, "x"))
        bytes.toList().chunked(37).forEach { decoder.feed(it.toByteArray()) }
        assertEquals("Hallo", layoutTeletextPage(decoder.page(0x123)!!)[2].text().trim())
    }

    @Test fun subtitlePageGivesBoxedLines() {
        val decoder = TeletextDecoder()
        decoder.feed(stream(
            header(1, 5, 0, "", subtitle = true),
            row(1, 20, intArrayOf(0x0D, 0x0B, 0x0B, 0x03) + text("Guten Abend") + intArrayOf(0x0A, 0x0A)),
            row(1, 21, text("verdeckt durch doppelte H|he")),
            row(1, 22, intArrayOf(0x0B, 0x0B, 0x07) + text("und willkommen") + intArrayOf(0x0A)),
            header(1, 0, 0, ""),
        ))
        val page = decoder.page(0x150)!!
        assertTrue(page.subtitle)
        assertEquals(listOf(TeletextSubtitleLine("Guten Abend", 3), TeletextSubtitleLine("und willkommen", 7)), teletextSubtitleLines(page))
        assertEquals(listOf(0x150), decoder.subtitlePagesSeen())
    }

    @Test fun mosaicsColoursAndHold() {
        val row = layoutTeletextRow(byteArrayOf(0x11, 0x7F, 0x1E, 0x04, 0x41, 0x1A, 0x15, 0x21), national = 1)
        assertEquals(-1, row.cells[0].mosaic)
        assertEquals(1, row.cells[1].foreground)
        assertEquals(0x3F, row.cells[1].mosaic) // 0x7F: all six sixels
        assertEquals(0x3F, row.cells[2].mosaic) // hold mosaics repeats it on the attribute
        assertEquals(0x3F, row.cells[3].mosaic) // "set-after": the alpha switch itself still shows the held mosaic
        assertEquals('A', row.cells[4].char)
        assertEquals(4, row.cells[4].foreground)
        assertTrue(row.cells[7].separated)
        assertEquals(1, row.cells[7].mosaic)
    }

    @Test fun flofLinksFollowRelativeMagazines() {
        val decoder = TeletextDecoder()
        val links = IntArray(40) { ham(0) }
        // Link 0 → 1 2 0 (same magazine), link 1 → 3 0 0 (magazine 1 xor 2 = 3).
        fun link(index: Int, tens: Int, units: Int, relative: Int) {
            val o = 1 + index * 6
            links[o] = ham(units); links[o + 1] = ham(tens); links[o + 2] = ham(0xF)
            links[o + 3] = ham(((relative and 1) shl 3) or 7); links[o + 4] = ham(0xF)
            links[o + 5] = ham(((relative shr 1) shl 2) or 3)
        }
        for (i in 0 until 6) link(i, 0xF, 0xF, 0)
        link(0, 2, 0, 0)
        link(1, 0, 0, 2)
        decoder.feed(stream(header(1, 0, 0, ""), unit(1, 27, links), header(1, 0, 1, "")))
        val page = decoder.page(0x100)!!
        assertEquals(0x120, page.links!![0])
        assertEquals(0x300, page.links!![1])
        assertEquals(0, page.links!![2])
    }

    @Test fun pageStepsAndNumbers() {
        assertEquals(0x150, teletextNumber(150))
        assertNull(teletextNumber(950))
        assertEquals(150, teletextDecimal(0x150))
        assertFalse(teletextDisplayable(0x1FF))
        assertEquals(0x200, teletextStepPage(0x150, listOf(0x100, 0x200), true))
        assertEquals(0x151, teletextStepPage(0x150, emptyList(), true))
        assertEquals(0x899, teletextStepPage(0x100, emptyList(), false))
    }

    @Test fun subtitleChoicePrefersAnnouncedPageInLanguage() {
        val announced = listOf(TeletextAnnouncedPage(0x777, "eng", 2), TeletextAnnouncedPage(0x150, "deu", 5))
        assertEquals(0x150, teletextSubtitlePage("auto", announced, emptyList(), setOf("de", "deu", "ger")))
        assertEquals(0x777, teletextSubtitlePage("auto", announced, emptyList(), emptySet()))
        assertEquals(0x888, teletextSubtitlePage("auto", emptyList(), listOf(0x888), emptySet()))
        assertEquals(0x150, teletextSubtitlePage("150", emptyList(), emptyList(), emptySet()))
        assertNull(teletextSubtitlePage("off", announced, emptyList(), emptySet()))
    }

    @Test fun aitAcrossTwoPacketsGivesTheHbbtvApplication() {
        val changes = mutableListOf<Int>()
        val decoder = TeletextDecoder(onServiceChanged = { changes += 1 })
        val longBase = "http://hbbtv.example.org/" + "a".repeat(160) + "/"
        decoder.feed(ts(0, pat) + ts(0x1000, pmtWithAit))
        assertTrue(decoder.pmtSeen)
        assertEquals(0x101, decoder.teletextPid)
        assertEquals(0x102, decoder.aitPid)
        assertFalse(decoder.probeComplete())
        decoder.feed(ts(0x102, aitSection(longBase, "index.html?ch=1", "ARD Startleiste")))
        assertTrue(decoder.probeComplete())
        assertEquals(listOf(HbbtvApplication(longBase + "index.html?ch=1", "ARD Startleiste", 1)), decoder.hbbtvApps)
    }

    @Test fun streamWithoutTeletextOrAitIsKnownAfterThePmt() {
        val decoder = TeletextDecoder()
        val pmtVideoOnly = section(byteArrayOf(0x02, 0, 0, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00) +
            bytes(0x1B, 0xE1, 0x00, 0xF0, 0x00))
        decoder.feed(ts(0, pat) + ts(0x1000, pmtVideoOnly))
        assertTrue(decoder.pmtSeen)
        assertTrue(decoder.probeComplete())
        assertEquals(-1, decoder.teletextPid)
        assertEquals(-1, decoder.aitPid)
    }

    @Test fun aitOfOtherApplicationTypesIsIgnored() {
        val section = aitSection("http://x/", "a", "n")
        section[1 + 4] = 0x01 // application_type 0x0001 (DVB-J)
        assertNull(parseAit(section.copyOfRange(1, section.size), section.size - 1))
        assertEquals(emptyList<HbbtvApplication>(), parseAit(aitSection("ftp://x/", "a", "n").let { it.copyOfRange(1, it.size) }, aitSection("ftp://x/", "a", "n").size - 1))
    }
}

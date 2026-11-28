package com.ambient.tv.games.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class GameRomTitlesTest {
    private fun reader(bytes: ByteArray): (Long, Int) -> ByteArray? = { offset, count ->
        if (offset < 0 || count < 0 || offset + count > bytes.size) null
        else bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
    }

    @Test fun gbAndGbaReadOnlyTheirCartridgeHeaderTitles() {
        val gb = ByteArray(0x160)
        "TETRIS".toByteArray(StandardCharsets.US_ASCII).copyInto(gb, 0x134)
        assertEquals("TETRIS", GameRomTitles.extract("gb", "tetris.gb", reader(gb)))

        val gba = ByteArray(0xC0)
        "GOLDENSUN".toByteArray(StandardCharsets.US_ASCII).copyInto(gba, 0xa0)
        assertEquals("GOLDENSUN", GameRomTitles.extract("gba", "golden.gba", reader(gba)))
    }

    @Test fun dsReadsBannerInsteadOfAbbreviatedHeaderAndRejectsMissingBanner() {
        val rom = ByteArray(0x2000)
        "SHORTCODE".toByteArray(StandardCharsets.US_ASCII).copyInto(rom)
        val banner = 0x1000
        ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN).putInt(0x68, banner)
        rom[banner] = 1
        "A Full Game Title\nPublisher".toByteArray(StandardCharsets.UTF_16LE)
            .copyInto(rom, banner + 0x240 + 0x100)
        assertEquals("A Full Game Title", GameRomTitles.extract("nds", "game.nds", reader(rom)))
        assertNull(GameRomTitles.extract("nds", "game.nds", reader(rom.copyOf(512))))
        ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN).putInt(0x68, -1)
        assertNull(GameRomTitles.extract("nds", "game.nds", reader(rom)))
    }

    @Test fun megaDrivePrefersInternationalLabel() {
        val rom = ByteArray(0x200)
        "JAPANESE NAME".toByteArray(StandardCharsets.US_ASCII).copyInto(rom, 0x120)
        "INTERNATIONAL GAME".toByteArray(StandardCharsets.US_ASCII).copyInto(rom, 0x150)
        assertEquals("INTERNATIONAL GAME",
            GameRomTitles.extract("megadrive", "game.md", reader(rom)))
    }

    @Test fun n64HandlesBigEndianAndByteSwappedRomHeaders() {
        val big = ByteArray(0x40)
        big[0] = 0x80.toByte(); big[1] = 0x37; big[2] = 0x12; big[3] = 0x40
        "MARIO KART 64".toByteArray(StandardCharsets.US_ASCII).copyInto(big, 0x20)
        val expected = GameRomTitles.extract("n64", "mario.z64", reader(big))
        assertEquals("MARIO KART 64", expected)
        val byteswapped = big.copyOf()
        for (i in byteswapped.indices step 2) {
            val tmp = byteswapped[i]; byteswapped[i] = byteswapped[i + 1]; byteswapped[i + 1] = tmp
        }
        assertEquals(expected, GameRomTitles.extract("n64", "mario.v64", reader(byteswapped)))
    }

    @Test fun snesLoRomChecksTitleAndChecksum() {
        val rom = ByteArray(0x8000)
        "SUPER METROID".toByteArray(StandardCharsets.US_ASCII).copyInto(rom, 0x7fc0)
        rom[0x7fc0 + 0x1c] = 0x34
        rom[0x7fc0 + 0x1d] = 0x12
        rom[0x7fc0 + 0x1e] = 0xcb.toByte()
        rom[0x7fc0 + 0x1f] = 0xed.toByte()
        assertEquals("SUPER METROID", GameRomTitles.extract("snes", "sm.sfc", reader(rom)))
    }

    @Test fun unsupportedAndInvalidHeadersAreNotPresentedAsVerifiedTitles() {
        assertNull(GameRomTitles.extract("nes", "tetris.nes", reader(ByteArray(256))))
        assertNull(GameRomTitles.extract("gba", "bad.gba", reader(ByteArray(300))))
        assertNull(GameRomTitles.extract("n64", "bad.z64", reader(ByteArray(64))))
        val binary = ByteArray(0x160) { 0x1f }
        assertNull(GameRomTitles.extract("gb", "garbage.gb", reader(binary)))
    }

    @Test fun pspIsoCsoAndZsoReadGameTitleWithoutReadingTheWholeImage() {
        val sector = 2048
        val iso = ByteArray(24 * sector)
        val data = ByteBuffer.wrap(iso).order(ByteOrder.LITTLE_ENDIAN)
        val pvd = 16 * sector
        iso[pvd] = 1
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(iso, pvd + 1)
        iso[pvd + 6] = 1
        fun record(at: Int, extent: Int, size: Int, name: String, directory: Boolean) {
            val bytes = name.toByteArray(StandardCharsets.US_ASCII)
            iso[at] = (33 + bytes.size + if (bytes.size % 2 == 0) 1 else 0).toByte()
            data.putInt(at + 2, extent)
            data.putInt(at + 10, size)
            iso[at + 25] = if (directory) 2 else 0
            iso[at + 32] = bytes.size.toByte()
            bytes.copyInto(iso, at + 33)
        }
        record(pvd + 156, 20, sector, "R", true)
        record(20 * sector, 21, sector, "PSP_GAME", true)
        record(21 * sector, 22, 256, "PARAM.SFO;1", false)
        val sfo = ByteBuffer.wrap(iso, 22 * sector, 256).slice().order(ByteOrder.LITTLE_ENDIAN)
        sfo.put(1, 'P'.code.toByte()); sfo.put(2, 'S'.code.toByte()); sfo.put(3, 'F'.code.toByte())
        sfo.putInt(8, 0x24); sfo.putInt(12, 0x40); sfo.putInt(16, 1)
        sfo.putShort(20, 0); sfo.putShort(22, 0x0204.toShort())
        sfo.putInt(24, 16); sfo.putInt(28, 16); sfo.putInt(32, 0)
        "TITLE".toByteArray(StandardCharsets.US_ASCII).copyInto(iso, 22 * sector + 0x24)
        "Portable Game".toByteArray(StandardCharsets.UTF_8).copyInto(iso, 22 * sector + 0x40)
        assertEquals("Portable Game", GameRomTitles.extract("psp", "opaque.iso", reader(iso)))

        for ((magic, extension) in listOf("CISO" to "cso", "ZISO" to "zso")) {
            val payload = java.io.ByteArrayOutputStream()
            val index = IntArray(25)
            val indexEnd = 24 + index.size * 4
            for (i in 0 until 24) {
                val raw = iso.copyOfRange(i * sector, (i + 1) * sector)
                index[i] = indexEnd + payload.size()
                if (i != 16) {
                    index[i] = index[i] or Int.MIN_VALUE
                    payload.write(raw)
                } else if (magic == "CISO") {
                    val deflater = java.util.zip.Deflater(6, true)
                    try {
                        deflater.setInput(raw); deflater.finish()
                        val packed = ByteArray(sector + 64)
                        val length = deflater.deflate(packed)
                        payload.write(packed, 0, length)
                    } finally { deflater.end() }
                } else {
                    // One legal LZ4 final literal sequence, deliberately not compressible.
                    payload.write(0xf0)
                    var extra = sector - 15
                    while (extra >= 255) { payload.write(255); extra -= 255 }
                    payload.write(extra); payload.write(raw)
                }
            }
            index[24] = indexEnd + payload.size()
            val header = ByteBuffer.allocate(indexEnd).order(ByteOrder.LITTLE_ENDIAN)
            header.put(magic.toByteArray(StandardCharsets.US_ASCII)); header.putInt(24)
            header.putLong(iso.size.toLong()); header.putInt(sector); header.put(1.toByte())
            header.position(24); index.forEach { header.putInt(it) }
            val image = header.array() + payload.toByteArray()
            var bytesRead = 0
            val read = reader(image)
            assertEquals("Portable Game", GameRomTitles.extract("psp", "opaque.$extension") { offset, count ->
                bytesRead += count
                read(offset, count)
            })
            org.junit.Assert.assertTrue(bytesRead < image.size / 2)
        }
    }

    @Test fun pspPbpReadsBoundedParamSfoTitle() {
        val pbp = ByteArray(0x200)
        val hdr = ByteBuffer.wrap(pbp).order(ByteOrder.LITTLE_ENDIAN)
        pbp[0] = 0; pbp[1] = 'P'.code.toByte(); pbp[2] = 'B'.code.toByte(); pbp[3] = 'P'.code.toByte()
        hdr.putInt(8, 40); hdr.putInt(12, 0x140)
        val sfo = ByteBuffer.wrap(pbp, 40, 0x100).slice().order(ByteOrder.LITTLE_ENDIAN)
        sfo.put(0, 0); sfo.put(1, 'P'.code.toByte()); sfo.put(2, 'S'.code.toByte()); sfo.put(3, 'F'.code.toByte())
        sfo.putInt(8, 0x24); sfo.putInt(12, 0x40); sfo.putInt(16, 1)
        sfo.putShort(20, 0); sfo.putShort(22, 0x0204.toShort())
        sfo.putInt(24, 16); sfo.putInt(28, 16); sfo.putInt(32, 0)
        "TITLE".toByteArray(StandardCharsets.US_ASCII).copyInto(pbp, 40 + 0x24)
        "Portable Game".toByteArray(StandardCharsets.UTF_8).copyInto(pbp, 40 + 0x40)
        assertEquals("Portable Game", GameRomTitles.extract("psp", "game.pbp", reader(pbp)))
    }
}

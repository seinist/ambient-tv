package com.ambient.tv.games.library

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GameRomDatReaderTest {
    @Test fun noIntroAndRedumpXmlExtractOnlyValidatedNamesAndCrcs() {
        val data = """
            <?xml version="1.0"?>
            <datafile>
              <game name="Chrono Trigger (USA)"><rom name="Chrono Trigger.sfc" crc="deadbeef" size="123" /></game>
              <machine name="Sonic the Hedgehog"><rom name="sonic.md" crc="1234ABCD" /></machine>
              <game name="Invalid"><rom name="no-crc.zip" crc="NOTACRC" /></game>
            </datafile>
        """.trimIndent()
        val seen = mutableListOf<GameRomDatReader.Entry>()
        val count = GameRomDatReader.parse(ByteArrayInputStream(data.toByteArray()), seen::add)
        assertEquals(2, count)
        assertEquals("DEADBEEF", seen[0].crc32)
        assertEquals("Chrono Trigger (USA)", seen[0].title)
        assertEquals("Sonic the Hedgehog", seen[1].title)
    }

    @Test fun retroArchClrMameProDatGivesEachRomTheGameName() {
        // As in libretro-database: a header block, several roms per game, keys without a value.
        val data = "\uFEFF" + """
            clrmamepro (
                name "Nintendo - Nintendo Entertainment System"
                version "2026.11.14"
            )

            game (
                name "Duck Tales (USA)"
                region "USA"
                rom ( name "Duck Tales (USA).nes" size 131088 crc 1A2B3C4D md5 00 sha1 00 )
                rom ( name "Duck Tales (USA).unh" size 131072 crc deadbeef )
            )
            game (
                name "No Checksum (Japan)"
                rom ( name "No Checksum (Japan).nes" size 16 )
                comment ( "ignored" ( nested ) )
            )
            game (
                rom ( name "Only Rom Name (Europe).gba" crc 0000ABCD )
            )
        """.trimIndent()
        val seen = mutableListOf<GameRomDatReader.Entry>()
        val count = GameRomDatReader.parse(ByteArrayInputStream(data.toByteArray()), seen::add)
        assertEquals(3, count)
        assertEquals(GameRomDatReader.Entry("1A2B3C4D", "Duck Tales (USA)"), seen[0])
        assertEquals(GameRomDatReader.Entry("DEADBEEF", "Duck Tales (USA)"), seen[1])
        assertEquals(GameRomDatReader.Entry("0000ABCD", "Only Rom Name (Europe)"), seen[2])
    }

    @Test fun xmlAfterWhitespaceIsStillReadAsXml() {
        val data = "\n  <datafile><game name=\"Tetris (World)\"><rom name=\"t.gb\" crc=\"46DF91AD\"/></game></datafile>"
        val seen = mutableListOf<GameRomDatReader.Entry>()
        GameRomDatReader.parse(ByteArrayInputStream(data.toByteArray()), seen::add)
        assertEquals(listOf(GameRomDatReader.Entry("46DF91AD", "Tetris (World)")), seen)
    }

    @Test fun doctypeIsRejectedRatherThanFollowingExternalEntities() {
        val data = "<!DOCTYPE datafile [<!ENTITY exploit SYSTEM \"file:///etc/passwd\">]><datafile />"
        assertThrows(Exception::class.java) {
            GameRomDatReader.parse(ByteArrayInputStream(data.toByteArray())) { }
        }
    }
}

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

    @Test fun doctypeIsRejectedRatherThanFollowingExternalEntities() {
        val data = "<!DOCTYPE datafile [<!ENTITY exploit SYSTEM \"file:///etc/passwd\">]><datafile />"
        assertThrows(Exception::class.java) {
            GameRomDatReader.parse(ByteArrayInputStream(data.toByteArray())) { }
        }
    }
}

package com.ambient.tv.games.metadata

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GameGamelistReaderTest {
    @Test fun readsPortableTextAndImagePathsWithoutFollowingThem() = runBlocking {
        val records = mutableListOf<GameGamelistEntry>()
        val xml = """<gameList><game>
            <path>./nested/game.sfc</path><name>Game &amp; Friends</name>
            <desc><![CDATA[First line
            Second line]]></desc><releasedate>19940301T000000</releasedate>
            <genre>Action / Platform</genre><developer>Developer</developer><publisher>Publisher</publisher>
            <players>1-2</players><image>./images/cover.png</image><fanart>./images/bg.jpg</fanart>
            </game></gameList>"""
        val count = GameGamelistReader.parse(ByteArrayInputStream(xml.toByteArray())) { records += it }
        assertEquals(1, count)
        val entry = records.single()
        assertEquals("Game & Friends", entry.metadata.title)
        assertEquals(1994, entry.metadata.year)
        assertEquals(listOf("Action", "Platform"), entry.metadata.genres)
        assertEquals("./images/cover.png", entry.cover)
        assertEquals("./images/bg.jpg", entry.backdrop)
        assertTrue(entry.metadata.summary.contains("Second line"))
    }

    @Test fun rejectsDocumentTypesRatherThanResolvingExternalEntities() = runBlocking {
        val xml = """<!DOCTYPE gameList [<!ENTITY xxe SYSTEM "file:///private/secret">]>
            <gameList><game><path>game.sfc</path><name>&xxe;</name></game></gameList>"""
        var rejected = false
        try { GameGamelistReader.parse(ByteArrayInputStream(xml.toByteArray())) { fail("DTD entry must not be imported") } }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun boundedFieldsPreventUnboundedDescriptionAllocation() = runBlocking {
        val xml = "<gameList><game><path>game.sfc</path><desc>" + "x".repeat(16_001) + "</desc></game></gameList>"
        var rejected = false
        try { GameGamelistReader.parse(ByteArrayInputStream(xml.toByteArray())) {} }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
    }
}

package com.ambient.tv.data.iptv

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class IptvParsersTest {
    @Test fun m3uReadsAttributesNamesGroupsAndOptions() {
        val playlist = M3uParser.parse("""
            #EXTM3U url-tvg="http://epg.example/a.xml.gz,http://epg.example/b.xml"
            #EXTINF:-1 tvg-id="das.erste" tvg-name="Das Erste, HD" tvg-logo="http://logo/1.png" group-title="Vollprogramm" tvg-chno="1",Das Erste HD
            #EXTVLCOPT:http-user-agent=Kodi/21
            http://stream.example/1.m3u8
            #EXTINF:-1,Radio Eins
            #EXTGRP:Radio
            https://stream.example/radio.mp3
            #EXTINF:-1,Broken
            file:///etc/passwd
        """.trimIndent())
        assertEquals(listOf("http://epg.example/a.xml.gz", "http://epg.example/b.xml"), playlist.epgUrls)
        assertEquals(2, playlist.channels.size)
        val first = playlist.channels[0]
        assertEquals("Das Erste HD", first.name)
        assertEquals("Das Erste, HD", first.tvgName)
        assertEquals("das.erste", first.tvgId)
        assertEquals(1, first.number)
        assertEquals("Vollprogramm", first.group)
        assertEquals("Kodi/21", first.headers["User-Agent"])
        assertEquals("Radio", playlist.channels[1].group)
        assertEquals(2, playlist.channels[1].number)
        assertNotEquals(first.uuid, playlist.channels[1].uuid)
    }

    @Test fun xmltvKeepsTheWindowAndAcceptedChannels() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd" [ <!ENTITY boom "x"> ]>
            <tv generator-info-name="test">
              <!-- a comment with <tags> -->
              <channel id="das.erste"><display-name lang="de">Das Erste</display-name><icon src="http://logo/1.png"/></channel>
              <channel id="other"><display-name>Other</display-name></channel>
              <programme start="20261014200000 +0200" stop="20261014201500 +0200" channel="das.erste">
                <title lang="de">Tagesschau</title><sub-title>Nachrichten</sub-title><desc><![CDATA[News & <mehr>]]></desc>
              </programme>
              <programme start="20261014201500 +0200" stop="20261014220000 +0200" channel="das.erste"><title>Tatort &amp; Co</title></programme>
              <programme start="20261020200000 +0200" stop="20261020210000 +0200" channel="das.erste"><title>Too late</title></programme>
              <programme start="20261014200000 +0200" stop="20261014210000 +0200" channel="other"><title>Filtered</title></programme>
            </tv>"""
        val from = XmltvParser.time("20261014000000 +0200")!!
        val to = XmltvParser.time("20261015000000 +0200")!!
        val seen = mutableListOf<String>()
        val guide = XmltvParser.parse(StringReader(xml), from, to, accept = { it == "das.erste" }, onChannel = { seen += it.id })
        assertEquals(listOf("das.erste", "other"), seen)
        assertEquals(listOf("Tagesschau", "Tatort & Co"), guide.programmes.map { it.title })
        val news = guide.programmes.first()
        assertEquals("Nachrichten", news.subtitle)
        assertEquals("News & <mehr>", news.description)
        assertEquals(15 * 60L, news.stopEpochSeconds - news.startEpochSeconds)
        assertEquals("http://logo/1.png", guide.channels.first().icon)
    }

    @Test fun timesWithoutOffsetAreUtc() {
        assertEquals(0L, XmltvParser.time("19700101000000"))
        assertEquals(3600L, XmltvParser.time("19700101020000 +0100"))
        assertNull(XmltvParser.time("tomorrow"))
    }

    @Test fun guideChannelsMatchByIdThenName() {
        val channels = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"zdf.de\",ZDF HD\nhttp://s/zdf\n#EXTINF:-1,Arte\nhttp://s/arte").channels
        val matcher = IptvGuideMatcher(channels)
        assertEquals("ZDF HD", matcher.match(null, "ZDF.de")?.name)
        assertEquals("Arte", matcher.match(XmltvChannel("arte.fr", listOf("ARTE"), null), "arte.fr")?.name)
        assertNull(matcher.match(XmltvChannel("x", listOf("Unknown"), null), "x"))
    }

    @Test fun iptvSourceValidation() {
        IptvSource(id = "a", name = "Provider", playlistUrl = "https://p.example/get.php?u=1").validated()
        assertThrows(IllegalArgumentException::class.java) { IptvSource(id = "a", name = "P", playlistUrl = "file:///sdcard/x.m3u").validated() }
        assertThrows(IllegalArgumentException::class.java) { IptvSource(id = "a", name = "P", playlistUrl = "http://p/x", userAgent = "a\nb").validated() }
    }
}

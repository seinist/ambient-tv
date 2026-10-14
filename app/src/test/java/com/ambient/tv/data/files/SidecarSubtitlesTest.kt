package com.ambient.tv.data.files

import org.junit.Assert.*
import org.junit.Test

class SidecarSubtitlesTest {
    private fun entry(name: String, folder: String = "Movies", size: Long = 40_000) = FileEntry("nas", "$folder/$name", name, false, size)

    @Test fun matchesKodiStyleNamesNextToTheVideo() {
        val siblings = listOf(
            entry("Movie (2020).mkv"), entry("Movie (2020).srt"), entry("Movie (2020).de.srt"),
            entry("Movie (2020).en.forced.srt"), entry("Movie (2020).German.SDH.ass"), entry("Movie (2020).pt-BR.vtt"),
            entry("Other Movie.de.srt"), entry("Movie (2020)-trailer.mkv"), entry("Movie (2020).nfo"),
        )
        val found = SidecarSubtitles.match("Movie (2020).mkv", siblings)
        assertEquals(setOf("Movie (2020).srt", "Movie (2020).de.srt", "Movie (2020).en.forced.srt", "Movie (2020).German.SDH.ass", "Movie (2020).pt-BR.vtt"),
            found.map { it.name }.toSet())
        val byName = found.associateBy { it.name }
        assertEquals("de", byName.getValue("Movie (2020).de.srt").languageCode)
        assertTrue(byName.getValue("Movie (2020).en.forced.srt").forced)
        assertEquals("en", byName.getValue("Movie (2020).en.forced.srt").languageCode)
        assertEquals("de", byName.getValue("Movie (2020).German.SDH.ass").languageCode)
        assertTrue(byName.getValue("Movie (2020).German.SDH.ass").hearingImpaired)
        assertEquals("pt-BR", byName.getValue("Movie (2020).pt-BR.vtt").languageCode)
        assertNull(byName.getValue("Movie (2020).srt").languageCode)
    }

    @Test fun threeLetterAndBibliographicCodes() {
        assertEquals("de", SidecarSubtitles.languageFrom("ger", listOf("ger")))
        assertEquals("de", SidecarSubtitles.languageFrom("deu", listOf("deu")))
        assertEquals("fr", SidecarSubtitles.languageFrom("fre", listOf("fre")))
        assertNull(SidecarSubtitles.languageFrom("hi", listOf("hi")))
    }

    @Test fun perTitleSubsFolderTakesEveryFile() {
        val found = SidecarSubtitles.match("Movie.mkv", listOf(entry("2_English.srt", "Movies/Subs/Movie"), entry("3_German.srt", "Movies/Subs/Movie")), wholeFolder = true)
        assertEquals(listOf("en", "de").sorted(), found.map { it.languageCode }.sortedBy { it })
    }

    @Test fun hugeFilesAreNotSubtitles() {
        assertTrue(SidecarSubtitles.match("Movie.mkv", listOf(entry("Movie.srt", size = 50_000_000))).isEmpty())
    }

    @Test fun subtitleTextBecomesUtf8() {
        val latin = "Grüße – Straße".toByteArray(java.nio.charset.Charset.forName("windows-1252"))
        assertEquals("Grüße – Straße", String(SubtitleText.utf8(latin), Charsets.UTF_8))
        val utf8 = "Grüße".toByteArray(Charsets.UTF_8)
        assertArrayEquals(utf8, SubtitleText.utf8(utf8))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertArrayEquals(utf8, SubtitleText.utf8(bom))
        val utf16 = "Grüße".toByteArray(Charsets.UTF_16) // with BOM
        assertEquals("Grüße", String(SubtitleText.utf8(utf16), Charsets.UTF_8))
    }

    @Test fun topLevelLocalDocumentsUseTheTreeRoot() {
        val local = FileSource(name = "USB", kind = "local", treeUri = "content://x/tree/primary%3A")
        val nas = FileSource(name = "NAS", kind = "smb", host = "nas", share = "media")
        assertEquals("ROOT", parentFolder(local, "primary:movie.mkv") { "ROOT" })
        assertEquals("primary:Movies", parentFolder(local, "primary:Movies/movie.mkv") { "ROOT" })
        assertEquals("", parentFolder(nas, "movie.mkv") { "ROOT" })
    }
}

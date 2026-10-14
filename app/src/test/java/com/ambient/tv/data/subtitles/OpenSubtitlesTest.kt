package com.ambient.tv.data.subtitles

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class OpenSubtitlesTest {
    private val base = "https://api.opensubtitles.com/api/v1".toHttpUrl()

    @Test fun hashAddsSizeAndLittleEndianWordsOfBothEnds() {
        val head = ByteArray(65536) { (it % 256).toByte() }
        val tail = ByteArray(65536) { ((it * 7) % 256).toByte() }
        // Reference computed independently (Python struct '<Q').
        assertEquals("20202020202e4240", OpenSubtitlesHash.of(1_000_000, head, tail))
    }

    @Test fun parametersAreSortedAndLowerCase() {
        val movie = searchUrl(base, SubtitleSearchQuery("The Matrix", year = 1999, movieHash = "8e245d9679d31e12", languages = listOf("EN", "de", "de")))
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?languages=de%2Cen&moviehash=8e245d9679d31e12&query=the%20matrix&year=1999", movie.toString())
        val episode = searchUrl(base, SubtitleSearchQuery("Dark", year = 2017, season = 1, episode = 2, imdbId = "tt5753856"))
        assertEquals(listOf("imdb_id"), episode.queryParameterNames.toList())
        assertEquals("5753856", episode.queryParameter("imdb_id"))
        val byTitle = searchUrl(base, SubtitleSearchQuery("Dark", season = 1, episode = 2))
        assertEquals(listOf("episode_number", "query", "season_number"), byTitle.queryParameterNames.toList())
    }

    @Test fun resultsPutExactMatchesFirst() {
        val body = """{"data":[
            {"attributes":{"language":"de","release":"Popular","download_count":900,"hearing_impaired":false,"foreign_parts_only":false,"moviehash_match":false,"files":[{"file_id":1,"file_name":"a.srt"}]}},
            {"attributes":{"language":"de","release":"Exact","download_count":10,"hearing_impaired":true,"foreign_parts_only":false,"moviehash_match":true,"files":[{"file_id":2,"file_name":"b.srt"}]}},
            {"attributes":{"language":"en","release":"No files","files":[]}}]}"""
        val results = parseSearch(body)
        assertEquals(listOf(2L, 1L), results.map { it.fileId })
        assertTrue(results.first().hearingImpaired && results.first().hashMatch)
        assertEquals("srt", results.first().format)
    }
}

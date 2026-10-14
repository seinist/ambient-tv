package com.ambient.tv.tvheadend.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TvheadendUrlSchemeTest {
    @Test
    fun hostWithoutScheme_defaultsToHttp() {
        assertEquals("http", TvheadendUrlScheme.resolve("tvh.local", useHttps = false))
        assertEquals("http", TvheadendUrlScheme.resolve("192.168.1.10", useHttps = false))
    }

    @Test
    fun hostWithoutScheme_toggleForcesHttps() {
        assertEquals("https", TvheadendUrlScheme.resolve("tvh.local", useHttps = true))
    }

    @Test
    fun explicitHttpsPrefix_winsOverToggleOff() {
        assertEquals("https", TvheadendUrlScheme.resolve("https://tvh.local/path", useHttps = false))
    }

    @Test
    fun explicitHttpPrefix_winsOverToggleOn() {
        assertEquals("http", TvheadendUrlScheme.resolve("http://tvh.local", useHttps = true))
    }

    @Test
    fun prefixMatchIsCaseInsensitive() {
        assertEquals("https", TvheadendUrlScheme.resolve("HTTPS://TVH.LOCAL", useHttps = false))
        assertEquals("http", TvheadendUrlScheme.resolve("HTTP://TVH.LOCAL", useHttps = true))
    }
}

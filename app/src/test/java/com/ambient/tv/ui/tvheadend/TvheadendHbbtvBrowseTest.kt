package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvHbbtvApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvheadendHbbtvBrowseTest {
    @Test fun multipleApplicationsAreShownForOneChannelWithStableKeys() {
        val apps = listOf(
            LiveTvHbbtvApp("https://b.example", "B", "apps"),
            LiveTvHbbtvApp("https://a.example", "A", "all"),
        )
        val rows = tvheadendHbbtvEntries(listOf(LiveTvChannel("abc", "ARD", 1, null, hbbtvApps = apps)))
        assertEquals(2, rows.size)
        assertEquals(listOf("A", "B"), rows.map { it.app.name })
        assertTrue(rows.all { it.key.startsWith("abc:") })
    }

    @Test fun noBrowsableApplicationMeansNoStandaloneMenu() {
        val hidden = LiveTvChannel("no", "No HbbTV", 1, null,
            hbbtvApps = listOf(LiveTvHbbtvApp("https://hidden.example", "Hidden", "reserved")))
        assertTrue(tvheadendHbbtvEntries(listOf(hidden)).isEmpty())
    }
}

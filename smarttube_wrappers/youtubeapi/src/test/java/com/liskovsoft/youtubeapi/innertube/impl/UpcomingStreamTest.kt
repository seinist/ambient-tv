package com.liskovsoft.youtubeapi.innertube.impl

import com.google.gson.Gson
import com.liskovsoft.youtubeapi.innertube.models.PlayerResult
import org.junit.Assert.*
import org.junit.Test

class UpcomingStreamTest {
    private fun info(status: String, slate: String = "") = MediaItemFormatInfoImpl(Gson().fromJson(
        """{"playabilityStatus":{"status":"$status"$slate}}""", PlayerResult::class.java))

    @Test fun `offline slate exposes schedule without streamingData`() {
        val value = info("LIVE_STREAM_OFFLINE", """,
            "liveStreamability":{"liveStreamabilityRenderer":{"offlineSlate":{
                "liveStreamOfflineSlateRenderer":{"scheduledStartTime":"1600977300"}}}}""")
        assertTrue(value.isLiveOffline())
        assertEquals("1600977300", value.getScheduledStartTime())
    }
    @Test fun `offline without a date is still an expected waiting state`() {
        val value = info("LIVE_STREAM_OFFLINE")
        assertTrue(value.isLiveOffline())
        assertNull(value.getScheduledStartTime())
    }
    @Test fun `playable and restricted videos are not classified as upcoming`() {
        for (status in listOf("OK", "ERROR", "UNPLAYABLE", "LOGIN_REQUIRED")) {
            assertFalse(info(status).isLiveOffline())
        }
    }
    @Test fun `feed retains machine readable schedule even without a subtitle`() {
        val item = Gson().fromJson("""{"videoRenderer":{"upcomingEventData":{"startTime":"1600977300"}}}""",
            com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper::class.java)
        assertEquals("1600977300", com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem(item).getUpcomingEventStartTime())
    }
}

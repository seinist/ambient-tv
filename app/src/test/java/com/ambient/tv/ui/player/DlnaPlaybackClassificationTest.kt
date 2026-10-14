package com.ambient.tv.ui.player

import com.ambient.tv.data.dlna.DlnaKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaPlaybackClassificationTest {
    @Test fun dlnaVideoWithoutCodecMetadataKeepsAVideoSurface() {
        assertFalse(audioOnlyPlaybackHint(DlnaKeys.key("server-1", "video-1"), "movie", null))
    }

    @Test fun dlnaAudioStillUsesTheAudioPresentation() {
        assertTrue(audioOnlyPlaybackHint(DlnaKeys.key("server-1", "track-1"), "track", null))
    }
}

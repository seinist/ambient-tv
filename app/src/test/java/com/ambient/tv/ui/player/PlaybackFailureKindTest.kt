package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureKindTest {
    @Test fun genericDecoderFailureCannotEnableVideoTranscoding() {
        assertEquals(PlaybackFailureKind.UNKNOWN, playbackFailureKind(null, null, "Decoder init failed"))
        assertEquals(PlaybackFailureKind.AUDIO, playbackFailureKind("MediaCodecAudioRenderer", null, "Decoder init failed"))
        assertEquals(PlaybackFailureKind.AUDIO, playbackFailureKind(null, "audio/true-hd", "Decoder init failed"))
        assertEquals(PlaybackFailureKind.VIDEO, playbackFailureKind(null, "video/hevc", "Decoder init failed"))
    }
}

package com.ambient.tv.ui.player

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3BufferTargetTest {
    private val mb = 1024 * 1024

    @Test
    fun `buffer target is a quarter of the heap within bounds`() {
        assertEquals(32 * mb, media3TargetBufferBytes(96))
        assertEquals(48 * mb, media3TargetBufferBytes(192))
        assertEquals(64 * mb, media3TargetBufferBytes(256))
        assertEquals(DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE, media3TargetBufferBytes(1024))
        assertEquals(DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE, media3TargetBufferBytes(0))
    }
}

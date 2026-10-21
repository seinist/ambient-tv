package com.ambient.tv.spotify

import com.ambient.tv.spotify.playback.LibrespotNativeStateBus
import dev.rusty.app.SpotifyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class SpotifyNativeCallbackTest {
    @Test fun rustyPlaybackCallbackSignaturesPublishState() {
        val originalParameters = arrayOf(
            String::class.java, String::class.java, String::class.java,
            Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
            String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
            String::class.java, String::class.java,
        )
        for (newCore in listOf(false, true)) {
            val parameters = if (newCore) originalParameters + Boolean::class.javaPrimitiveType!! else originalParameters
            val callback = SpotifyService::class.java.getDeclaredMethod("onNativePlaybackEvent", *parameters)
            assertTrue(Modifier.isStatic(callback.modifiers))
            assertEquals(Void.TYPE, callback.returnType)
            val arguments = arrayOf<Any?>("PLAYING", "Selected track", "Artist", 123L, 456L, null, null, false, null, "track-id")
            try {
                callback.invoke(null, *(if (newCore) arguments + true else arguments))
                val state = LibrespotNativeStateBus.state.value
                assertTrue(state.playing)
                assertEquals("track-id", state.current?.id)
                assertEquals(123L, state.positionMs)
            } finally {
                LibrespotNativeStateBus.disconnected()
            }
        }
    }
}

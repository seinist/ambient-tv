package com.ambient.tv.companion

import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.player.PlaybackSeekStopTest
import com.ambient.tv.ui.player.PlaybackStatus
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class CompanionPreviousTest {
    private class Clock : PlaybackHandle {
        val seeks = mutableListOf<Long>()
        override fun status() = PlaybackStatus(positionMs = 45_000, durationMs = 120_000, playing = true)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun release() = Unit
    }

    private lateinit var manager: CompanionManager
    private val owner = Any()
    private val starts = mutableListOf<CompanionFrame>()

    @Before fun setUp() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
        CompanionQueuePlayback.pending = false
        manager = CompanionManager(RuntimeEnvironment.getApplication(), null, null)
        // Exercise the real state/command methods without discovery, collectors or queue storage.
        (field("scope").get(manager) as CoroutineScope).cancel()
        field("television").set(manager, true)
        field("foreground").set(manager, true)
        field("identityAlias").set(manager, "previous-regression")
        manager.attach { starts += it }
    }

    @After fun tearDown() {
        CompanionPlaybackControls.remove(owner)
        manager.detach()
        CompanionQueuePlayback.pending = false
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
    }

    private fun field(name: String) = CompanionManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun mount(
        source: String = "youtube",
        backend: String = "media3",
        offset: Long = 0,
        controls: CompanionPlaybackControls.SourceControls = CompanionPlaybackControls.SourceControls(),
    ): Clock {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
        val metadata = PlaybackMetadata(
            route = "$source/current", title = "Current video", source = source, mediaId = "current",
            durationMs = 120_000, timelineOffsetMs = offset,
            companionMedia = CompanionMedia(if (source == "youtube") "youtube" else "library", "current"),
        )
        val clock = Clock()
        BackgroundPlaybackSession.acquire(RuntimeEnvironment.getApplication(), metadata, backend, Any()) { clock }
        CompanionPlaybackControls.update(owner, metadata.route, controls)
        return clock
    }

    private fun state(): CompanionFrame = CompanionManager::class.java
        .getDeclaredMethod("playbackState", Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(manager, 0) as CompanionFrame

    private fun previous(observed: CompanionFrame): Result<Unit> = runCatching {
        runBlocking {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                try {
                    CompanionManager::class.java
                        .getDeclaredMethod("applyPlaybackCommand", CompanionFrame::class.java, Continuation::class.java)
                        .apply { isAccessible = true }
                        .invoke(manager, CompanionFrame(type = "previous", media = observed.media,
                            playbackSessionId = observed.playbackSessionId), continuation)
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            }
        }
    }

    @Test fun youtubeWithoutHistoryNeitherOffersPreviousNorExecutesAStartOverForAnyRenderer() {
        for (backend in listOf("media3", "vlc", "mpv")) {
            for (sourceSeek in listOf(false, true)) {
                val sourceSeeks = mutableListOf<Long>()
                val clock = mount(backend = backend, controls = CompanionPlaybackControls.SourceControls(
                    seek = if (sourceSeek) ({ sourceSeeks += it }) else null,
                ))
                val observed = state()
                val seekVersion = BackgroundPlaybackSession.externalSeekVersion
                assertTrue(observed.seekable)
                assertFalse("previous" in observed.capabilities)
                assertTrue(previous(observed).exceptionOrNull() is IllegalArgumentException)
                assertTrue(clock.seeks.isEmpty())
                assertTrue(sourceSeeks.isEmpty())
                assertEquals(seekVersion, BackgroundPlaybackSession.externalSeekVersion)
            }
        }
    }

    @Test fun actualSourceAndQueuePreviousRemainUsableAndAnExhaustedHistoryRejectsTheOldCommand() {
        var sourcePrevious = 0
        val clock = mount(controls = CompanionPlaybackControls.SourceControls(previous = { sourcePrevious++ }))
        val queue = field("playbackQueue").get(manager) as CompanionPlaybackQueue
        val older = CompanionMedia("youtube", "older")
        queue.remember(older)
        val observed = state()
        assertTrue("previous" in observed.capabilities)
        previous(observed).getOrThrow()
        assertEquals(1, sourcePrevious)
        assertTrue(queue.hasPrevious)
        assertTrue(starts.isEmpty())

        CompanionPlaybackControls.update(owner, "youtube/current", CompanionPlaybackControls.SourceControls())
        assertTrue("previous" in state().capabilities)
        previous(observed).getOrThrow()
        assertEquals(listOf(older), starts.map { it.media })
        assertFalse(queue.hasPrevious)
        assertFalse("previous" in state().capabilities)
        val seekVersion = BackgroundPlaybackSession.externalSeekVersion
        assertTrue(previous(observed).exceptionOrNull() is IllegalArgumentException)
        assertTrue(clock.seeks.isEmpty())
        assertEquals(seekVersion, BackgroundPlaybackSession.externalSeekVersion)
    }

    @Test fun nonYoutubePreviousKeepsBothNativeAndSourceSeekStartOver() {
        for (sourceSeek in listOf(false, true)) {
            val sourceSeeks = mutableListOf<Long>()
            val clock = mount(source = "plex", offset = if (sourceSeek) 20_000 else 0,
                controls = CompanionPlaybackControls.SourceControls(
                    seek = if (sourceSeek) ({ sourceSeeks += it }) else null,
                ))
            val observed = state()
            assertTrue("previous" in observed.capabilities)
            previous(observed).getOrThrow()
            assertEquals(if (sourceSeek) emptyList<Long>() else listOf(0L), clock.seeks)
            assertEquals(if (sourceSeek) listOf(0L) else emptyList<Long>(), sourceSeeks)
        }
    }
}

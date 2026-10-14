package com.ambient.tv.ui.player

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSubtitleLifecycleTest {
    private fun snapshot(generation: Long, route: String = "movie") = BackgroundPlaybackSession.Snapshot(
        metadata = PlaybackMetadata(route, route, source = "plex", mediaId = route), generation = generation,
    )

    @Test fun aLateDownloadCannotAttachToTheNextPlaybackOrAReopenedTitle() = runTest {
        for (next in listOf(snapshot(2, "next"), snapshot(2), BackgroundPlaybackSession.Snapshot(generation = 2))) {
            val owner = snapshot(1)
            var current = owner
            val pending = CompletableDeferred<File>()
            var attached = false
            val result = async { downloadSubtitleForPlayback(owner, { current }, { pending.await() }, { attached = true }) }
            runCurrent()
            current = next
            pending.complete(File("subtitle.srt"))
            assertFalse(result.await())
            assertFalse(attached)
        }
    }

    @Test fun dismissingWhileDownloadFinishesDoesNotApplyItsResult() = runTest {
        val owner = snapshot(1)
        val pending = CompletableDeferred<File>()
        var attached = false
        val job = launch {
            downloadSubtitleForPlayback(owner, { owner }, { withContext(NonCancellable) { pending.await() } }, { attached = true })
        }
        runCurrent()
        job.cancel()
        pending.complete(File("subtitle.srt"))
        job.join()
        assertFalse(attached)
    }

    @Test fun anUnchangedPlaybackReceivesTheDownloadedFileOnce() = runTest {
        val owner = snapshot(1)
        val file = File("subtitle.srt")
        val applied = mutableListOf<File>()
        assertTrue(downloadSubtitleForPlayback(owner, { owner }, { file }, { applied += it }))
        assertEquals(listOf(file), applied)
    }
}

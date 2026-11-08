package com.ambient.tv.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeRefreshBatchesTest {
    @Test fun completedRequestAppliesOnlyItsFinalSnapshot() = runTest {
        val batches = mutableListOf<List<YouTubeRow>>()
        val finalRows = rows(3)

        val result = withYouTubeRefreshBatches(onBatch = { batches += it }) { publish ->
            publish(rows(1))
            publish(rows(2))
            finalRows
        }
        advanceUntilIdle()

        assertEquals(finalRows, result)
        assertTrue(batches.isEmpty())
    }

    @Test fun continuousEmissionsPublishTheLatestWholeSnapshotAtBoundedIntervals() = runTest {
        val batches = mutableListOf<Pair<Long, List<YouTubeRow>>>()
        val finish = CompletableDeferred<Unit>()
        val request = async {
            withYouTubeRefreshBatches(onBatch = { batches += currentTime to it }) { publish ->
                repeat(8) { index ->
                    publish(rows(index + 1))
                    delay(100)
                }
                finish.await()
            }
        }
        runCurrent()
        advanceTimeBy(YOUTUBE_REFRESH_BATCH_MS - 1)
        runCurrent()
        assertTrue(batches.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(350L, batches.single().first)
        assertEquals(rows(4), batches.single().second)

        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf(350L, 750L), batches.map { it.first })
        assertEquals(rows(8), batches.last().second)

        finish.complete(Unit)
        request.await()
        advanceUntilIdle()
        assertEquals(2, batches.size)
    }

    @Test fun cancellationCannotPublishThePendingSnapshotIntoAnotherFeed() = runTest {
        val batches = mutableListOf<List<YouTubeRow>>()
        val request = async {
            withYouTubeRefreshBatches(onBatch = { batches += it }) { publish ->
                publish(rows(2))
                awaitCancellation()
            }
        }
        runCurrent()
        advanceTimeBy(100)
        request.cancelAndJoin()
        advanceTimeBy(YOUTUBE_REFRESH_BATCH_MS)
        runCurrent()

        assertTrue(request.isCancelled)
        assertTrue(batches.isEmpty())
    }

    private fun rows(size: Int) = listOf(YouTubeRow("Subscriptions", (1..size).map {
        YouTubeItem("video-$it", "Video $it")
    }))
}

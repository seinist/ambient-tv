package com.ambient.tv.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCancellationJobsTest {
    @Test
    fun cancellingOneMachineLeavesTheOthersRunning() = runTest {
        val firstWait = CompletableDeferred<Unit>()
        val secondWait = CompletableDeferred<Unit>()
        val first = launch { withCancellableWake("mac:one") { firstWait.await() } }
        val second = launch { withCancellableWake("mac:two") { secondWait.await() } }
        yield()

        assertTrue(WakeCancellationJobs.cancel("mac:one"))
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(second.isActive)
        assertFalse(WakeCancellationJobs.cancel("mac:one"))

        secondWait.complete(Unit)
        second.join()
        assertFalse(WakeCancellationJobs.cancel("mac:two"))
    }

    @Test
    fun allMembersOfOnePhysicalMachineAreCancelled() = runTest {
        val wait = CompletableDeferred<Unit>()
        val plex = launch { withCancellableWake("mac:shared") { wait.await() } }
        val nas = launch { withCancellableWake("mac:shared") { wait.await() } }
        yield()

        assertTrue(WakeCancellationJobs.cancel("mac:shared"))
        plex.join()
        nas.join()
        assertTrue(plex.isCancelled)
        assertTrue(nas.isCancelled)
        assertFalse(WakeCancellationJobs.cancel("mac:shared"))
    }
}

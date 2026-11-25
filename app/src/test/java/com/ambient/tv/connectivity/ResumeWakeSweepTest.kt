package com.ambient.tv.connectivity

import com.ambient.tv.notifications.WakeCancellationJobs
import com.ambient.tv.notifications.withCancellableWake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeWakeSweepTest {
    @Test fun `cancelled NAS settles offline without stopping sibling or later rechecks`() = runBlocking {
        withTimeout(5_000) {
            val machine = "test:resume-wake-worker"
            val registered = CompletableDeferred<Unit>()
            val releaseSibling = CompletableDeferred<Unit>()
            val requests = Channel<Boolean>(Channel.UNLIMITED)
            val completed = Channel<List<Boolean>>(Channel.UNLIMITED)
            val worker = launch {
                for (shouldWake in requests) {
                    val sweep = ResumeWakeSweep()
                    val results = coroutineScope {
                        listOf(
                            async {
                                sweep.check(machine) {
                                    if (!shouldWake) true else withCancellableWake(machine) {
                                        registered.complete(Unit)
                                        awaitCancellation()
                                    }
                                }
                            },
                            async {
                                if (shouldWake) releaseSibling.await()
                                true
                            },
                        ).awaitAll()
                    }
                    completed.send(results)
                }
            }
            try {
                requests.send(true)
                registered.await()
                assertTrue(WakeCancellationJobs.cancel(machine))
                releaseSibling.complete(Unit)
                assertEquals(listOf(false, true), completed.receive())
                assertFalse(WakeCancellationJobs.cancel(machine))

                requests.send(false)
                assertEquals(listOf(true, true), completed.receive())
                requests.close()
                worker.join()
                assertFalse(worker.isCancelled)
            } finally {
                worker.cancelAndJoin()
            }
        }
    }

    @Test fun `remaining sources on cancelled machine skip both probe and wake this sweep`() = runBlocking {
        withTimeout(5_000) {
            val machine = "test:resume-wake-siblings"
            val sweep = ResumeWakeSweep()
            val registered = CompletableDeferred<Unit>()
            val first = async {
                sweep.check(machine) {
                    withCancellableWake(machine) {
                        registered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            registered.await()
            assertTrue(WakeCancellationJobs.cancel(machine))
            assertFalse(first.await())
            assertFalse(sweep.check(machine) { error("Cancelled machine must not probe or wake again") })
            assertTrue(sweep.check("test:other-nas") { true })
        }
    }

    @Test fun `actual parent cancellation still stops the sweep`() = runBlocking {
        withTimeout(5_000) {
            val machine = "test:resume-wake-parent"
            val registered = CompletableDeferred<Unit>()
            val pauses = ResumeWakePauses()
            var continued = false
            val worker = launch {
                pauses.sweep(endpoints(machine)).check(machine) {
                    withCancellableWake(machine) {
                        registered.complete(Unit)
                        awaitCancellation()
                    }
                }
                continued = true
            }
            registered.await()
            worker.cancelAndJoin()
            assertTrue(worker.isCancelled)
            assertFalse(continued)
            assertFalse(WakeCancellationJobs.cancel(machine))
            assertFalse(pauses.isPaused(machine))
        }
    }

    @Test fun `ordinary unreachable result does not suppress another source on that machine`() = runBlocking {
        val sweep = ResumeWakeSweep()
        assertFalse(sweep.check("test:offline-nas") { false })
        assertTrue(sweep.check("test:offline-nas") { true })
    }

    @Test fun `queued network sweep probes cancelled machine without automatically waking it again`() = runBlocking {
        withTimeout(5_000) {
            val machine = "test:resume-wake-queued"
            val pauses = ResumeWakePauses()
            cancelRegisteredWake(pauses.sweep(endpoints(machine)), machine)
            assertTrue(pauses.isPaused(machine))

            var probes = 0
            var wakes = 0
            val queued = pauses.sweep(endpoints(machine))
            assertFalse(queued.check(machine) {
                probes++
                if (pauses.isPaused(machine)) false else { wakes++; true }
            })
            assertEquals(1, probes)
            assertEquals(0, wakes)
            assertTrue(pauses.isPaused(machine))

            // An explicit wake is outside this automatic guard. Its successful probe ends
            // the cancelled outage, allowing normal automatic recovery at a later outage.
            assertTrue(queued.check(machine) {
                probes++
                pauses.online(machine)
                true
            })
            assertFalse(pauses.isPaused(machine))
            assertTrue(pauses.sweep(endpoints(machine)).check(machine) {
                if (pauses.isPaused(machine)) false else { wakes++; true }
            })
            assertEquals(1, wakes)
        }
    }

    @Test fun `changed or removed endpoint releases its previous wake pause`() = runBlocking {
        withTimeout(5_000) {
            val machine = "test:resume-wake-reconfigured"
            val pauses = ResumeWakePauses()
            cancelRegisteredWake(pauses.sweep(endpoints(machine)), machine)
            assertTrue(pauses.isPaused(machine))

            pauses.sweep(endpoints(machine, port = 2049))
            assertFalse(pauses.isPaused(machine))
            cancelRegisteredWake(pauses.sweep(endpoints(machine, port = 2049)), machine)
            assertTrue(pauses.isPaused(machine))
            pauses.sweep(emptyMap())
            assertFalse(pauses.isPaused(machine))
        }
    }

    private fun endpoints(machine: String, port: Int = 445) = mapOf(machine to setOf(
        ResumeWakeEndpoint("nas", port, "00:11:22:33:44:55", "255.255.255.255", 9, true, false),
    ))

    private suspend fun cancelRegisteredWake(sweep: ResumeWakeSweep, machine: String) = coroutineScope {
        val registered = CompletableDeferred<Unit>()
        val wake = async {
            sweep.check(machine) {
                withCancellableWake(machine) {
                    registered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        registered.await()
        assertTrue(WakeCancellationJobs.cancel(machine))
        assertFalse(wake.await())
    }
}

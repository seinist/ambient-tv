package com.ambient.tv.ui.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class StreamUrlRenewalPolicyTest {
    @Test fun slowRenewalDoesNotBlockOtherUrls() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        val calls = AtomicInteger()
        val policy = StreamUrlRenewalPolicy { failed ->
            calls.incrementAndGet()
            if (failed == "old-video") {
                entered.countDown()
                check(unblock.await(10, TimeUnit.SECONDS))
            }
            "$failed-fresh"
        }
        try {
            val video = pool.submit<String?> { policy.replacement("old-video", "old-video") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val audio = pool.submit<String?> {
                assertEquals("old-video", policy.current("old-video"))
                policy.replacement("old-audio", "old-audio")
            }
            assertEquals("old-audio-fresh", audio.get(5, TimeUnit.SECONDS))
            val parallelVideo = pool.submit<String?> { policy.replacement("old-video", "old-video") }
            unblock.countDown()
            assertEquals("old-video-fresh", video.get(5, TimeUnit.SECONDS))
            assertEquals("old-video-fresh", parallelVideo.get(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertEquals("old-video-fresh", policy.current("old-video"))
        } finally {
            unblock.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun failedRenewalHasCooldownAndDoesNotPoisonOtherUrls() {
        val calls = AtomicInteger()
        val policy = StreamUrlRenewalPolicy {
            calls.incrementAndGet()
            if (it == "bad") error("refresh failed") else "fresh"
        }
        assertNull(policy.replacement("bad", "bad"))
        assertNull(policy.replacement("bad", "bad"))
        assertEquals("bad", policy.current("bad"))
        assertEquals("fresh", policy.replacement("good", "good"))
        assertEquals(2, calls.get())
    }
}

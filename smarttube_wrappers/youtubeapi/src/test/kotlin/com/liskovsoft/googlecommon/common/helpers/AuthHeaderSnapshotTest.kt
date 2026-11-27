package com.liskovsoft.googlecommon.common.helpers

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthHeaderSnapshotTest {
    @Test fun obsoleteRefreshCannotOverwriteNewAccount() {
        val previous = RetrofitOkHttpHelper.authHeaders
        try {
            val old = RetrofitOkHttpHelper.beginAuthRefresh()
            val latest = RetrofitOkHttpHelper.beginAuthRefresh()
            val headers = mapOf("Authorization" to "new", "X-Goog-Pageid" to "new-page")
            assertTrue(RetrofitOkHttpHelper.publishAuthHeaders(latest, headers))
            assertFalse(RetrofitOkHttpHelper.publishAuthHeaders(old, mapOf("Authorization" to "old")))
            assertEquals(headers, RetrofitOkHttpHelper.authHeaders)
            val pending = RetrofitOkHttpHelper.beginAuthRefresh()
            RetrofitOkHttpHelper.replaceAuthHeaders(emptyMap())
            assertFalse(RetrofitOkHttpHelper.publishAuthHeaders(pending, headers))
            assertTrue(RetrofitOkHttpHelper.authHeaders.isEmpty())
        } finally {
            RetrofitOkHttpHelper.replaceAuthHeaders(previous)
        }
    }

    @Test fun snapshotsAreImmutableAndAccountUpdatesAreAtomic() {
        val previous = RetrofitOkHttpHelper.authHeaders
        val input = mutableMapOf("Authorization" to "test-A", "X-Goog-Pageid" to "page-A")
        val alternate = mapOf("Authorization" to "test-B", "X-Goog-Pageid" to "page-B")
        val expected = input.toMap()
        val pool = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)
        try {
            RetrofitOkHttpHelper.replaceAuthHeaders(input)
            val snapshot = RetrofitOkHttpHelper.authHeaders
            input.clear()
            assertEquals(expected, snapshot)
            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot as MutableMap<String, String>).clear()
            }
            val writer = pool.submit {
                start.await()
                repeat(10000) {
                    RetrofitOkHttpHelper.replaceAuthHeaders(
                        when (it % 3) { 0 -> expected; 1 -> alternate; else -> emptyMap() }
                    )
                }
            }
            val readers = (0 until 4).map {
                pool.submit {
                    start.await()
                    repeat(10000) {
                        val headers = RetrofitOkHttpHelper.authHeaders
                        check(headers == expected || headers == alternate || headers.isEmpty())
                    }
                }
            }
            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            readers.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(expected, snapshot)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            RetrofitOkHttpHelper.replaceAuthHeaders(previous)
        }
    }
}

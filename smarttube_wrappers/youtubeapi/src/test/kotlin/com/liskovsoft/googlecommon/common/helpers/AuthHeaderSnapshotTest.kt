package com.liskovsoft.googlecommon.common.helpers

import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthHeaderSnapshotTest {
    @Test fun credentialKeySurvivesAccountMetadataReplacement() {
        fun account(page: String) = object : YouTubeAccount() {
            override fun getRefreshToken() = "fixture-refresh"
            override fun getPageIdToken() = page
        }
        assertEquals(YouTubeSignInService.authKey(account("page-a")), YouTubeSignInService.authKey(account("page-a")))
        assertNotEquals(YouTubeSignInService.authKey(account("page-a")), YouTubeSignInService.authKey(account("page-b")))
    }

    @Test fun selectingAnotherAccountClearsHeadersBeforeChangingSelection() {
        val signIn = YouTubeSignInService.instance()
        val accounts = signIn.accounts
        val previous = RetrofitOkHttpHelper.authHeaders
        val selectionReached = IllegalStateException("selection reached")
        val old = object : YouTubeAccount() {
            override fun isSelected() = true
            override fun getName() = "old fixture"
            override fun setSelected(selected: Boolean) {
                assertTrue(RetrofitOkHttpHelper.authHeaders.isEmpty())
                throw selectionReached
            }
        }
        val next = object : YouTubeAccount() {
            override fun getName() = "next fixture"
            override fun getRefreshToken() = "next refresh"
        }
        try {
            accounts.add(old)
            accounts.add(next)
            val pending = RetrofitOkHttpHelper.beginAuthRefresh()
            assertTrue(RetrofitOkHttpHelper.publishAuthHeaders(pending, mapOf("Authorization" to "old fixture")))
            assertSame(selectionReached, assertThrows(IllegalStateException::class.java) { signIn.selectAccount(next) })
            assertFalse(RetrofitOkHttpHelper.publishAuthHeaders(pending, mapOf("Authorization" to "obsolete fixture")))
        } finally {
            accounts.remove(old)
            accounts.remove(next)
            RetrofitOkHttpHelper.replaceAuthHeaders(previous)
        }
    }

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

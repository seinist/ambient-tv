package com.ambient.tv.data.dlna

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DlnaPagingTest {
    @Test fun largePageFailureFallsBackWithoutLosingServerOffsetInformation() = runBlocking {
        val requests = mutableListOf<Int>()
        val result = browseDlnaPageWithFallback(30) { count ->
            requests += count
            if (count > 10) throw DlnaHttpException(500, "Action failed")
            DlnaBrowsePage(emptyList(), 500, 10)
        }
        assertEquals(listOf(30, 10), requests)
        assertEquals(10, result.numberReturned)
        assertEquals(10, result.requestedCount)
        assertEquals(500, result.totalMatches)
    }

    @Test fun persistentFailureIsBoundedAndNotConvertedToEmptySuccess() = runBlocking {
        val requests = mutableListOf<Int>()
        val error = runCatching {
            browseDlnaPageWithFallback(30) { count ->
                requests += count
                throw DlnaHttpException(500, "Action failed")
            }
        }.exceptionOrNull()
        assertTrue(error is DlnaHttpException)
        assertEquals(listOf(30, 10, 1), requests)
    }

    @Test fun cancellationAndClientErrorsAreNotRetried() = runBlocking {
        for (failure in listOf(CancellationException("Left folder"), DlnaHttpException(404, "Gone"))) {
            var calls = 0
            val caught = runCatching {
                browseDlnaPageWithFallback(30) { calls++; throw failure }
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(1, calls)
        }
    }
}

package com.ambient.tv.network

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtworkNetworkAccessChangesTest {
    @Test fun serverChecksDoNotRunOnTheImageCollectorThread() = runBlocking {
        Executors.newSingleThreadExecutor { task -> Thread(task, "artwork-test-ui") }
            .asCoroutineDispatcher().use { ui ->
                withContext(ui) {
                    val uiThread = Thread.currentThread()
                    var checkedOn: Thread? = null
                    withTimeout(5_000) {
                        artworkNetworkAccessChanges(listOf(Unit).asFlow()) {
                            checkedOn = Thread.currentThread()
                            true
                        }.first()
                    }
                    assertNotEquals(uiThread, checkedOn)
                    assertEquals(uiThread, Thread.currentThread())
                }
            }
    }

    @Test fun repeatedFailureReportsDoNotRepublishImagesButRecoveryIsPreserved() = runBlocking {
        var blocked = false
        val reports = (List(50) { false } + List(100) { true } + List(50) { false })
            .asFlow().map { blocked = it }
        val received = withTimeout(5_000) {
            artworkNetworkAccessChanges(reports) { blocked }.toList()
        }
        assertEquals(listOf(false, true, false), received)
    }
}

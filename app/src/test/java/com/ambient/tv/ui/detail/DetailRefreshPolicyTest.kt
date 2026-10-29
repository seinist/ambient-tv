package com.ambient.tv.ui.detail

import org.junit.Assert.*
import org.junit.Test

class DetailRefreshPolicyTest {
    @Test fun actionInvalidatesTheReadThatWasAlreadyRunning() {
        val policy = DetailRefreshPolicy()
        val original = requireNotNull(policy.beginLoad())
        assertTrue(policy.isCurrent(original))

        assertTrue(policy.beginAction(readInProgress = true))
        assertFalse(policy.isCurrent(original))
        assertTrue(policy.finishAction())
        assertFalse(policy.isCurrent(original))

        val replacement = requireNotNull(policy.beginLoad())
        assertTrue(policy.isCurrent(replacement))
        assertFalse(policy.isCurrent(original))
    }

    @Test fun repeatedEntriesAndReconnectsDuringAnActionCoalesce() {
        val policy = DetailRefreshPolicy()
        assertTrue(policy.beginAction(readInProgress = false))
        repeat(4) { assertNull(policy.beginLoad()) }

        assertTrue(policy.finishAction())
        assertFalse(policy.finishAction())
        assertNotNull(policy.beginLoad())
    }

    @Test fun interruptedProgressiveReadIsResumedWithoutAnotherEntry() {
        val policy = DetailRefreshPolicy()
        policy.beginLoad()
        assertTrue(policy.beginAction(readInProgress = true))
        assertTrue(policy.finishAction())
    }

    @Test fun completedReadDoesNotCauseAnExtraPostActionRefresh() {
        val policy = DetailRefreshPolicy()
        policy.beginLoad()
        assertTrue(policy.beginAction(readInProgress = false))
        assertFalse(policy.finishAction())
    }

    @Test fun secondActionCannotReplaceThePendingRefresh() {
        val policy = DetailRefreshPolicy()
        assertTrue(policy.beginAction(readInProgress = true))
        assertFalse(policy.beginAction(readInProgress = false))
        assertTrue(policy.finishAction())
        assertTrue(policy.beginAction(readInProgress = false))
        assertFalse(policy.finishAction())
    }

    @Test fun aNewReadRejectsAnOlderCompletedResult() {
        val policy = DetailRefreshPolicy()
        val first = requireNotNull(policy.beginLoad())
        val second = requireNotNull(policy.beginLoad())
        assertFalse(policy.isCurrent(first))
        assertTrue(policy.isCurrent(second))
    }
}

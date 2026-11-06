package com.ambient.tv.ui.player

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSelectionGateTest {
    @Test fun onlyLatestIntentReachesTheSuccessfullySelectedSource() {
        val applied = mutableListOf<Boolean>()
        val gate = PlaybackSelectionGate(Executor(Runnable::run), applied::add)
        val operation = SettableFuture.create<Void?>()
        gate.start(true, {}) { operation }
        gate.setPlayWhenReady(true)
        gate.setPlayWhenReady(false)
        assertTrue(applied.isEmpty())
        operation.set(null)
        assertEquals(listOf(false), applied)
        assertFalse(gate.isPending)
    }

    @Test fun cancellationDiscardsPlayAndCancelsTheSourceOperation() {
        val applied = mutableListOf<Boolean>()
        val gate = PlaybackSelectionGate(Executor(Runnable::run), applied::add)
        val operation = SettableFuture.create<Void?>()
        gate.start(true, {}) { operation }
        gate.setPlayWhenReady(true)
        gate.cancel()
        assertTrue(operation.isCancelled)
        assertTrue(applied.isEmpty())
        assertFalse(gate.isPending)
    }

    @Test fun completedButNotDispatchedOldOperationCannotPlayANewerSelection() {
        val callbacks = ArrayDeque<Runnable>()
        val applied = mutableListOf<Boolean>()
        val gate = PlaybackSelectionGate(Executor { callbacks.addLast(it) }, applied::add)
        val old = SettableFuture.create<Void?>()
        val current = SettableFuture.create<Void?>()
        gate.start(true, {}) { old }
        old.set(null)
        gate.start(true, {}) { current }
        gate.setPlayWhenReady(false)
        callbacks.removeFirst().run()
        assertTrue(applied.isEmpty())
        current.set(null)
        callbacks.removeFirst().run()
        assertEquals(listOf(false), applied)
    }

    @Test fun pauseArrivingAfterCompletionButBeforeDispatchWins() {
        val callbacks = ArrayDeque<Runnable>()
        val applied = mutableListOf<Boolean>()
        val gate = PlaybackSelectionGate(Executor { callbacks.addLast(it) }, applied::add)
        val operation = SettableFuture.create<Void?>()
        gate.start(true, {}) { operation }
        operation.set(null)
        gate.setPlayWhenReady(false)
        callbacks.removeFirst().run()
        assertEquals(listOf(false), applied)
    }

    @Test fun failureCompletesOverlayCleanupWithoutTransportingTheOldSource() {
        val applied = mutableListOf<Boolean>()
        val gate = PlaybackSelectionGate(Executor(Runnable::run), applied::add)
        val operation = SettableFuture.create<Void?>()
        var finished = false
        gate.start(true, { finished = true }) { operation }
        gate.setPlayWhenReady(true)
        operation.setException(IllegalStateException("Unavailable"))
        assertTrue(finished)
        assertTrue(applied.isEmpty())
        assertFalse(gate.isPending)
    }

    @Test fun immediateSelectionAndImmediateFailureHaveNoDanglingPendingState() {
        val applied = mutableListOf<Boolean>()
        val gate = PlaybackSelectionGate(Executor(Runnable::run), applied::add)
        gate.start(true, {}) { Futures.immediateVoidFuture() }
        assertEquals(listOf(true), applied)
        var cleaned = false
        val failed = gate.start(true, { cleaned = true }) { error("Unavailable") }
        assertTrue(failed.isDone)
        assertTrue(cleaned)
        assertFalse(gate.isPending)
        assertEquals(listOf(true), applied)
    }
}

package com.ambient.tv.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ReactiveContentTest {
    fun interface Consumer { fun accept(value: Any) }
    fun interface Action { fun run() }
    class Disposable {
        var disposed = false
        fun dispose() { disposed = true }
    }
    class Feed {
        val subscribed = CompletableDeferred<Unit>()
        val disposable = Disposable()
        lateinit var next: Consumer
        lateinit var error: Consumer
        lateinit var complete: Action
        fun subscribe(next: Consumer, error: Consumer, complete: Action): Disposable {
            this.next = next
            this.error = error
            this.complete = complete
            subscribed.complete(Unit)
            return disposable
        }
    }

    @Test fun `first row is delivered before completion and later rows are retained`() = runBlocking {
        withTimeout(2_000) {
            val feed = Feed()
            val first = CompletableDeferred<Any>()
            val rows = mutableListOf<Any>()
            val job = launch {
                reactiveContent(feed)!!.collect { rows += it; first.complete(it) }
            }
            feed.subscribed.await()
            feed.next.accept("first row")
            assertEquals("first row", first.await())
            assertTrue(job.isActive)
            feed.next.accept("later row")
            feed.complete.run()
            job.join()
            assertEquals(listOf("first row", "later row"), rows)
            assertTrue(feed.disposable.disposed)
        }
    }

    @Test fun `navigation cancellation disposes the upstream subscription`() = runBlocking {
        withTimeout(2_000) {
            val feed = Feed()
            val job = launch { reactiveContent(feed)!!.collect() }
            feed.subscribed.await()
            job.cancelAndJoin()
            assertTrue(feed.disposable.disposed)
        }
    }

    @Test fun `upstream error is reported after already emitted content`() = runBlocking {
        withTimeout(2_000) {
            val feed = Feed()
            val rows = mutableListOf<Any>()
            val result = async { runCatching { reactiveContent(feed)!!.collect { rows += it } } }
            feed.subscribed.await()
            val failure = IllegalStateException("late network failure")
            feed.next.accept("visible row")
            feed.error.accept(failure)
            val actual = result.await().exceptionOrNull()
            assertEquals(failure.javaClass, actual?.javaClass)
            assertEquals(failure.message, actual?.message)
            assertEquals(listOf("visible row"), rows)
            assertTrue(feed.disposable.disposed)
        }
    }

    @Test fun `emission limit disposes a feed that does not complete`() = runBlocking {
        withTimeout(2_000) {
            val feed = Feed()
            val result = async { reactiveContent(feed)!!.take(2).toList() }
            feed.subscribed.await()
            feed.next.accept(1)
            feed.next.accept(2)
            assertEquals(listOf(1, 2), result.await())
            assertTrue(feed.disposable.disposed)
        }
    }

    @Test fun hotSubscriptionReturnsItsSnapshotWhenObservationWindowEnds() = runBlocking {
        val received = mutableListOf<String>()
        val completed = flow {
            emit("updated subscription row")
            awaitCancellation()
        }.collectBoundedSnapshot(timeoutMs = 50L, maxEmissions = 128) {
            received += it
        }
        assertFalse(completed)
        assertEquals(listOf("updated subscription row"), received)
    }

    @Test fun completedSubscriptionDoesNotWaitForTimeout() = runBlocking {
        val received = mutableListOf<String>()
        val completed = flow {
            emit("first row")
            emit("second row")
        }.collectBoundedSnapshot(timeoutMs = 1_000L, maxEmissions = 128) {
            received += it
        }
        assertTrue(completed)
        assertEquals(listOf("first row", "second row"), received)
    }

    @Test fun `plain synchronous content is left to the normal mapper`() {
        assertNull(reactiveContent(listOf("row")))
    }

    @Test fun `packaged RxJava emits immediately and is disposed on cancellation`() = runBlocking {
        withTimeout(2_000) {
            val type = Class.forName("io.reactivex.subjects.PublishSubject")
            val subject = type.getMethod("create").invoke(null)
            fun observing() = type.getMethod("hasObservers").invoke(subject) as Boolean
            val first = CompletableDeferred<Any>()
            val job = launch { reactiveContent(subject)!!.collect { first.complete(it) } }
            while (!observing()) delay(1)
            type.getMethod("onNext", Any::class.java).invoke(subject, "available before completion")
            assertEquals("available before completion", first.await())
            assertTrue(observing())
            job.cancelAndJoin()
            assertFalse(observing())
        }
    }
}

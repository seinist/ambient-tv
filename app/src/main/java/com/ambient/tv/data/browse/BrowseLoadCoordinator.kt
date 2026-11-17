package com.ambient.tv.data.browse

import com.ambient.tv.domain.model.LibrarySection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Priority is relative to other browse work, not to playback. Streaming and connection/WOL
 * workers deliberately do not acquire a browse slot.
 */
enum class BrowseLoadPriority { VISIBLE, HOME, BACKGROUND }

/** Cancellable, FIFO-within-priority permits. A queued visible request overtakes background work. */
internal class BrowseLoadGate(private val limit: Int) {
    init { require(limit > 0) }

    private class Waiter(val priority: () -> BrowseLoadPriority) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
        var lowerPrioritySlot = false
    }
    private val mutex = Mutex()
    private val pending = mutableListOf<Waiter>()
    private var active = 0
    // One slot stays available for newly opened views even if Home has a slow response.
    private val lowerPriorityLimit = if (limit > 1) limit - 1 else limit
    private var lowerPriorityActive = 0

    suspend fun <T> withPermit(priority: () -> BrowseLoadPriority, block: suspend () -> T): T {
        val waiter = Waiter(priority)
        mutex.withLock {
            pending += waiter
            grantAvailable()
        }
        try {
            waiter.ready.await()
            return block()
        } finally {
            // A cancelled request must not strand a permit, even if it was cancelled immediately
            // after being granted but before resuming its suspended caller.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (waiter.granted) {
                        active--
                        if (waiter.lowerPrioritySlot) lowerPriorityActive--
                    } else pending.remove(waiter)
                    grantAvailable()
                }
            }
        }
    }

    private fun grantAvailable() {
        while (active < limit && pending.isNotEmpty()) {
            val next = pending.filter {
                it.priority() == BrowseLoadPriority.VISIBLE || lowerPriorityActive < lowerPriorityLimit
            }.minByOrNull { it.priority().ordinal } ?: break
            pending.remove(next)
            next.granted = true
            next.lowerPrioritySlot = next.priority() != BrowseLoadPriority.VISIBLE
            active++
            if (next.lowerPrioritySlot) lowerPriorityActive++
            next.ready.complete(Unit)
        }
    }
}

/**
 * Process-wide single-flight for equal, provider-scoped browse requests. One view disappearing
 * cannot cancel a request still needed by another view. The last departing consumer cancels it.
 *
 * The key is a *typed resource identity*: all callers of a key must expect the same result type.
 * No completed response is retained here; the provider repositories remain the source of truth
 * for TTL, persistent caching, account isolation and invalidation.
 */
internal class BrowseLoadScheduler(
    maxConcurrent: Int = 4,
    private val idle: () -> Boolean = { true },
) {
    private class Shared(val key: String, @Volatile var priority: BrowseLoadPriority) {
        var consumers = 0
        lateinit var job: Deferred<Any?>
    }

    private val gate = BrowseLoadGate(maxConcurrent)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val flights = mutableMapOf<String, Shared>()

    val inFlightCount: Int get() = synchronized(lock) { flights.size }

    suspend fun <T> load(
        key: String,
        priority: BrowseLoadPriority,
        block: suspend () -> T,
    ): T {
        require(key.isNotBlank())
        // The first caller owns UI/Compose dispatch; the shared task itself owns cancellation.
        val callerContext = currentCoroutineContext().minusKey(Job)
        val shared = synchronized(lock) {
            flights[key]?.also { current ->
                current.consumers++
                if (priority < current.priority) current.priority = priority
            } ?: Shared(key, priority).also { fresh ->
                fresh.consumers = 1
                fresh.job = scope.async(start = CoroutineStart.LAZY) {
                    // Do not hold a scarce network permit while waiting for idle: otherwise four
                    // background jobs can starve a visible screen indefinitely during scrolling.
                    // A visible join promotes this request and makes the wait finish immediately.
                    while (fresh.priority == BrowseLoadPriority.BACKGROUND && !idle()) delay(200)
                    gate.withPermit({ fresh.priority }) {
                        Timber.tag("browse-load").d("start priority=%s", fresh.priority)
                        withContext(callerContext) { block() }
                    }
                }
                flights[key] = fresh
            }
        }
        shared.job.start()
        try {
            @Suppress("UNCHECKED_CAST")
            return shared.job.await() as T
        } finally {
            synchronized(lock) {
                shared.consumers--
                if (shared.consumers == 0) {
                    if (flights[key] === shared) flights.remove(key)
                    if (!shared.job.isCompleted) shared.job.cancel()
                }
            }
        }
    }
}

/** Shared scheduling, not a second content cache and not a replacement for BrowseReconnects. */
@Singleton
class BrowseLoadCoordinator @Inject constructor(private val scopes: BrowseScopes) {
    private val scheduler = BrowseLoadScheduler(
        maxConcurrent = 4,
        idle = BrowseWorkBudget::idle,
    )

    val activeRequests: Int get() = scheduler.inFlightCount

    suspend fun <T> request(
        provider: String,
        resource: String,
        priority: BrowseLoadPriority,
        block: suspend () -> T,
    ): T = scheduler.load("${scopes.forProvider(provider)}:$resource", priority) {
        val tracked = priority != BrowseLoadPriority.BACKGROUND
        if (tracked) BrowseWorkBudget.foregroundRequests.incrementAndGet()
        try { block() } finally {
            if (tracked) BrowseWorkBudget.foregroundRequests.decrementAndGet()
        }
    }

    suspend fun librarySections(
        provider: String,
        priority: BrowseLoadPriority,
        block: suspend () -> Result<List<LibrarySection>>,
    ): Result<List<LibrarySection>> = request(provider, "library-sections", priority, block)
}

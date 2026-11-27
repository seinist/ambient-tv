package com.ambient.tv.data.telegram

import com.ambient.tv.R
import android.content.Context
import android.util.Base64
import com.ambient.tv.data.local.TokenStore
import java.io.File
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.monogram.mtproto.MtprotoNative
import org.monogram.mtproto.MtprotoNativeLoader
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.MessageDto
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.QrLoginDto
import uniffi.monogram_mtproto.UpdateEventDto

/**
 * The ONLY application-side dependency on Monogram, compiled exclusively with
 * MONOGRAM_NATIVE=1. Uses upstream :native:mtproto, never its UI/feature modules.
 */
class MonogramNativeBackend(context: Context, private val tokenStore: TokenStore) : MonogramAuthClientPort, TelegramClientPort {
    private val app = context.applicationContext
    private val native = MtprotoNativeLoader.loadOrStub().also {
        check(it !== MtprotoNative.Stub) { "Monogram native MTProto library is missing" }
        MtprotoNativeLoader.initializePlatformVerifier(app)
    }
    private val session = File(app.noBackupFilesDir, "monogram/account.session").apply {
        parentFile?.mkdirs()
    }
    private val cache = File(app.cacheDir, "monogram").canonicalFile.apply { mkdirs() }
    // v2 disk keys include the actual upstream document identity, never just message size.
    private val chunkDirectory = File(cache, "chunks-v2").apply { File(cache, "chunks").deleteRecursively() }
    /** The upstream MTProto handle supports overlapping RPCs. Only handle creation and
     * connecting are serialized: an artwork request must never block video seeking. */
    private val mutex = Mutex()
    /**
     * RPCs on the handle run outside [mutex] so they can overlap. Each holds a read lock while
     * it uses the handle; destroying the handle takes the write lock, so it waits for running
     * RPCs instead of freeing native memory under them (use-after-free on logout/reset).
     */
    private val handleUse = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val browseMutex = Mutex()
    /**
     * Category pages (videos, YouTube, audio, photos, a channel) no longer wait for each other:
     * two run at once, enough to fill Home's rows in parallel without bursting into FLOOD_WAIT.
     * The dialog catalog they share is guarded by [catalogLock]; "recent" keeps [browseMutex]
     * for its stateful timeline.
     */
    private val browsePermits = kotlinx.coroutines.sync.Semaphore(2)
    private val catalogLock = Any()
    private val diskChunkLock = Any()
    // Access-ordered index, rebuilt only once per account/cache generation.
    private val chunkIndex = LinkedHashMap<File, Long>(128, 0.75f, true)
    private var chunkIndexGeneration = -1L
    private var chunkIndexBytes = 0L
    private val chunkTouched = HashMap<File, Long>()
    // Cap memory by heap class and use a stricter ceiling on low-RAM devices.
    private val hotChunkLimit = run {
        val manager = app.getSystemService(android.app.ActivityManager::class.java)
        if (manager?.isLowRamDevice == true) 4
        else (Runtime.getRuntime().maxMemory() / (32L * TELEGRAM_MEDIA_PART_BYTES)).toInt().coerceIn(4, 24)
    }
    private val hotChunks = LinkedHashMap<File, ByteArray>(hotChunkLimit, 0.75f, true)
    private val mediaDigests = LinkedHashMap<String, String>(64, 0.75f, true)
    private fun mediaDigest(key: String): String = synchronized(mediaDigests) {
        mediaDigests[key] ?: run {
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            buildString(64) {
                bytes.forEach { byte ->
                    val n = byte.toInt() and 255
                    append(hex[n ushr 4]); append(hex[n and 15])
                }
            }.also {
                mediaDigests[key] = it
                if (mediaDigests.size > 128) mediaDigests.remove(mediaDigests.keys.first())
            }
        }
    }
    private fun rememberHotChunk(file: File, bytes: ByteArray) {
        hotChunks[file] = bytes
        while (hotChunks.size > hotChunkLimit) hotChunks.remove(hotChunks.keys.first())
    }
    private fun invalidateChunksLocked() {
        cacheGeneration++
        hotChunks.clear()
        synchronized(mediaDigests) { mediaDigests.clear() }
        chunkIndex.clear()
        chunkTouched.clear()
        chunkIndexBytes = 0L
        chunkIndexGeneration = -1L
        inlinePreviewIndex.clear()
        sharpPreviewIndex.clear()
        photoIndex.clear()
        inlineMemo.clear()
        displayAliases.clear()
        runCatching { uniffi.monogram_mtproto.ambientClearPreviewIndex() }
    }
    /** Account-generation fences prevent older thumbnail RPCs repopulating a new account's cache. */
    @Volatile private var cacheGeneration = 0L
    // All index operations are under diskChunkLock; seed only once per generation.
    private val inlinePreviewIndex = PreviewFileIndex(cache, 500, 32L * 1024 * 1024) {
        it.name.startsWith("thumb-") || it.name.startsWith("channel-")
    }
    private val sharpPreviewIndex = PreviewFileIndex(File(cache, "previews"), 500, 64L * 1024 * 1024) {
        it.name.endsWith(".img") && !it.name.startsWith("pending-")
    }
    // Full photographs are much larger than thumbnails; independent storage budget.
    private val photoIndex = PreviewFileIndex(File(cache, "photos"), 24, 128L * 1024 * 1024) {
        it.name.endsWith(".media") && !it.name.startsWith("pending-")
    }
    private data class InlineMemo(val revision: Long, val atMs: Long, val file: File)
    private val inlineMemo = LinkedHashMap<Pair<Long, Int>, InlineMemo>(128, .75f, true)
    // Account-local aliases refer only to a successfully published, validated display image.
    private val displayAliases = LinkedHashMap<String, File>(128, .75f, true)
    private val measuredWindows = java.util.concurrent.atomic.AtomicInteger()
    private fun recordNativeWindow() {
        if (!com.ambient.tv.BuildConfig.MONOGRAM_PGO_GENERATE && !com.ambient.tv.BuildConfig.MONOGRAM_PERF_COUNTERS) return
        if (measuredWindows.incrementAndGet() % 16 != 0) return
        if (com.ambient.tv.BuildConfig.MONOGRAM_PGO_GENERATE) {
            val result = runCatching {
                val status = AmbientMonogramJni.flushPgoProfile(File(app.filesDir, "monogram-pgo.profraw").absolutePath)
                if (status == 0) File(app.filesDir, "monogram-pgo.stamp").writeText(com.ambient.tv.BuildConfig.MONOGRAM_PGO_STAMP)
                status
            }
            android.util.Log.i("MonogramPerf", "PGO profile flush: ${result.getOrNull() ?: "unavailable"}")
        }
        if (com.ambient.tv.BuildConfig.MONOGRAM_PERF_COUNTERS) {
            runCatching { uniffi.monogram_mtproto.perfSnapshot(true) }.getOrNull()?.let {
                android.util.Log.d("MonogramPerf", it)
            }
        }
    }
    private val accountIdFile = File(session.parentFile, "account.id")
    @Volatile private var accountId: Long? = accountIdFile.takeIf { it.isFile }?.let {
        runCatching { it.readText().trim().toLongOrNull() }.getOrNull()
    }
    private var handle = 0L
    @Volatile private var connected = false
    private var epoch = System.nanoTime()
    /**
     * Paged by browse calls under [browseMutex], but also read by the player/detail lookup and
     * cleared by login/logout. Copy-on-write iteration never throws ConcurrentModificationException.
     */
    private val chats = java.util.concurrent.CopyOnWriteArrayList<ChatDto>()
    /** When [chats] was last loaded from Telegram (elapsedRealtime). */
    @Volatile private var chatsLoadedAtMs = 0L
    @Volatile private var chatsExhausted = false
    private val recentFeeds = LinkedHashMap<String, MonogramRecentTimeline>()
    private val recentFeedRevision = java.util.concurrent.atomic.AtomicLong()
    private var recentFeedsSeen = -1L // guarded by browseMutex
    private val leftChannels = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    /** Public channels resolved from t.me/<name> links, so their page has a title before joining. */
    private val resolvedChannels = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private var nextRecentFeed = 0L
    private var phone: String? = null
    private var codeHash: String? = null
    private var qrExpiresAt = 0L
    private var label = "Monogram"
    private var loginState = MonogramLoginState(MonogramLoginStep.READY)
    override val accountName get() = label
    override val cacheIdentity: String? get() = accountId?.toString()

    private class HistoryPage(val requested: Int, val messages: List<MessageDto>, val atMs: Long)

    /**
     * Channel history pages shared by every category. Videos, YouTube, audio, photos and
     * recent read the same pages and only filter them differently: each page used to be
     * fetched once per category, which on a cold start alone tripped FLOOD_WAIT.
     * Keyed by channel and offset message id; the first n messages of a larger page from the
     * same offset are exactly the page of size n. Cleared with the channel catalog
     * (account change, logout, manual refresh, leaving a channel).
     */
    private val historyPages = LinkedHashMap<Pair<Long, Int>, HistoryPage>(64, 0.75f, true)
    private class HistoryFlight { var users = 0 }
    private val historyFlights = HashMap<Pair<Long, Int>, HistoryFlight>()
    // Guarded by historyPages; never publish an RPC begun before an invalidation.
    private var historyRevision = 0L
    private var historyFloor = 0L
    private val channelHistoryRevisions = LinkedHashMap<Long, Long>()
    // Access only under historyPages.
    private fun historyRevisionFor(chatId: Long) = channelHistoryRevisions[chatId] ?: historyFloor
    private fun invalidateHistoryLocked(touched: Set<Long>, everything: Boolean) {
        val revision = ++historyRevision
        if (everything || channelHistoryRevisions.size + touched.size > 4096) {
            channelHistoryRevisions.clear()
            historyFloor = revision
            historyPages.clear()
        } else {
            touched.forEach { channelHistoryRevisions[it] = revision }
            historyPages.keys.removeAll { it.first in touched }
        }
    }
    private val mediaRevisions = LinkedHashMap<Pair<Long, Int>, Long>()
    private var mediaRevisionCounter = 0L
    private var mediaRevisionFloor = 0L

    override fun mediaRevision(chatId: Long, messageId: Int): Long =
        synchronized(mediaRevisions) { mediaRevisions[chatId to messageId] ?: mediaRevisionFloor }

    private fun invalidateMediaRevisions(messages: Set<Pair<Long, Int>>, everything: Boolean) =
        synchronized(mediaRevisions) {
            val revision = ++mediaRevisionCounter
            if (everything || mediaRevisions.size + messages.size > 4096) {
                mediaRevisions.clear()
                mediaRevisionFloor = revision
            } else messages.forEach { mediaRevisions[it] = revision }
        }

    private fun historyPage(h: Long, chatId: Long, size: Int, offset: Int): List<MessageDto> {
        val key = chatId to offset
        val flight = synchronized(historyFlights) {
            historyFlights.getOrPut(key) { HistoryFlight() }.also { it.users++ }
        }
        try {
            synchronized(flight) {
                repeat(2) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val revision = synchronized(historyPages) {
                        if (chatId in leftChannels) return emptyList()
                        historyPages[key]?.takeIf { now - it.atMs < HISTORY_PAGE_TTL_MS &&
                            (it.requested >= size || it.messages.size < it.requested) }
                            ?.let { return it.messages.take(size) }
                        historyRevisionFor(chatId)
                    }
                    val requested = maxOf(size, HISTORY_SHARED_PAGE).coerceAtMost(100)
                    val fetched = native.getHistoryPage(h, chatId, requested, offset, 0, 0)
                    synchronized(historyPages) {
                        if (chatId in leftChannels) return emptyList()
                        if (revision == historyRevisionFor(chatId)) {
                            historyPages[key] = HistoryPage(requested, fetched, android.os.SystemClock.elapsedRealtime())
                            while (historyPages.size > HISTORY_PAGE_LIMIT) historyPages.remove(historyPages.keys.first())
                            return fetched.take(size)
                        }
                    }
                }
                throw java.io.IOException("Monogram history changed during request")
            }
        } finally {
            synchronized(historyFlights) {
                if (--flight.users == 0) historyFlights.remove(key)
            }
        }
    }

    private fun clearHistoryPages() = synchronized(historyPages) {
        invalidateHistoryLocked(emptySet(), true)
    }

    /**
     * Telegram's update stream, like the Monogram app's UpdatesPump but only for what Ambient TV
     * shows: new, edited or deleted posts drop the shared history pages of that channel (the
     * next look is fresh instead of up to two minutes old) and keep the channel's latest date
     * current, so "recent" sorts new posts in. Nothing on screen is replaced by it.
     */
    private val updatesScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var updatesJob: kotlinx.coroutines.Job? = null
    @Volatile private var updatesStarted = false
    private val activeUntil = java.util.concurrent.atomic.AtomicLong()
    private val updateWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun noteInteractiveActivity() {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = activeUntil.getAndSet(now + 10_000L)
        if (previous <= now) updateWake.trySend(Unit)
    }

    private fun startUpdatesPump() {
        if (updatesJob?.isActive == true) return
        updatesJob = updatesScope.launch {
            var backoffMs = UPDATES_POLL_MS
            while (true) {
                val active = android.os.SystemClock.elapsedRealtime() < activeUntil.get()
                kotlinx.coroutines.withTimeoutOrNull(if (active) minOf(backoffMs, 2_000L) else backoffMs) {
                    updateWake.receive()
                }
                backoffMs = try {
                    val generation = cacheGeneration
                    val identity = accountId
                    val drained = nativeCall { h ->
                        if (generation != cacheGeneration || identity != accountId) DrainedUpdates(emptyList(), null)
                        else drainUpdateEvents(h)
                    }
                    if (generation == cacheGeneration && identity == accountId) {
                        val changes = applyUpdates(drained.events)
                        if (changes != null && identity != null && generation == cacheGeneration && identity == accountId) {
                            contentChangedListener?.invoke(identity.toString(), changes.messages, changes.all)
                            artworkChangedListener?.invoke()
                        }
                        // A durable batch is acknowledged only after the local invalidation.
                        drained.acknowledge?.let { acknowledge ->
                            nativeCallOnce(connect = false) { h ->
                                if (generation == cacheGeneration && identity == accountId) acknowledge(h)
                            }
                        }
                    }
                    if (drained.events.isEmpty()) (backoffMs + UPDATES_POLL_MS).coerceAtMost(15_000L)
                    else UPDATES_POLL_MS
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (lost: TelegramSessionLostException) {
                    return@launch
                } catch (failure: Exception) {
                    // After a reconnect the stream has to be started again.
                    updatesStarted = false
                    (backoffMs * 2).coerceIn(4_000L, 30_000L)
                }
            }
        }
    }

    private fun stopUpdatesPump() {
        updatesJob?.cancel()
        updatesJob = null
        updatesStarted = false
    }

    private data class DrainedUpdates(val events: List<UpdateEventDto>, val acknowledge: ((Long) -> Unit)?)
    private data class ContentChanges(val messages: Set<Pair<Long, Int>>, val all: Boolean)

    /** Drain first; acknowledge durable batches after local cache invalidation succeeds. */
    private fun drainUpdateEvents(h: Long): DrainedUpdates {
        if (!native.isAuthorized(h)) return DrainedUpdates(emptyList(), null)
        if (!updatesStarted) {
            native.startUpdates(h)
            updatesStarted = true
        }
        val drained = native.drainUpdateBatches(h)
        val batches = if (drained.replayRequired)
            (drained.batches + native.pendingUpdateBatches(h)).distinctBy { it.batchId }
        else drained.batches
        val acknowledge: ((Long) -> Unit)? = if (batches.isEmpty()) null else { handle ->
            batches.forEach { batch ->
                native.acknowledgeUpdateBatch(handle, batch.accountGeneration, batch.batchId)
            }
        }
        return DrainedUpdates(batches.flatMap { it.events } + drained.liveEvents, acknowledge)
    }

    private fun applyUpdates(events: List<UpdateEventDto>): ContentChanges? {
        if (events.isEmpty()) return null
        val touched = HashSet<Long>()
        var everything = false
        val changedMessages = HashSet<Pair<Long, Int>>()
        val latest = HashMap<Long, Pair<Long, Int>>()
        for (event in events) {
            when (event) {
                is UpdateEventDto.NewMessage -> {
                    touched += event.message.chatId
                    val date = event.message.date
                    if ((latest[event.message.chatId]?.first ?: Long.MIN_VALUE) < date)
                        latest[event.message.chatId] = date to event.message.id
                }
                is UpdateEventDto.MessageEdited -> {
                    touched += event.message.chatId
                    changedMessages += event.message.chatId to event.message.id
                }
                is UpdateEventDto.MessageContentInvalidated -> {
                    touched += event.chatId
                    changedMessages += event.chatId to event.messageId
                }
                is UpdateEventDto.MessagesDeleted -> event.chatId?.let { chat ->
                    touched += chat
                    event.messageIds.forEach { changedMessages += chat to it }
                } ?: run { everything = true }
                else -> Unit
            }
        }
        synchronized(historyPages) {
            if (everything || touched.isNotEmpty()) invalidateHistoryLocked(touched, everything)
        }
        if (everything || changedMessages.isNotEmpty()) {
            recentFeedRevision.incrementAndGet()
            synchronized(diskChunkLock) {
                // Existing player buffers and in-flight chunk publications are fenced.
                invalidateMediaRevisions(changedMessages, everything)
                fun affected(file: File): Boolean {
                    val name = file.name.removePrefix("thumb-")
                    return everything || changedMessages.any { (chat, message) ->
                            name.startsWith("$chat-$message-") || name.substringBeforeLast('.') == "$chat-$message"
                        }
                }
                // Account generation is unchanged: unrelated playback and hot
                // chunks survive. Remove just affected index and memory entries.
                hotChunks.keys.removeAll(::affected)
                inlineMemo.entries.removeAll { everything || it.key in changedMessages }
                inlinePreviewIndex.removeMatching(::affected)
                sharpPreviewIndex.removeMatching(::affected)
                photoIndex.removeMatching(::affected)
                displayAliases.entries.removeAll { affected(it.value) }
                val entries = chunkIndex.entries.iterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    if (affected(entry.key)) {
                        chunkIndexBytes -= entry.value
                        chunkTouched.remove(entry.key)
                        entries.remove()
                    }
                }
                listOf(chunkDirectory, File(cache, "previews"), File(cache, "photos"), cache).forEach { dir ->
                    dir.listFiles()?.filter { it.isFile && affected(it) }?.forEach { it.delete() }
                }
            }
        }
        // Newer last-message dates for loaded dialogs. The catalog's last entry is the paging
        // offset for loadMoreChats and stays untouched.
        val lastIndex = chats.size - 1
        latest.forEach { (chatId, newest) ->
            val index = chats.indexOfFirst { it.id == chatId }
            if (index in 0 until lastIndex) {
                val chat = chats[index]
                if ((chat.lastMessageDate ?: 0L) < newest.first)
                    chats[index] = chat.copy(lastMessageDate = newest.first, lastMessageId = newest.second)
            }
        }
        return if (everything || changedMessages.isNotEmpty()) ContentChanges(changedMessages, everything) else null
    }

    @Volatile private var contentChangedListener: (suspend (String, Set<Pair<Long, Int>>, Boolean) -> Unit)? = null
    override fun setContentChangedListener(listener: (suspend (String, Set<Pair<Long, Int>>, Boolean) -> Unit)?) {
        contentChangedListener = listener
    }
    @Volatile private var artworkChangedListener: (() -> Unit)? = null
    override fun setArtworkChangedListener(listener: (() -> Unit)?) { artworkChangedListener = listener }

    @Volatile private var sessionLostListener: ((String) -> Unit)? = null
    override fun setSessionLostListener(listener: ((String) -> Unit)?) { sessionLostListener = listener }

    /** Monotonic end of a FLOOD_WAIT Telegram asked for; every RPC waits it out first. */
    @Volatile private var floodUntilMs = 0L

    /**
     * Every RPC goes through here, with the Monogram app's error handling:
     * - FLOOD_WAIT up to 5 s is waited out silently and the request resent (up to 6 times);
     *   longer waits fail at once with [TelegramFloodWaitException] so the screen can show them.
     *   While a wait lasts, later RPCs wait too instead of extending it.
     * - Network errors (timeout, closed connection, OS errors) drop the connection so the next
     *   attempt reconnects; the request is sent once more.
     * - A revoked or unknown session fails with [TelegramSessionLostException]: sign in again.
     */
    private suspend fun <T> nativeCall(
        connect: Boolean = true,
        /** Longest FLOOD_WAIT waited out instead of failing; playback waits longer than browsing. */
        maxFloodWaitSeconds: Int = TELEGRAM_FLOOD_AUTO_WAIT_SECONDS,
        action: (Long) -> T,
    ): T {
        var floodRetries = 0
        var networkRetried = false
        while (true) {
            awaitFloodWait(maxFloodWaitSeconds)
            try {
                return nativeCallOnce(connect, action)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (flood: TelegramFloodWaitException) {
                // Already classified (search): record, never retry.
                noteFloodWait(flood.seconds)
                throw flood
            } catch (failure: Exception) {
                if (failure is AmbientMonogramJniFailure && failure.code == "cancelled")
                    throw kotlinx.coroutines.CancellationException("Monogram media request cancelled", failure)
                val failureKind = if (failure is AmbientMonogramJniFailure && failure.code == "unknown-client")
                    TelegramFailureKind.SESSION else classifyTelegramFailure(failure.message)
                when (failureKind) {
                    TelegramFailureKind.FLOOD -> {
                        val seconds = telegramFloodWaitSeconds(failure.message) ?: throw failure
                        noteFloodWait(seconds)
                        // The loop's awaitFloodWait then sleeps until the wait is over.
                        if (seconds > maxFloodWaitSeconds || floodRetries++ >= TELEGRAM_FLOOD_AUTO_RETRIES)
                            throw TelegramFloodWaitException(seconds)
                    }
                    TelegramFailureKind.NETWORK -> {
                        // The native client reconnects on the next connect(); a stale "connected"
                        // made every later request fail until the app was restarted.
                        connected = false
                        if (networkRetried) throw failure
                        networkRetried = true
                    }
                    TelegramFailureKind.SESSION -> {
                        val type = telegramSessionErrorType(failure.message)
                        updatesJob?.cancel()
                        updatesStarted = false
                        sessionLostListener?.invoke(type)
                        throw TelegramSessionLostException(type)
                    }
                    TelegramFailureKind.OTHER -> throw failure
                }
            }
        }
    }

    /**
     * The native scheduler routes a download by the calling thread's dispatch class; a thread
     * left at the default "read" class fails every media download with "read class on the
     * media family". IO threads are pooled, so the class is reset afterwards.
     */
    private inline fun <T> mediaClass(dispatchClass: Int, body: () -> T): T {
        native.setDispatchClass(dispatchClass)
        try { return body() } finally { native.setDispatchClass(DISPATCH_READ) }
    }

    private fun noteFloodWait(seconds: Int) {
        val until = android.os.SystemClock.elapsedRealtime() + seconds.coerceAtLeast(1) * 1_000L
        floodUntilMs = maxOf(floodUntilMs, until)
    }

    /** Waits out a short FLOOD_WAIT; a long one is reported at once with its remaining seconds. */
    private suspend fun awaitFloodWait(maxSeconds: Int = TELEGRAM_FLOOD_AUTO_WAIT_SECONDS) {
        val waitMs = floodUntilMs - android.os.SystemClock.elapsedRealtime()
        if (waitMs <= 0) return
        val seconds = ((waitMs + 999) / 1_000).toInt()
        if (seconds > maxSeconds + 1) throw TelegramFloodWaitException(seconds)
        kotlinx.coroutines.delay(waitMs + 250)
    }

    private var nativePipelineParts = 0
    private var lastNativeNetworkCheck = -5_000L
    private val nativeLowRam = app.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true

    /** Refresh outside hot read loops; keep the demand lane count fixed at four. */
    private fun refreshNativePipelineLocked(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNativeNetworkCheck < 5_000L) return
        lastNativeNetworkCheck = now
        val parts = runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm?.getNetworkCapabilities(it) }
            monogramNativePipelineParts(
                metered = cm?.isActiveNetworkMetered != false,
                downstreamKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
                lowRam = nativeLowRam,
            )
        }.getOrDefault(2)
        if (force || parts != nativePipelineParts) {
            native.setDownloadConcurrency(4, parts)
            nativePipelineParts = parts
        }
    }

    /** Serializes native operations and keeps network work off the UI thread. */
    private suspend fun <T> nativeCallOnce(connect: Boolean = true, action: (Long) -> T): T =
        withContext(Dispatchers.IO) {
            val current = mutex.withLock {
                if (handle == 0L) {
                    val (id, hash) = checkNotNull(tokenStore.monogramCredentials()) {
                        app.getString(R.string.monogram_api_required)
                    }
                    // Telegram's session list names the app by the API ID's registered title;
                    // device and version are ours to report, so the session reads "Ambient TV".
                    runCatching {
                        val lang = java.util.Locale.getDefault().language.ifBlank { "en" }
                        native.setInitConnectionInfo(
                            deviceModel = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
                                .filter(String::isNotBlank).distinct().joinToString(" ").ifBlank { "Android TV" },
                            systemVersion = "Android ${android.os.Build.VERSION.RELEASE}",
                            appVersion = "Ambient TV ${com.ambient.tv.BuildConfig.VERSION_NAME}",
                            systemLangCode = lang,
                            langPack = "android",
                            langCode = lang,
                        )
                    }
                    handle = native.createClient(id, hash, session.absolutePath)
                    native.setDownloadChunkKib(512)
                    // Measured on a phone: with upstream's pipelined parts (several getFile parts
                    // in flight) a ~15 KiB card preview took 6-17 s, in steps of the native 5 s
                    // silent-response grace: parts past the end of a tiny file go unanswered.
                    // Patched profiles keep thumbnails and individual ranges at one part.
                    // Only known large full-file downloads may use a wider native pipeline.
                    uniffi.monogram_mtproto.setAmbientMediaMode(true)
                    if (com.ambient.tv.BuildConfig.MONOGRAM_PERF_COUNTERS) {
                        uniffi.monogram_mtproto.perfSetEnabled(true)
                        android.util.Log.i("MonogramPerf", "CPU features: ${uniffi.monogram_mtproto.ambientCpuFeatures()}")
                    }
                    uniffi.monogram_mtproto.setAmbientCdnRanges(com.ambient.tv.BuildConfig.MONOGRAM_CDN_RANGES)
                    refreshNativePipelineLocked(force = true)
                    connected = false
                }
                if (connect) refreshNativePipelineLocked()
                if (connect && !connected) {
                    native.connect(handle)
                    connected = true
                }
                // Taken before the mutex is released: a wipe cannot slip in between. The body
                // and action run without suspending, so this thread also releases it.
                handleUse.readLock().lock()
                handle
            }
            try { action(current) } finally { handleUse.readLock().unlock() }
        }

    /** Application credentials changed while logged out: never reuse the old MTProto handle. */
    override suspend fun resetForCredentials() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle != 0L) {
                check(!native.isAuthorized(handle)) { app.getString(R.string.monogram_error_signout_first) }
            }
            wipeLocalLocked()
        }
    }

    /**
     * Server logout failed (e.g. offline): this device must still forget the account. Removes
     * the native session file, account id and every cached thumbnail, photo and media chunk.
     */
    override suspend fun forgetLocalSession() = withContext(Dispatchers.IO) {
        mutex.withLock { wipeLocalLocked() }
    }

    private fun wipeLocalLocked() {
        stopUpdatesPump()
        if (handle != 0L) {
            // Waits for RPCs still using the handle (a running media chunk, a thumbnail).
            handleUse.writeLock().lock()
            try {
                runCatching { native.destroyClient(handle) }
                handle = 0L
                connected = false
            } finally {
                handleUse.writeLock().unlock()
            }
        }
        synchronized(diskChunkLock) {
            invalidateChunksLocked()
            cache.deleteRecursively()
            cache.mkdirs()
        }
        leftChannels.clear()
        recentFeeds.clear()
        // account.session, account.id and any native side files of this account.
        session.parentFile?.listFiles()?.forEach { it.deleteRecursively() }
        accountId = null
        chats.clear(); clearHistoryPages()
        chatsExhausted = false
        phone = null
        codeHash = null
        qrExpiresAt = 0L
        epoch++
        label = "Monogram"
        loginState = MonogramLoginState(MonogramLoginStep.READY)
    }

    private fun authorized(id: Long? = null): MonogramLoginState {
        if (id != null) {
            if (accountId != id) {
                synchronized(diskChunkLock) {
                    invalidateChunksLocked()
                    cache.deleteRecursively()
                    cache.mkdirs()
                }
            }
            accountId = id
            accountIdFile.writeText(id.toString())
        }
        accountId?.let { label = "Monogram #$it" }
        leftChannels.clear()
        recentFeeds.clear()
        chats.clear(); clearHistoryPages()
        chatsExhausted = false
        phone = null
        codeHash = null
        epoch++
        startUpdatesPump()
        loginState = MonogramLoginState(MonogramLoginStep.AUTHORIZED, account = label)
        return loginState
    }

    // Read the locally encrypted native session first, even with no network.
    // The private feed cache may then paint Home before MTProto reconnects.
    override suspend fun restoreSession() = nativeCall(connect = false) {
        if (native.isAuthorized(it)) authorized() else
            MonogramLoginState(MonogramLoginStep.READY).also { state -> loginState = state }
    }

    override suspend fun beginQrLogin(): MonogramLoginState = nativeCall { h ->
        try { qrState(h) } catch (_: MtprotoException.PasswordRequired) {
            MonogramLoginState(MonogramLoginStep.PASSWORD).also { loginState = it }
        }
    }

    private fun qrState(h: Long): MonogramLoginState =
        when (val result = native.exportLoginToken(h)) {
            is QrLoginDto.Token -> {
                val token = Base64.encodeToString(result.token,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                qrExpiresAt = result.expires.toLong()
                MonogramLoginState(MonogramLoginStep.QR, qrUrl = "tg://login?token=$token")
                    .also { loginState = it }
            }
            is QrLoginDto.Authorized -> authorized(result.session.userId)
        }

    override suspend fun acceptLoginLink(link: String): Boolean {
        val token = runCatching {
            Base64.decode(link.substringAfter("token=").substringBefore('&'),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        return nativeCall { h -> native.isAuthorized(h) && runCatching { native.acceptLoginToken(h, token) }.isSuccess }
    }

    override suspend fun pollQrLogin(): MonogramLoginState = nativeCall { h ->
        try {
            if (native.pollLoginToken(h) || native.isAuthorized(h)) {
                if (native.isAuthorized(h)) authorized() else loginState
            } else if (qrExpiresAt > 0 && System.currentTimeMillis() / 1000 >= qrExpiresAt) {
                qrState(h)
            } else loginState
        } catch (_: MtprotoException.PasswordRequired) {
            MonogramLoginState(MonogramLoginStep.PASSWORD).also { loginState = it }
        }
    }

    override suspend fun sendPhoneCode(phone: String): MonogramLoginState =
        nativeCall(connect = false) { h ->
            require(phone.startsWith("+") && phone.length in 7..19) { app.getString(R.string.monogram_error_phone) }
            val sent = native.sendAuthCode(h, phone)
            this.phone = sent.phone
            codeHash = sent.phoneCodeHash
            MonogramLoginState(MonogramLoginStep.CODE).also { loginState = it }
        }

    override suspend fun resendPhoneCode(): MonogramLoginState =
        nativeCall(connect = false) { h ->
            val previous = checkNotNull(phone) { app.getString(R.string.monogram_error_code_first) }
            val hash = checkNotNull(codeHash) { app.getString(R.string.monogram_error_code_first) }
            val sent = native.resendAuthCode(h, previous, hash)
            phone = sent.phone
            codeHash = sent.phoneCodeHash
            MonogramLoginState(MonogramLoginStep.CODE).also { loginState = it }
        }

    override suspend fun submitPhoneCode(code: String): MonogramLoginState = nativeCall { h ->
        try {
            authorized(native.signIn(h, checkNotNull(phone), checkNotNull(codeHash), code).userId)
        } catch (_: MtprotoException.PasswordRequired) {
            MonogramLoginState(MonogramLoginStep.PASSWORD).also { loginState = it }
        }
    }

    override suspend fun submitTwoFactorPassword(password: String): MonogramLoginState =
        nativeCall { h -> authorized(native.checkPassword(h, password).userId) }

    override suspend fun logout() {
        stopUpdatesPump()
        nativeCall { h -> native.logout(h) }
        mutex.withLock {
            chats.clear(); clearHistoryPages()
            chatsExhausted = false
            recentFeeds.clear()
            leftChannels.clear()
            epoch++
            label = "Monogram"
            phone = null
            codeHash = null
            loginState = MonogramLoginState(MonogramLoginStep.READY)
            synchronized(diskChunkLock) {
                invalidateChunksLocked()
                cache.deleteRecursively()
                cache.mkdirs()
            }
            accountIdFile.delete()
            accountId = null
        }
    }

    override suspend fun refreshCatalog() {
        browseMutex.withLock {
            chats.clear(); clearHistoryPages()
            chatsExhausted = false
            recentFeeds.clear()
        }
    }

    private fun requireAuthorized(h: Long) {
        check(native.isAuthorized(h)) { app.getString(R.string.monogram_error_not_authorized) }
    }

    private fun knownChannels(h: Long, count: Int): List<ChatDto> =
        synchronized(catalogLock) { knownChannelsLocked(h, count) }

    private fun knownChannelsLocked(h: Long, count: Int): List<ChatDto> {
        // The dialog list orders the timelines (newest channel first). A TV keeps the app running
        // for days, and Telegram sends no update for most channels: an old list kept the
        // categories on posts of days ago. Reload it (one getChats) once it is a few minutes old.
        val now = android.os.SystemClock.elapsedRealtime()
        if (chats.isNotEmpty() && now - chatsLoadedAtMs > DIALOGS_MAX_AGE_MS) {
            chats.clear()
            chatsExhausted = false
            clearHistoryPages()
        }
        if (chats.isEmpty() && !chatsExhausted) {
            chats.addAll(native.getChats(h))
            chatsLoadedAtMs = now
            if (chats.isEmpty()) chatsExhausted = true
        }
        var pages = 0
        while (!chatsExhausted && chats.count { it.isChannel && !it.left } < count && pages++ < 6) {
            val last = chats.lastOrNull() ?: break
            val page = native.loadMoreChats(h, (last.lastMessageDate ?: 0L).toInt(),
                last.lastMessageId, last.id, 0)
            val existing = chats.mapTo(HashSet()) { it.id }
            val new = page.filter { existing.add(it.id) }
            if (new.isEmpty()) { chatsExhausted = true; break }
            chats.addAll(new)
        }
        return chats.filter { it.isChannel && !it.left && it.id !in leftChannels }
    }

    private fun cursor(index: Int) = "$epoch:$index"
    private fun cursorIndex(value: String?): Int {
        if (value == null) return 0
        val parts = value.split(':')
        require(parts.size == 2 && parts[0].toLongOrNull() == epoch) { "Stale account cursor" }
        return parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid cursor")
    }

    override suspend fun channels(cursor: String?, limit: Int): TelegramChannelPage =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            val index = cursorIndex(cursor)
            val all = knownChannels(h, index + limit.coerceIn(1, 60) + 1)
            val result = all.drop(index).take(limit.coerceIn(1, 60))
            TelegramChannelPage(result.map {
                TelegramChannel(it.id, it.title, thumbnailUrl = preview(h, it.id, 0, true),
                    subscribed = !it.left, unreadCount = it.unreadCount,
                    lastPostAtMs = it.lastMessageDate?.takeIf { date -> date > 0 }?.times(1000L))
            }, if (index + result.size < all.size || (!chatsExhausted && result.isNotEmpty()))
                cursor(index + result.size) else null)
        } }

    override suspend fun channel(chatId: Long): TelegramChannel? =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            // The catalog's first page is loaded once and shared (knownChannels); a separate
            // getChats per lookup returned the same page and ran into FLOOD_WAIT at start.
            val found = chats.firstOrNull { it.id == chatId && it.isChannel }
                ?: knownChannels(h, 1).firstOrNull { it.id == chatId }
                ?: return@nativeCall resolvedChannels[chatId]?.let { title ->
                    // Opened from a link: membership is unknown until the catalog lists it.
                    TelegramChannel(chatId, title, thumbnailUrl = preview(h, chatId, 0, true))
                }
            TelegramChannel(found.id, found.title,
                thumbnailUrl = preview(h, found.id, 0, true),
                subscribed = !found.left && found.id !in leftChannels)
        } }

    override suspend fun resolveChannel(username: String): TelegramChannel? =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            val peer = try {
                native.resolveUsername(h, username)
            } catch (failure: MtprotoException) {
                telegramFloodWaitSeconds(failure.message)?.let { throw TelegramFloodWaitException(it) }
                // USERNAME_NOT_OCCUPIED / USERNAME_INVALID: no such public name.
                return@nativeCall null
            }
            // Users and bots have positive ids, basic groups small negative ones: only channels open here.
            if (peer.isBot || peer.peerId > TELEGRAM_CHANNEL_ID_MAX) return@nativeCall null
            val title = peer.title.ifBlank { peer.username ?: username }
            resolvedChannels[peer.peerId] = title
            val known = chats.firstOrNull { it.id == peer.peerId }
            TelegramChannel(peer.peerId, known?.title ?: title,
                thumbnailUrl = preview(h, peer.peerId, 0, true),
                subscribed = known?.let { !it.left && it.id !in leftChannels })
        } }

    override suspend fun joinChannel(chatId: Long): Boolean =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            val joined = native.joinChat(h, chatId).joined
            if (joined) {
                leftChannels.remove(chatId)
                chats.clear(); clearHistoryPages()
                chatsExhausted = false
                recentFeeds.clear()
                epoch++
            }
            joined
        } }

    override suspend fun leaveChannel(chatId: Long): Boolean =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            // The patched UniFFI call returns Unit and throws MtprotoException when Telegram
            // rejects channels.leaveChannel, so reaching the next line means it was accepted.
            native.leaveChat(h, chatId)
            leftChannels.add(chatId)
            synchronized(diskChunkLock) {
                invalidateChunksLocked()
                cache.deleteRecursively()
                cache.mkdirs()
            }
            chats.clear(); clearHistoryPages()
            chatsExhausted = false
            recentFeeds.clear()
            epoch++
            true
        } }

    /** Only small in-message previews; bounded across a long scrolling session. */
    private fun preview(h: Long, chat: Long, id: Int, peer: Boolean = false): String? {
        val expectedGeneration = cacheGeneration
        val expectedAccount = accountId
        val revision = mediaRevision(chat, id)
        fun valid() = expectedAccount != null && accountId == expectedAccount &&
            cacheGeneration == expectedGeneration && revision == mediaRevision(chat, id) && chat !in leftChannels
        val memoKey = chat to id
        val nowMs = android.os.SystemClock.elapsedRealtime()
        // Peer avatars have their own update semantics; only memoize revisioned messages.
        if (!peer) synchronized(diskChunkLock) {
            if (!valid()) return null
            val memo = inlineMemo[memoKey]
            if (memo != null && memo.revision == revision && nowMs - memo.atMs < 30_000L &&
                inlinePreviewIndex.hit(memo.file)) return memo.file.toURI().toString()
            inlineMemo.remove(memoKey)
        }
        fun rememberInline(file: File) {
            if (peer) return
            inlineMemo[memoKey] = InlineMemo(revision, nowMs, file)
            while (inlineMemo.size > 256) inlineMemo.remove(inlineMemo.keys.first())
        }
        val bytes = if (peer) native.peekPeerInlineThumb(h, chat)
            else native.peekMessageInlineThumb(h, chat, id)
        if (bytes == null || bytes.isEmpty() || bytes.size > 512 * 1024) return null
        // Content-addressed inline art survives unchanged feed refreshes and
        // cannot alias a different preview of exactly the same byte length.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        val key = buildString(64) {
            digest.forEach { byte ->
                val n = byte.toInt() and 255
                append(hex[n ushr 4]); append(hex[n and 15])
            }
        }
        val out = File(cache, if (peer) "channel-$chat-$key.bin" else "thumb-$chat-$id-$key.bin")
        synchronized(diskChunkLock) {
            if (!valid()) return null
            if (out.length() == bytes.size.toLong() && inlinePreviewIndex.hit(out)) {
                rememberInline(out)
                return out.toURI().toString()
            }
        }
        val stage = File(cache, "pending-inline-${java.util.UUID.randomUUID()}.bin")
        try {
            // Neither the JNI data copy nor the file write holds the chunk lock.
            stage.writeBytes(bytes)
            synchronized(diskChunkLock) {
                if (!valid()) return null
                if ((!out.isFile || out.length() != bytes.size.toLong()) && !stage.renameTo(out)) return null
                inlinePreviewIndex.publish(out)
                rememberInline(out)
            }
            return out.toURI().toString()
        } finally { stage.delete() }
    }

    private fun media(
        h: Long, m: MessageDto, channel: String,
        /** Checked before the inline preview is written: skipped posts cost no disk I/O. */
        wanted: (TelegramMediaKind) -> Boolean = { true },
    ): TelegramMedia? {
        // Telegram 'text_url' entities can hide an actual video link behind a title.
        // Resolve every URL, but validate hosts/IDs with the same trusted parser.
        val hiddenLinks = runCatching {
            val entities = JSONArray(m.entitiesJson.orEmpty())
            (0 until entities.length()).mapNotNull { index ->
                entities.optJSONObject(index)?.optString("url")?.takeIf(String::isNotBlank)
            }
        }.getOrDefault(emptyList())
        val youtubeLinks = telegramYouTubeIds(m.text.orEmpty() + " " + hiddenLinks.joinToString(" "))
        val youtube = youtubeLinks.firstOrNull()
        val type = when (m.mediaKind?.lowercase()) {
            "video", "gif" -> TelegramMediaKind.VIDEO
            "audio", "voice" -> TelegramMediaKind.AUDIO
            "photo" -> TelegramMediaKind.PHOTO
            "document" -> when {
                m.fileName.orEmpty().endsWith(".mp4", true) ||
                    m.fileName.orEmpty().endsWith(".mkv", true) -> TelegramMediaKind.VIDEO
                m.fileName.orEmpty().endsWith(".mp3", true) ||
                    m.fileName.orEmpty().endsWith(".flac", true) -> TelegramMediaKind.AUDIO
                else -> null
            }
            else -> null
        } ?: if (youtube != null) TelegramMediaKind.YOUTUBE else return null
        if (!wanted(type)) return null
        return TelegramMedia(
            chatId = m.chatId, messageId = m.id, channel = channel,
            title = m.text?.trim()?.take(150)?.takeIf(String::isNotEmpty)
                ?: m.fileName?.takeIf(String::isNotEmpty) ?: channel,
            kind = type, summary = m.text,
            thumbnailUrl = preview(h, m.chatId, m.id),
            durationMs = (m.mediaDuration ?: 0).toLong() * 1000L,
            fileSize = m.fileSize ?: 0L,
            publishedAtMs = m.date * 1000L,
            youtubeVideoId = if (type == TelegramMediaKind.YOUTUBE) youtube else null,
            linkedYouTubeVideoIds = youtubeLinks,
            protectedContent = m.noforwards,
            width = m.mediaWidth?.takeIf { it > 0 } ?: 0,
            height = m.mediaHeight?.takeIf { it > 0 } ?: 0,
            fileName = m.fileName?.trim()?.takeIf(String::isNotEmpty),
            nativeMediaKey = m.mediaCacheKey,
        )
    }

    /** Telegram search filter of a category timeline; null reads plain history (recent). */
    private fun categoryFilter(section: String): String? = when (section) {
        "youtube" -> "url"
        "photos" -> "photos"
        "videos" -> "videos"
        "audio" -> "music"
        else -> null
    }

    /**
     * One newest-first timeline across all channels, like SmartTube's subscriptions: "recent"
     * over every post, a category over Telegram's media filter for it (every returned message
     * is a hit, instead of scanning plain history for the odd video).
     */
    private fun recent(h: Long, token: String?, limit: Int, section: String = "recent"): TelegramMediaPage {
        val revision = recentFeedRevision.get()
        if (recentFeedsSeen != revision) {
            recentFeeds.clear()
            recentFeedsSeen = revision
        }
        val filter = categoryFilter(section)
        // Categories: Telegram's own "media of all chats" (one searchGlobal per page) instead
        // of one search per channel; the per-channel timeline stays the fallback.
        if (filter != null && (token == null || token.startsWith("global:"))) {
            globalCategory(h, token, limit, section, filter)?.let { global ->
                if (token != null) return global
                // Telegram's search index can trail the channels by days, so the first page
                // also takes the category's posts from the newest history ("Neue Medien"), which
                // is shared and usually cached. Newest first; paging continues the search.
                val fromHistory = runCatching { recent(h, null, limit, "recent").items }.getOrDefault(emptyList())
                    .flatMap { item -> if (section == "youtube") item.youtubePosts() else listOf(item) }
                    .filter { categoryKindMatches(section, it.kind) }
                val merged = (global.items + fromHistory).distinctBy(TelegramMedia::mediaKey)
                    .sortedByDescending(TelegramMedia::publishedAtMs)
                    .take(limit.coerceIn(1, 60))
                return TelegramMediaPage(merged, global.nextCursor)
            }
        }
        val wanted: (TelegramMediaKind) -> Boolean = when (section) {
            "videos" -> { kind -> kind == TelegramMediaKind.VIDEO }
            "audio" -> { kind -> kind == TelegramMediaKind.AUDIO }
            "photos" -> { kind -> kind == TelegramMediaKind.PHOTO }
            "recent" -> { kind -> kind != TelegramMediaKind.PHOTO }
            else -> { _ -> true }
        }
        // The cursor is "<feed>:<page>": a new value per page, or a caller that stops on an
        // unchanged cursor ended the list after the second page.
        val cursorToken = token?.substringBeforeLast(':') ?: ("recent:" + epoch + ":" + (++nextRecentFeed))
        val pageNumber = token?.substringAfterLast(':')?.toIntOrNull() ?: 0
        val timeline = if (token == null) {
            fun timelineChannel(channel: ChatDto) =
                MonogramRecentTimeline.Channel(channel.id, (channel.lastMessageDate ?: 0L) * 1000L)
            // Newest possible message of a dialog not loaded yet (dialogs come newest first).
            fun unloadedBound(): Long? = if (chatsExhausted) null
                else chats.lastOrNull()?.lastMessageDate?.times(1000L) ?: Long.MAX_VALUE
            // The first channels only; the timeline pages the catalog when an unloaded channel
            // could hold the next item. A large account no longer loads every dialog up front.
            val initial = knownChannels(h, RECENT_CATALOG_STEP)
            MonogramRecentTimeline(
                initial.map(::timelineChannel),
                unloadedBoundMs = unloadedBound(),
                moreChannels = {
                    val loaded = chats.count { it.isChannel && !it.left }
                    val all = knownChannels(h, loaded + RECENT_CATALOG_STEP)
                    MonogramRecentTimeline.CatalogPage(all.map(::timelineChannel), unloadedBound())
                },
                parallelism = RECENT_PARALLEL_CHANNELS,
            ) { id, offset, size ->
                val history = if (filter != null)
                    native.searchMessagesPage(h, id, "", filter, offset, 0, size).messages
                else historyPage(h, id, size, offset)
                val title = chats.firstOrNull { it.id == id }?.title ?: "Monogram"
                val items = history.mapNotNull { entry -> media(h, entry, title, wanted) }
                    .flatMap { item -> if (section == "youtube") item.youtubePosts() else listOf(item) }
                    .filter { section != "youtube" || it.kind == TelegramMediaKind.YOUTUBE }
                val next = history.lastOrNull()?.id?.takeIf { history.size == size }
                MonogramRecentTimeline.HistoryBatch(
                    items, next,
                    history.lastOrNull()?.date?.times(1000L) ?: Long.MIN_VALUE)
            }.also { recentFeeds[cursorToken] = it }
        } else {
            require(token.startsWith("recent:" + epoch + ":")) { "Stale Monogram recent cursor" }
            checkNotNull(recentFeeds[cursorToken]) { "Expired Monogram recent cursor" }
        }
        val page = timeline.nextPage(limit.coerceIn(1, 60))
        if (recentFeedRevision.get() != revision) {
            recentFeeds.remove(cursorToken)
            throw java.io.IOException("Monogram timeline changed during request")
        }
        if (!page.hasMore) recentFeeds.remove(cursorToken)
        while (recentFeeds.size > 8) recentFeeds.remove(recentFeeds.keys.first())
        return TelegramMediaPage(page.media, if (page.hasMore) "$cursorToken:${pageNumber + 1}" else null)
    }

    private fun categoryKindMatches(section: String, kind: TelegramMediaKind): Boolean = when (section) {
        "videos" -> kind == TelegramMediaKind.VIDEO
        "audio" -> kind == TelegramMediaKind.AUDIO
        "youtube" -> kind == TelegramMediaKind.YOUTUBE
        else -> true
    }

    /** Telegram refused a filtered global search without query once: use per-channel search. */
    @Volatile private var globalCategoryUnsupported = false

    /**
     * One newest-first page of a category over all subscribed channels: messages.searchGlobal
     * with the media filter and broadcasts_only (Ambient TV's patched native export). The cursor
     * carries Telegram's own offsets ("global:<epoch>:<rate>:<peer>:<id>"), so no state is kept.
     * Returns null when the server rejects it on the first page; the caller then falls back.
     */
    private fun globalCategory(h: Long, token: String?, limit: Int, section: String, filter: String): TelegramMediaPage? {
        if (globalCategoryUnsupported && token == null) return null
        var rate = 0
        var peer = 0L
        var offset = 0
        if (token != null) {
            val parts = token.split(':')
            require(parts.size == 5 && parts[1].toLongOrNull() == epoch) { "Stale Monogram category cursor" }
            rate = parts[2].toInt(); peer = parts[3].toLong(); offset = parts[4].toInt()
        }
        val wanted: (TelegramMediaKind) -> Boolean = when (section) {
            "videos" -> { kind -> kind == TelegramMediaKind.VIDEO }
            "audio" -> { kind -> kind == TelegramMediaKind.AUDIO }
            else -> { _ -> true }
        }
        // Titles come from the dialog snapshot (one getChats when empty).
        knownChannels(h, 1)
        val wantedCount = limit.coerceIn(1, 60)
        val items = LinkedHashMap<String, TelegramMedia>()
        var more = true
        var rounds = 0
        while (items.size < wantedCount && rounds++ < GLOBAL_CATEGORY_ROUNDS) {
            val page = try {
                uniffi.monogram_mtproto.searchGlobalFiltered(h.toULong(), "", filter, true,
                    rate, peer, offset, GLOBAL_CATEGORY_PAGE)
            } catch (failure: Exception) {
                if (token == null && rounds == 1 &&
                    classifyTelegramFailure(failure.message) == TelegramFailureKind.OTHER) {
                    globalCategoryUnsupported = true
                    return null
                }
                throw failure
            }
            for (message in page.messages) {
                if (message.chatId in leftChannels) continue
                val title = chats.firstOrNull { it.id == message.chatId }?.title ?: continue
                val found = media(h, message, title, wanted) ?: continue
                val posts = if (section == "youtube") found.youtubePosts().filter { it.kind == TelegramMediaKind.YOUTUBE }
                    else listOf(found)
                posts.forEach { items.putIfAbsent(it.mediaKey, it) }
            }
            if (page.messages.isEmpty() || page.nextOffsetId == 0) { more = false; break }
            rate = page.nextRate; peer = page.nextPeerId; offset = page.nextOffsetId
        }
        return TelegramMediaPage(items.values.toList(), if (more) "global:$epoch:$rate:$peer:$offset" else null)
    }

    // A single channel keeps its history-offset cursor; recent and categories use a global timeline.
    override suspend fun browse(section: String, cursor: String?, limit: Int): TelegramMediaPage {
        noteInteractiveActivity()
        return if (!section.startsWith("channel:")) browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            recent(h, cursor, limit, section)
        } } else browsePermits.withPermit { nativeCall { h ->
            requireAuthorized(h)
            val parts = cursor?.split(':')
            require(parts == null || (parts.size == 3 && parts[0].toLongOrNull() == epoch))
            var index = parts?.get(1)?.toIntOrNull() ?: 0
            var offset = parts?.get(2)?.toIntOrNull() ?: 0
            require(index >= 0 && offset >= 0)
            val channelId = section.takeIf { it.startsWith("channel:") }
                ?.substringAfter(':')?.toLongOrNull()
            if (channelId != null && channelId in leftChannels)
                return@nativeCall TelegramMediaPage(emptyList())
            val available = if (channelId != null) {
                listOf(channelId to (knownChannels(h, 1).firstOrNull { it.id == channelId }?.title ?: "Monogram"))
            } else knownChannels(h, index + 2).map { it.id to it.title }
            val items = ArrayList<TelegramMedia>()
            var steps = 0
            while (index < available.size && items.size < limit.coerceIn(1, 60) && steps++ < 12) {
                val (id, title) = available[index]
                val fetchLimit = minOf(60, limit.coerceIn(1, 60) - items.size)
                val history = historyPage(h, id, fetchLimit, offset)
                items.addAll(history.mapNotNull { media(h, it, title) })
                if (history.size == fetchLimit) {
                    offset = history.last().id
                    if (items.size >= limit.coerceIn(1, 60)) break
                } else {
                    index++
                    offset = 0
                }
            }
            val next = if (index < available.size || (channelId == null && !chatsExhausted))
                "$epoch:$index:$offset" else null
            TelegramMediaPage(items.distinctBy(TelegramMedia::mediaKey), next)
        } }
    }

    /**
     * messages.searchGlobal has no media filter (search_rpc.rs), so its hits are reduced to
     * playable video/audio posts from subscribed channels. Only when that yields few hits, a
     * small, paced per-channel search (videos, music) over the most recently active channels
     * supplements it. A FLOOD_WAIT stops the search and is reported, never retried here.
     */
    override suspend fun search(query: String, limit: Int): List<TelegramMedia> =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            val wanted = limit.coerceIn(1, 50)
            try {
                // The current dialog snapshot (one getChats when empty). Posts from channels
                // not yet paged into it are left out rather than paging dialogs per search.
                val channels = knownChannels(h, 1).associateBy { it.id }
                val subscribed = channels.keys - leftChannels
                val found = LinkedHashMap<String, TelegramMedia>()
                fun collect(messages: List<MessageDto>) {
                    for (message in messages) {
                        if (found.size >= wanted) return
                        val chat = channels[message.chatId] ?: continue
                        // Skip text and photo posts before media() fetches any preview.
                        if (message.mediaKind == null || message.mediaKind.equals("photo", true)) continue
                        val item = media(h, message, chat.title) ?: continue
                        telegramSearchResults(listOf(item), subscribed, leftChannels, 1)
                            .firstOrNull()?.let { found.putIfAbsent(it.mediaKey, it) }
                    }
                }
                collect(native.searchGlobal(h, query, 0, 0L, 0, SEARCH_GLOBAL_LIMIT, 0).messages)
                if (found.size < wanted / 2) {
                    val recent = channels.values.filter { it.id in subscribed }
                        .sortedByDescending { it.lastMessageDate ?: 0L }
                        .take(SEARCH_SUPPLEMENT_CHANNELS)
                    loop@ for (chat in recent) for (filter in listOf("videos", "music")) {
                        if (found.size >= wanted) break@loop
                        Thread.sleep(CATALOG_ROUND_PAUSE_MS)
                        collect(native.searchMessagesPage(h, chat.id, query, filter, 0, 0,
                            SEARCH_CHANNEL_LIMIT).messages)
                    }
                }
                found.values.toList()
            } catch (failure: MtprotoException) {
                telegramFloodWaitSeconds(failure.message)?.let { throw TelegramFloodWaitException(it) }
                throw failure
            }
        } }

    override suspend fun media(chatId: Long, messageId: Int): TelegramMedia? = nativeCall { h ->
        requireAuthorized(h)
        if (chatId in leftChannels) return@nativeCall null
        // No paging here. A player or detail lookup reads the catalog, loading its first page
        // once (shared with browsing) instead of a getChats per post, which hit FLOOD_WAIT.
        val title = (chats.firstOrNull { it.id == chatId }
            ?: knownChannels(h, 1).firstOrNull { it.id == chatId })?.title ?: "Monogram"
        native.getMessage(h, chatId, messageId)?.let { media(h, it, title) }
    }

    // The full photo for the viewer: the adapter's downloadMessageDisplay is the full download.
    override suspend fun photoFile(chatId: Long, messageId: Int): String? =
        cachedPhotoFile(chatId, messageId) ?: cancellableMediaRequest(TelegramTransferPriority(true)) { request ->
            nativeCall { h ->
                requireAuthorized(h)
                check(chatId !in leftChannels) { app.getString(R.string.monogram_error_channel_left) }
                val dir = File(cache, "photos").apply { mkdirs() }.canonicalFile
                val output = File(dir, "$chatId-$messageId.media")
                val generation = cacheGeneration
                val identity = accountId
                val revision = mediaRevision(chatId, messageId)
                fun valid() = generation == cacheGeneration && identity != null && identity == accountId &&
                    chatId !in leftChannels && revision == mediaRevision(chatId, messageId)
                synchronized(diskChunkLock) {
                    if (!valid()) return@nativeCall null
                    if (photoIndex.hit(output)) return@nativeCall output.toURI().toString()
                }
                val stage = File(dir, "pending-${java.util.UUID.randomUUID()}.media")
                val previous = uniffi.monogram_mtproto.bindRequestControl(request)
                try {
                    val actual = File(mediaClass(DISPATCH_USER) { native.downloadMessageDisplay(h, chatId, messageId, stage.path) })
                        .canonicalFile
                    require(actual == stage.canonicalFile && actual.length() in 1L..128L * 1024 * 1024) {
                        "Monogram full-resolution photo unavailable or exceeds cache budget"
                    }
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(actual.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Monogram photo is not a valid image" }
                    synchronized(diskChunkLock) {
                        check(valid())
                        check(actual.renameTo(output)) { "Monogram photo publication failed" }
                        photoIndex.publish(output)
                    }
                    recordNativeWindow()
                    output.toURI().toString()
                } finally {
                    uniffi.monogram_mtproto.bindRequestControl(previous)
                    stage.delete()
                }
            }
        }

    override suspend fun cachedPhotoFile(chatId: Long, messageId: Int): String? {
        val generation = cacheGeneration
        val identity = accountId ?: return null
        val revision = mediaRevision(chatId, messageId)
        val file = File(File(cache, "photos"), "$chatId-$messageId.media")
        return synchronized(diskChunkLock) {
            if (generation == cacheGeneration && identity == accountId && chatId !in leftChannels &&
                revision == mediaRevision(chatId, messageId) && photoIndex.hit(file))
                file.toURI().toString() else null
        }
    }

    /**
     * Telegram's sharp card preview (size "m", ~320 px) of a video or photo post. The native
     * Thumb kind prefers the inline stripped preview whenever a post carries one (nearly all
     * do), so it returned the same ~40 px image again; Display is the getFile size Monogram
     * itself uses to sharpen a cell. Thumb stays the fallback, but never the inline bytes.
     */
    override suspend fun thumbnail(chatId: Long, messageId: Int): String? =
        thumbnail(chatId, messageId, TelegramTransferPriority(true))

    override suspend fun cachedThumbnail(chatId: Long, messageId: Int): String? =
        cachedThumbnail(chatId, messageId, 320)

    override suspend fun cachedThumbnail(chatId: Long, messageId: Int, target: Int): String? {
        val identity = accountId ?: return null
        val generation = cacheGeneration
        val revision = mediaRevision(chatId, messageId)
        if (chatId in leftChannels) return null
        val cached = File(File(cache, "previews"), "$chatId-$messageId-$target.img")
        return synchronized(diskChunkLock) {
            if (identity != accountId || generation != cacheGeneration || chatId in leftChannels ||
                revision != mediaRevision(chatId, messageId) || !sharpPreviewIndex.hit(cached)) null
            else cached.toURI().toString()
        }
    }

    override suspend fun thumbnail(chatId: Long, messageId: Int, priority: TelegramTransferPriority): String? =
        thumbnail(chatId, messageId, priority, 320)

    override suspend fun thumbnail(chatId: Long, messageId: Int, priority: TelegramTransferPriority, target: Int): String? =
        cachedThumbnail(chatId, messageId, target) ?: downloadThumbnail(chatId, messageId, priority, target)

    private suspend fun downloadThumbnail(chatId: Long, messageId: Int, priority: TelegramTransferPriority, target: Int): String? =
        cancellableMediaRequest(priority) { request ->
        nativeCall { h ->
        requireAuthorized(h)
        if (chatId in leftChannels) return@nativeCall null
        val expectedGeneration = cacheGeneration
        val expectedAccount = accountId
        val revision = mediaRevision(chatId, messageId)
        // "thumbs-hd" held the inline previews the old Thumb download returned: never reuse it.
        File(cache, "thumbs-hd").takeIf { it.isDirectory }?.deleteRecursively()
        val dir = File(cache, "previews").apply { mkdirs() }.canonicalFile
        val output = File(dir, "$chatId-$messageId-$target.img")
        // Only identities in the authenticated shared index qualify. Reference refreshes
        // may change tokens, but the file id, access hash and size letter must match.
        val displayIdentity = uniffi.monogram_mtproto.ambientDisplayIdentity(h.toULong(), chatId, messageId, target.toUInt())
        synchronized(diskChunkLock) {
            if (expectedAccount == null || accountId != expectedAccount || cacheGeneration != expectedGeneration ||
                revision != mediaRevision(chatId, messageId) || chatId in leftChannels) return@nativeCall null
            if (sharpPreviewIndex.hit(output)) return@nativeCall output.toURI().toString()
            if (displayIdentity != null) {
                val source = displayAliases[displayIdentity]
                if (source != null && source != output && sharpPreviewIndex.hit(source)) {
                    // Independent names preserve per-post invalidation; deleting one name
                    // cannot delete the other. Unsupported filesystems use the normal path.
                    val linked = runCatching { android.system.Os.link(source.path, output.path) }.isSuccess
                    if (linked) {
                        displayAliases.entries.removeAll { it.value == output }
                        sharpPreviewIndex.publish(output)
                        displayAliases[displayIdentity] = output
                        return@nativeCall output.toURI().toString()
                    }
                } else if (source != null) displayAliases.remove(displayIdentity)
            }
        }
        val stage = File(dir, "pending-${java.util.UUID.randomUUID()}.img")
        val previous = uniffi.monogram_mtproto.bindRequestControl(request)
        try {
        // Upstream's MtprotoNativeUniFfi.downloadMessageDisplay calls the FULL media download
        // (a whole video for one card); the UniFFI export itself fetches the display size.
        fun display(): File {
            val dispatch = if (priority.foreground) DISPATCH_ORDINARY else DISPATCH_BACKGROUND
            try {
                val bytes = mediaClass(dispatch) { AmbientMonogramJni.preview(h, chatId, messageId, stage.path, target) }
                if (bytes.size !in 1..(1024 * 1024)) throw IllegalStateException("incomplete captured preview")
                // The native sink has already validated exact size and session ownership.
                // Write only once to the Kotlin-controlled atomic publication stage.
                stage.writeBytes(bytes)
                return stage.canonicalFile
            } catch (failure: Exception) {
                val reason = failure.message.orEmpty()
                if (!reason.contains("ambient preview requires file sink") &&
                    !reason.contains("incomplete captured preview") &&
                    !reason.contains("oversized captured media range")) throw failure
            }
            return File(mediaClass(dispatch) {
                uniffi.monogram_mtproto.downloadMessagePreviewFile(h.toULong(), chatId, messageId, stage.path, target.toUInt())
            }).canonicalFile
        }
        var displayResult = true
        val returned = runCatching {
            // The first file request after connecting may be cancelled by the media session
            // switching over ("session changed"): sent once more.
            runCatching { display() }.getOrElse { failure ->
                if (failure.message?.contains("session changed") == true) display() else throw failure
            }
        }.getOrElse { failure ->
            // A FLOOD_WAIT or network error is the caller's to handle; only a post without a
            // display size falls back to its Thumb.
            if (failure.message?.contains("cancel", ignoreCase = true) == true ||
                classifyTelegramFailure(failure.message) != TelegramFailureKind.OTHER) throw failure
            displayResult = false
            // Decode bounds below are authoritative; equal byte counts do not imply
            // equal images. Avoid a second JNI copy of the inline thumbnail.
            File(mediaClass(if (priority.foreground) DISPATCH_VISIBLE else DISPATCH_BACKGROUND) {
                native.downloadMessageThumb(h, chatId, messageId, stage.path)
            }).canonicalFile
        }
        // The native layer may keep the file in its own cache or add an extension: copy it into
        // our bounded cache instead of discarding a valid thumbnail.
        val actual = when {
            returned == stage.canonicalFile -> returned
            returned.isFile && returned.length() in 1L..(2L * 1024 * 1024) ->
                runCatching { returned.copyTo(stage, overwrite = true) }.getOrNull() ?: return@nativeCall null
            else -> return@nativeCall null
        }
        // A thumbnail no larger than the inline preview (the Thumb fallback can return the
        // stripped ~40 px image) is never cached as the sharp one.
        if (actual.length() !in 1L..(2L * 1024 * 1024)) return@nativeCall null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(actual.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) < 120) {
            actual.delete()
            return@nativeCall null
        }
        val verifiedDisplay = displayIdentity.takeIf { displayResult && it != null &&
            it == uniffi.monogram_mtproto.ambientDisplayIdentity(h.toULong(), chatId, messageId, target.toUInt()) }
        synchronized(diskChunkLock) {
            // A logout or account switch meanwhile: never keep the old account's image.
            if (expectedAccount == null || accountId != expectedAccount || cacheGeneration != expectedGeneration ||
                revision != mediaRevision(chatId, messageId) || chatId in leftChannels) {
                actual.delete()
                return@nativeCall null
            }
            if (!actual.renameTo(output)) return@nativeCall null
            displayAliases.entries.removeAll { it.value == output }
            sharpPreviewIndex.publish(output)
            if (verifiedDisplay != null && output.isFile) {
                displayAliases[verifiedDisplay] = output
                while (displayAliases.size > 256) displayAliases.remove(displayAliases.keys.first())
            }
        }
        recordNativeWindow()
        output.toURI().toString()
        } finally {
            uniffi.monogram_mtproto.bindRequestControl(previous)
            stage.delete()
        }
        }
    }

    // A started video waits out a FLOOD_WAIT caused by browsing or card images instead of
    // failing at once (the player then stood at 0:00).
    // Cancellation must reach a synchronous UniFFI call, not merely cancel the Kotlin waiter.
    private suspend fun <T> cancellableMediaRequest(priority: TelegramTransferPriority, block: suspend (ULong) -> T): T =
        kotlinx.coroutines.coroutineScope {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val request = uniffi.monogram_mtproto.createRequestControl()
                priority.bind { uniffi.monogram_mtproto.promoteRequestControl(request, DISPATCH_USER) }
                val worker = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    val result = runCatching { block(request) }
                    continuation.resumeWith(result)
                }
                worker.invokeOnCompletion {
                    priority.unbind()
                    runCatching { uniffi.monogram_mtproto.releaseRequestControl(request) }
                }
                continuation.invokeOnCancellation {
                    if (priority.foreground) {
                        runCatching { uniffi.monogram_mtproto.cancelRequestControl(request) }
                    } else {
                        // A speculative request already near a verified boundary may
                        // finish within this small grace period and keep its TCP.
                        // A blocked or obsolete transfer still gets hard-cancelled.
                        val timer = speculativeCancellation.schedule({
                            if (!worker.isCompleted) runCatching {
                                uniffi.monogram_mtproto.cancelRequestControl(request)
                            }
                        }, 80L, java.util.concurrent.TimeUnit.MILLISECONDS)
                        worker.invokeOnCompletion { timer.cancel(false) }
                    }
                    worker.cancel()
                }
                worker.start()
            }
        }

    override suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray =
        loadMediaPart(chatId, messageId, offset, null, null, TelegramTransferPriority(true))

    override suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long, expectedBytes: Int): ByteArray =
        loadMediaPart(chatId, messageId, offset, expectedBytes, null, TelegramTransferPriority(true))

    override suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long, expectedBytes: Int, nativeMediaKey: String?): ByteArray =
        loadMediaPart(chatId, messageId, offset, expectedBytes, nativeMediaKey, TelegramTransferPriority(true))

    override suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long, expectedBytes: Int, nativeMediaKey: String?,
        priority: TelegramTransferPriority): ByteArray =
        loadMediaPart(chatId, messageId, offset, expectedBytes, nativeMediaKey, priority)

    private suspend fun loadMediaPart(chatId: Long, messageId: Int, offset: Long, expectedBytes: Int?,
        nativeMediaKey: String?, priority: TelegramTransferPriority): ByteArray =
        loadMediaWindow(chatId, messageId, offset, listOf(expectedBytes), nativeMediaKey, priority).single()

    override suspend fun mediaWindow(chatId: Long, messageId: Int, offset: Long, lengths: List<Int>,
        nativeMediaKey: String?, priority: TelegramTransferPriority): List<ByteArray> =
        loadMediaWindow(chatId, messageId, offset, lengths, nativeMediaKey, priority)

    private suspend fun loadMediaWindow(chatId: Long, messageId: Int, offset: Long, lengths: List<Int?>,
        nativeMediaKey: String?, priority: TelegramTransferPriority): List<ByteArray> {
        noteInteractiveActivity()
        require(offset >= 0L && offset % TELEGRAM_MEDIA_PART_BYTES == 0L)
        require(lengths.size in 1..2 && lengths.all { it == null || it in 1..TELEGRAM_MEDIA_PART_BYTES })
        require(lengths.size == 1 || lengths.first() == TELEGRAM_MEDIA_PART_BYTES && lengths.last() != null)
        fun validSize(index: Int, size: Int) = size in 1..TELEGRAM_MEDIA_PART_BYTES &&
            (lengths[index] == null || size == lengths[index])
        val generation = cacheGeneration
        val currentIdentity = accountId
        val revision = mediaRevision(chatId, messageId)
        fun verifyGeneration() {
            check(accountId == currentIdentity && cacheGeneration == generation && chatId !in leftChannels &&
                revision == mediaRevision(chatId, messageId)) {
                app.getString(R.string.monogram_error_account_changed)
            }
        }
        val dir = withContext(Dispatchers.IO) { chunkDirectory.canonicalFile }
        val mediaIdentity = nativeMediaKey?.takeIf { it.isNotBlank() }
        val digest = mediaIdentity?.let(::mediaDigest)
        val cacheFiles = lengths.indices.map { index ->
            File(dir, "$chatId-$messageId-$digest-${offset + index.toLong() * TELEGRAM_MEDIA_PART_BYTES}.bin")
        }
        fun lookup(index: Int): ByteArray? {
            if (digest == null) return null
            val cacheFile = cacheFiles[index]
            val candidate = synchronized(diskChunkLock) {
                verifyGeneration()
                prepareChunkIndex(dir)
                hotChunks[cacheFile]?.takeIf { validSize(index, it.size) }?.let {
                    chunkIndex[cacheFile]
                    touchChunk(cacheFile)
                    return it
                }
                // Files are published atomically and never modified in place.
                chunkIndex[cacheFile]?.takeIf { it <= TELEGRAM_MEDIA_PART_BYTES && validSize(index, it.toInt()) }
            } ?: return null
            // Do not block unrelated hot-cache lookups or update publication on disk IO.
            val bytes = runCatching { cacheFile.readBytes() }.getOrNull()
            return synchronized(diskChunkLock) {
                verifyGeneration()
                if (bytes != null && bytes.size.toLong() == candidate && validSize(index, bytes.size)) {
                    rememberHotChunk(cacheFile, bytes)
                    touchChunk(cacheFile)
                    bytes
                } else {
                    if (cacheFile.delete() || !cacheFile.exists()) {
                        chunkIndexBytes -= chunkIndex.remove(cacheFile) ?: 0L
                        chunkTouched.remove(cacheFile)
                    }
                    null
                }
            }
        }
        // Cache hits still validate the local authenticated native handle, but do
        // not wait on reconnect, a network flood deadline or a request control.
        val cached = nativeCallOnce(connect = false) { h ->
            requireAuthorized(h)
            lengths.indices.map(::lookup)
        }
        if (cached.all { it != null }) return cached.filterNotNull()
        if (lengths.size == 2 && cached.any { it != null }) {
            // A partial hit must not redownload a cached neighbor.
            return lengths.indices.map { index ->
                cached[index] ?: loadMediaPart(chatId, messageId, offset + index.toLong() * TELEGRAM_MEDIA_PART_BYTES,
                    lengths[index], nativeMediaKey, priority)
            }
        }

        return cancellableMediaRequest(priority) { request ->
            nativeCall(maxFloodWaitSeconds = PLAYBACK_FLOOD_WAIT_SECONDS) { h ->
                requireAuthorized(h)
                verifyGeneration()
                // Another reader may have filled the cache while we waited to connect.
                val ready = lengths.indices.map(::lookup)
                if (ready.all { it != null }) return@nativeCall ready.filterNotNull()
                val temp = synchronized(diskChunkLock) {
                    verifyGeneration()
                    // A unique destination name does not create an empty file.
                    File(dir, "part-${java.util.UUID.randomUUID()}.bin")
                }
                try {
                    val previous = uniffi.monogram_mtproto.bindRequestControl(request)
                    val payload = try {
                        mediaClass(if (priority.foreground) DISPATCH_USER else DISPATCH_BACKGROUND) {
                            AmbientMonogramJni.mediaRange(h, chatId, messageId, temp.absolutePath, offset, mediaIdentity.orEmpty(), lengths.size)
                        }
                    } finally {
                        uniffi.monogram_mtproto.bindRequestControl(previous)
                    }
                    val parts = if (lengths.size == 1) listOf(payload) else {
                        require(payload.size == lengths.sumOf { checkNotNull(it) }) { "Incomplete Monogram window" }
                        listOf(payload.copyOfRange(0, TELEGRAM_MEDIA_PART_BYTES),
                            payload.copyOfRange(TELEGRAM_MEDIA_PART_BYTES, payload.size))
                    }
                    require(parts.indices.all { validSize(it, parts[it].size) }) { "Invalid or incomplete Monogram chunk" }
                    recordNativeWindow()
                    verifyGeneration()
                    requireAuthorized(h)
                    val stages = parts.indices.map { index ->
                        if (parts.size == 1) temp else File(dir, "${temp.name}-$index.bin")
                    }
                    try {
                        val staged = parts.indices.map { index ->
                            digest != null && runCatching {
                                dir.mkdirs()
                                if (stages[index].length() != parts[index].size.toLong()) stages[index].writeBytes(parts[index])
                            }.isSuccess
                        }
                        synchronized(diskChunkLock) {
                            verifyGeneration()
                            if (digest != null) {
                                prepareChunkIndex(dir)
                                parts.indices.forEach { index ->
                                    val cacheFile = cacheFiles[index]
                                    rememberHotChunk(cacheFile, parts[index])
                                    if (staged[index] && !cacheFile.isFile && stages[index].renameTo(cacheFile)) rememberChunk(cacheFile)
                                }
                                trimChunkCache()
                            }
                        }
                        parts
                    } finally { stages.forEach { it.delete() } }
                } finally {
                    temp.delete()
                }
            }
        }
    }

    /** All index operations are under diskChunkLock, including account invalidation. */
    private fun prepareChunkIndex(dir: File) {
        if (chunkIndexGeneration == cacheGeneration) return
        chunkIndex.clear()
        chunkTouched.clear()
        chunkIndexBytes = 0L
        dir.listFiles().orEmpty().filter {
            it.isFile && it.name.endsWith(".bin") && !it.name.startsWith("part-")
        }.sortedBy { it.lastModified() }.forEach { rememberChunk(it) }
        chunkIndexGeneration = cacheGeneration
        trimChunkCache()
    }

    private fun rememberChunk(file: File) {
        val size = file.length()
        chunkIndexBytes += size - (chunkIndex.put(file, size) ?: 0L)
        chunkTouched[file] = file.lastModified()
    }

    /** Persist recency at most once per minute per on-disk entry. */
    private fun touchChunk(file: File) {
        val touched = chunkTouched[file] ?: return
        val now = System.currentTimeMillis()
        if (now - touched >= 60_000L && file.setLastModified(now)) chunkTouched[file] = now
    }

    private fun trimChunkCache() {
        val iterator = chunkIndex.entries.iterator()
        while (chunkIndexBytes > 64L * 1024 * 1024 && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.delete() || !entry.key.exists()) {
                chunkIndexBytes -= entry.value
                chunkTouched.remove(entry.key)
                iterator.remove()
            }
        }
    }

    private companion object {
        val speculativeCancellation by lazy {
            java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "monogram-cancel").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
        }
        /** Pause between per-channel search requests (Telegram FLOOD_WAIT). */
        const val CATALOG_ROUND_PAUSE_MS = 300L
        /** Channels added to the recent timeline per catalog step. */
        const val RECENT_CATALOG_STEP = 20
        const val PLAYBACK_FLOOD_WAIT_SECONDS = 30
        /** Channel histories fetched at once by a timeline page. */
        const val RECENT_PARALLEL_CHANNELS = 6
        /** Messages per searchGlobal request, and requests per category page at most. */
        const val GLOBAL_CATEGORY_PAGE = 50
        const val GLOBAL_CATEGORY_ROUNDS = 3
        /** Native dispatch classes (ffi.rs set_dispatch_class). */
        const val DISPATCH_READ = 0
        const val DISPATCH_BACKGROUND = 3
        const val DISPATCH_VISIBLE = 6
        /** 128 KiB parts, 4 in flight (Visible: 512 KiB x 8, which stalls on a 15 KiB preview). */
        const val DISPATCH_ORDINARY = 5
        const val DISPATCH_USER = 7
        /** Update stream drain interval; draining is local, it sends nothing to Telegram. */
        const val UPDATES_POLL_MS = 5_000L
        /** Shared channel history pages: long enough for one browse session across categories. */
        const val HISTORY_PAGE_TTL_MS = 120_000L
        /** Age after which the dialog list (channel order, newest posts) is loaded again. */
        const val DIALOGS_MAX_AGE_MS = 10 * 60_000L
        const val HISTORY_SHARED_PAGE = 60
        const val HISTORY_PAGE_LIMIT = 256
        const val SEARCH_GLOBAL_LIMIT = 50
        const val SEARCH_SUPPLEMENT_CHANNELS = 3
        const val SEARCH_CHANNEL_LIMIT = 20
    }
}

/** Channel and supergroup chat ids are -(10^12 + channel id); anything above is a user or basic group. */
private const val TELEGRAM_CHANNEL_ID_MAX = -1_000_000_000_000L


/** Keep the exact JNI class/method names in minified release APKs. */
@androidx.annotation.Keep
internal object AmbientMonogramJni {
    @Volatile private var jniAvailable = true
    external fun flushPgoProfile(destination: String): Int

    @Volatile private var previewJniAvailable = true
    private external fun downloadPreview(handle: Long, chatId: Long, messageId: Int, destination: String, target: Int): ByteArray

    fun preview(handle: Long, chatId: Long, messageId: Int, destination: String, target: Int): ByteArray {
        if (previewJniAvailable) {
            try {
                return downloadPreview(handle, chatId, messageId, destination, target)
            } catch (_: UnsatisfiedLinkError) {
                previewJniAvailable = false
            }
        }
        return uniffi.monogram_mtproto.downloadMessagePreviewBytes(handle.toULong(), chatId, messageId, destination, target.toUInt())
    }

    private external fun downloadMediaRange(
        handle: Long, chatId: Long, messageId: Int, destination: String, offset: Long, expectedKey: String, parts: Int,
    ): ByteArray

    fun mediaRange(handle: Long, chatId: Long, messageId: Int, destination: String, offset: Long, expectedKey: String, parts: Int): ByteArray {
        if (jniAvailable) {
            try {
                return downloadMediaRange(handle, chatId, messageId, destination, offset, expectedKey, parts)
            } catch (_: UnsatisfiedLinkError) {
                // A missing symbol is the only safe reason to retry through UniFFI.
                // Network, authentication and RPC errors must not issue a second request.
                jniAvailable = false
            }
        }
        return uniffi.monogram_mtproto.downloadMessageMediaWindowBytes(
            handle.toULong(), chatId, messageId, destination, offset, expectedKey, parts.toUInt())
    }
}

// JNI constructs this exact exception using ThrowNew; keep its name and constructor.
@androidx.annotation.Keep
internal class AmbientMonogramJniFailure(message: String) : RuntimeException(message.substringAfter(':')) {
    val code: String = message.substringBefore(':')
}

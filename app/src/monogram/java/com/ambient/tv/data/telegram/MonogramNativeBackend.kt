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
    private val cache = File(app.cacheDir, "monogram").apply { mkdirs() }
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
    /** Account-generation fences prevent older thumbnail RPCs repopulating a new account's cache. */
    @Volatile private var cacheGeneration = 0L
    private var previewWrites = 0
    private val accountIdFile = File(session.parentFile, "account.id")
    private var accountId: Long? = accountIdFile.takeIf { it.isFile }?.let {
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
    @Volatile private var chatsExhausted = false
    private val recentFeeds = LinkedHashMap<String, MonogramRecentTimeline>()
    private val leftChannels = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
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

    private fun historyPage(h: Long, chatId: Long, size: Int, offset: Int): List<MessageDto> {
        val key = chatId to offset
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(historyPages) {
            historyPages[key]?.takeIf { now - it.atMs < HISTORY_PAGE_TTL_MS &&
                (it.requested >= size || it.messages.size < it.requested) }
                ?.let { return it.messages.take(size) }
        }
        // One shared, slightly larger page serves the smaller requests of other categories.
        val requested = maxOf(size, HISTORY_SHARED_PAGE).coerceAtMost(100)
        val fetched = native.getHistoryPage(h, chatId, requested, offset, 0, 0)
        synchronized(historyPages) {
            historyPages[key] = HistoryPage(requested, fetched, now)
            while (historyPages.size > HISTORY_PAGE_LIMIT) historyPages.remove(historyPages.keys.first())
        }
        return fetched.take(size)
    }

    private fun clearHistoryPages() = synchronized(historyPages) { historyPages.clear() }

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

    private fun startUpdatesPump() {
        if (updatesJob?.isActive == true) return
        updatesJob = updatesScope.launch {
            var backoffMs = UPDATES_POLL_MS
            while (true) {
                kotlinx.coroutines.delay(backoffMs)
                backoffMs = try {
                    applyUpdates(nativeCall { h -> drainUpdateEvents(h) })
                    UPDATES_POLL_MS
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

    /** Drains and acknowledges the delivered batches; replays pending ones when asked to. */
    private fun drainUpdateEvents(h: Long): List<UpdateEventDto> {
        if (!native.isAuthorized(h)) return emptyList()
        if (!updatesStarted) {
            native.startUpdates(h)
            updatesStarted = true
        }
        val drained = native.drainUpdateBatches(h)
        val batches = if (drained.replayRequired)
            (drained.batches + native.pendingUpdateBatches(h)).distinctBy { it.batchId }
        else drained.batches
        // Nothing is stored durably here: acknowledge so the native side does not redeliver.
        batches.forEach { batch ->
            runCatching { native.acknowledgeUpdateBatch(h, batch.accountGeneration, batch.batchId) }
        }
        return batches.flatMap { it.events } + drained.liveEvents
    }

    private fun applyUpdates(events: List<UpdateEventDto>) {
        if (events.isEmpty()) return
        val touched = HashSet<Long>()
        var everything = false
        val latest = HashMap<Long, Pair<Long, Int>>()
        for (event in events) {
            when (event) {
                is UpdateEventDto.NewMessage -> {
                    touched += event.message.chatId
                    val date = event.message.date.toLong()
                    if ((latest[event.message.chatId]?.first ?: Long.MIN_VALUE) < date)
                        latest[event.message.chatId] = date to event.message.id
                }
                is UpdateEventDto.MessageEdited -> touched += event.message.chatId
                is UpdateEventDto.MessagesDeleted -> event.chatId?.let { touched += it } ?: run { everything = true }
                else -> Unit
            }
        }
        synchronized(historyPages) {
            if (everything) historyPages.clear()
            else historyPages.keys.removeAll { it.first in touched }
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
    }

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
    private suspend fun <T> nativeCall(connect: Boolean = true, action: (Long) -> T): T {
        var floodRetries = 0
        var networkRetried = false
        while (true) {
            awaitFloodWait()
            try {
                return nativeCallOnce(connect, action)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (flood: TelegramFloodWaitException) {
                // Already classified (search): record, never retry.
                noteFloodWait(flood.seconds)
                throw flood
            } catch (failure: Exception) {
                when (classifyTelegramFailure(failure.message)) {
                    TelegramFailureKind.FLOOD -> {
                        val seconds = telegramFloodWaitSeconds(failure.message) ?: throw failure
                        noteFloodWait(seconds)
                        if (telegramFloodRetryDelayMs(seconds) == null || floodRetries++ >= TELEGRAM_FLOOD_AUTO_RETRIES)
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

    private fun noteFloodWait(seconds: Int) {
        val until = android.os.SystemClock.elapsedRealtime() + seconds.coerceAtLeast(1) * 1_000L
        floodUntilMs = maxOf(floodUntilMs, until)
    }

    /** Waits out a short FLOOD_WAIT; a long one is reported at once with its remaining seconds. */
    private suspend fun awaitFloodWait() {
        val waitMs = floodUntilMs - android.os.SystemClock.elapsedRealtime()
        if (waitMs <= 0) return
        val seconds = ((waitMs + 999) / 1_000).toInt()
        if (seconds > TELEGRAM_FLOOD_AUTO_WAIT_SECONDS + 1) throw TelegramFloodWaitException(seconds)
        kotlinx.coroutines.delay(waitMs + 250)
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
                    connected = false
                }
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
            cacheGeneration++
            cache.deleteRecursively()
            cache.mkdirs()
            previewWrites = 0
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
                    cacheGeneration++
                    cache.deleteRecursively()
                    cache.mkdirs()
                    previewWrites = 0
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
                cacheGeneration++
                cache.deleteRecursively()
                cache.mkdirs()
                previewWrites = 0
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
        if (chats.isEmpty() && !chatsExhausted) {
            chats.addAll(native.getChats(h))
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
                    subscribed = !it.left)
            }, if (index + result.size < all.size || (!chatsExhausted && result.isNotEmpty()))
                cursor(index + result.size) else null)
        } }

    override suspend fun channel(chatId: Long): TelegramChannel? =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            // The dialog is often already in the catalog. One getChats fallback can locate
            // an older channel without paging through unrelated subscriptions.
            val found = chats.firstOrNull { it.id == chatId && it.isChannel }
                ?: native.getChats(h).firstOrNull { it.id == chatId && it.isChannel }
                ?: return@nativeCall null
            TelegramChannel(found.id, found.title,
                thumbnailUrl = preview(h, found.id, 0, true),
                subscribed = !found.left && found.id !in leftChannels)
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
                cacheGeneration++
                cache.deleteRecursively()
                cache.mkdirs()
                previewWrites = 0
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
        val bytes = if (peer) native.peekPeerInlineThumb(h, chat)
            else native.peekMessageInlineThumb(h, chat, id)
        if (bytes == null || bytes.isEmpty() || bytes.size > 512 * 1024) return null
        synchronized(diskChunkLock) {
            if (expectedAccount == null || accountId != expectedAccount ||
                cacheGeneration != expectedGeneration) return null
            val out = File(cache, if (peer) "channel-$chat.bin" else "thumb-$chat-$id.bin")
            if (!out.isFile || out.length() != bytes.size.toLong()) {
                out.writeBytes(bytes)
                // Avoid per-image directory scans while still bounding the native preview cache.
                if (++previewWrites % 16 == 1) trimPreviewCache()
            } else {
                out.setLastModified(System.currentTimeMillis())
            }
            return out.toURI().toString()
        }
    }

    /** Enforce both age and byte/count limits without touching media chunk/photo caches. */
    private fun trimPreviewCache() {
        val thumbnails = cache.listFiles().orEmpty()
            .filter { it.isFile && (it.name.startsWith("thumb-") || it.name.startsWith("channel-")) }
            .sortedBy { it.lastModified() }
        val expiry = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        var remainingBytes = thumbnails.sumOf { it.length() }
        var remainingCount = thumbnails.size
        for (item in thumbnails) {
            if (item.lastModified() >= expiry &&
                remainingCount <= 500 && remainingBytes <= 32L * 1024 * 1024) break
            val size = item.length()
            if (item.delete()) {
                remainingBytes -= size
                remainingCount--
            }
        }
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
        )
    }

    private fun recent(h: Long, token: String?, limit: Int): TelegramMediaPage {
        val cursorToken = token ?: ("recent:" + epoch + ":" + (++nextRecentFeed))
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
            ) { id, offset, size ->
                val history = historyPage(h, id, size, offset)
                val title = chats.firstOrNull { it.id == id }?.title ?: "Monogram"
                val items = history.mapNotNull { entry -> media(h, entry, title) }
                val next = history.lastOrNull()?.id?.takeIf { history.size == size }
                MonogramRecentTimeline.HistoryBatch(
                    items, next,
                    history.lastOrNull()?.date?.times(1000L) ?: Long.MIN_VALUE)
            }.also { recentFeeds[cursorToken] = it }
        } else {
            require(token.startsWith("recent:" + epoch + ":")) { "Stale Monogram recent cursor" }
            checkNotNull(recentFeeds[token]) { "Expired Monogram recent cursor" }
        }
        val page = timeline.nextPage(limit.coerceIn(1, 60))
        if (!page.hasMore) recentFeeds.remove(cursorToken)
        while (recentFeeds.size > 3) recentFeeds.remove(recentFeeds.keys.first())
        return TelegramMediaPage(page.media, if (page.hasMore) cursorToken else null)
    }

    // Other tabs keep their channel+history-offset cursor; recent uses a global timeline.
    override suspend fun browse(section: String, cursor: String?, limit: Int): TelegramMediaPage =
        if (section == "recent") browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            recent(h, cursor, limit)
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
                // YouTube posts are sparse in a channel's history: Telegram's link filter
                // returns only posts with links, so far fewer pages are read per card.
                val history = when (section) {
                    "youtube" -> native.searchMessagesPage(h, id, "", "url", offset, 0, fetchLimit).messages
                    // Telegram's photo filter is exact; videos/audio keep the history scan so
                    // .mkv/.mp3 files sent as plain documents are still found.
                    "photos" -> native.searchMessagesPage(h, id, "", "photos", offset, 0, fetchLimit).messages
                    else -> historyPage(h, id, fetchLimit, offset)
                }
                val wanted: (TelegramMediaKind) -> Boolean = when (section) {
                    "videos" -> { kind -> kind == TelegramMediaKind.VIDEO }
                    "audio" -> { kind -> kind == TelegramMediaKind.AUDIO }
                    "photos" -> { kind -> kind == TelegramMediaKind.PHOTO }
                    else -> { _ -> true }
                }
                val mapped = history.mapNotNull { media(h, it, title, wanted) }
                    .flatMap { item -> if (section == "youtube") item.youtubePosts() else listOf(item) }
                    .filter {
                        when (section) {
                            "videos" -> it.kind == TelegramMediaKind.VIDEO
                            "youtube" -> it.kind == TelegramMediaKind.YOUTUBE
                            "audio" -> it.kind == TelegramMediaKind.AUDIO
                            "photos" -> it.kind == TelegramMediaKind.PHOTO
                            else -> true
                        }
                    }
                items.addAll(mapped)
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
        // No paging here: browse owns the mutable dialog index under browseMutex. A player or
        // detail lookup only reads the copy-on-write snapshot, or one getChats fallback.
        val title = (chats.firstOrNull { it.id == chatId }
            ?: native.getChats(h).firstOrNull { it.id == chatId })?.title ?: "Monogram"
        native.getMessage(h, chatId, messageId)?.let { media(h, it, title) }
    }

    override suspend fun photoFile(chatId: Long, messageId: Int): String? = nativeCall { h ->
        requireAuthorized(h)
        check(chatId !in leftChannels) { app.getString(R.string.monogram_error_channel_left) }
        val dir = File(cache, "photos").apply { mkdirs() }.canonicalFile
        val output = File(dir, "$chatId-$messageId.media")
        if (output.isFile && output.length() > 0L) return@nativeCall output.toURI().toString()
        val actual = File(native.downloadMessageDisplay(h, chatId, messageId, output.path))
            .canonicalFile
        require(actual.parentFile == dir && actual.length() > 0L &&
            chatId !in leftChannels) {
            "Monogram full-resolution photo unavailable"
        }
        actual.toURI().toString()
    }

    /** Telegram's real thumbnail (MediaDownloadKind::Thumb), cached with the inline previews. */
    override suspend fun thumbnail(chatId: Long, messageId: Int): String? = nativeCall { h ->
        requireAuthorized(h)
        if (chatId in leftChannels) return@nativeCall null
        val expectedGeneration = cacheGeneration
        val expectedAccount = accountId
        val dir = File(cache, "thumbs-hd").apply { mkdirs() }.canonicalFile
        val output = File(dir, "$chatId-$messageId.img")
        if (output.isFile && output.length() > 0L) {
            output.setLastModified(System.currentTimeMillis())
            return@nativeCall output.toURI().toString()
        }
        val returned = File(native.downloadMessageThumb(h, chatId, messageId, output.path)).canonicalFile
        // The native layer may keep the file in its own cache or add an extension: copy it into
        // our bounded cache instead of discarding a valid thumbnail.
        val actual = when {
            returned.parentFile == dir -> returned
            returned.isFile && returned.length() in 1L..(2L * 1024 * 1024) ->
                runCatching { returned.copyTo(output, overwrite = true) }.getOrNull() ?: return@nativeCall null
            else -> return@nativeCall null
        }
        if (actual.length() !in 1L..(2L * 1024 * 1024)) {
            actual.delete()
            return@nativeCall null
        }
        synchronized(diskChunkLock) {
            // A logout or account switch meanwhile: never keep the old account's image.
            if (expectedAccount == null || accountId != expectedAccount || cacheGeneration != expectedGeneration) {
                actual.delete()
                return@nativeCall null
            }
            if (++previewWrites % 16 == 1) trimThumbnailCache(dir)
        }
        actual.toURI().toString()
    }

    /** Same bounds as the inline previews: 7 days, 500 files, 64 MiB. */
    private fun trimThumbnailCache(dir: File) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        val expiry = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (item in files) {
            if (item.lastModified() >= expiry && count <= 500 && bytes <= 64L * 1024 * 1024) break
            val size = item.length()
            if (item.delete()) { bytes -= size; count-- }
        }
    }

    override suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray =
        nativeCall { h ->
            requireAuthorized(h)
            check(chatId !in leftChannels) { app.getString(R.string.monogram_error_channel_left) }
            require(offset >= 0L && offset % TELEGRAM_MEDIA_PART_BYTES == 0L)
            val dir = File(cache, "chunks").apply { mkdirs() }.canonicalFile
            val cacheFile = File(dir, "$chatId-$messageId-$offset.bin")
            val cached = synchronized(diskChunkLock) {
                if (cacheFile.isFile && cacheFile.length() in 1..TELEGRAM_MEDIA_PART_BYTES.toLong()) {
                    cacheFile.setLastModified(System.currentTimeMillis())
                    cacheFile.readBytes()
                } else null
            }
            if (cached != null) return@nativeCall cached
            val currentIdentity = accountId
            val temp = File.createTempFile("part-", ".bin", dir)
            var actual: File? = null
            try {
                actual = File(native.downloadMessageMediaChunk(
                    h, chatId, messageId, temp.absolutePath, offset)).canonicalFile
                require(actual.parentFile == dir) { "Monogram chunk escaped private cache" }
                require(actual.length() in 1..TELEGRAM_MEDIA_PART_BYTES.toLong()) {
                    "Invalid Monogram chunk size"
                }
                val payload = actual.readBytes()
                // An account switch/log-out invalidates in-flight native file results.
                check(accountId == currentIdentity && native.isAuthorized(h) &&
                    chatId !in leftChannels) {
                    app.getString(R.string.monogram_error_account_changed)
                }
                synchronized(diskChunkLock) {
                    if (accountId == currentIdentity && chatId !in leftChannels) {
                        if (!cacheFile.isFile) runCatching {
                            actual.copyTo(cacheFile, overwrite = false)
                        }
                        trimChunkCache(dir)
                    }
                }
                payload
            } finally {
                temp.delete()
                actual?.takeIf { it.parentFile == dir }?.delete()
            }
        }

    private fun trimChunkCache(dir: File) {
        // part-*.bin are temp files of downloads still in flight; only completed chunks count.
        val files = dir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".bin") && !it.name.startsWith("part-")
        }
            ?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (item in files) {
            if (bytes <= 64L * 1024L * 1024L) break
            bytes -= item.length()
            item.delete()
        }
    }

    private companion object {
        /** Pause between per-channel search requests (Telegram FLOOD_WAIT). */
        const val CATALOG_ROUND_PAUSE_MS = 300L
        /** Channels added to the recent timeline per catalog step. */
        const val RECENT_CATALOG_STEP = 20
        /** Update stream drain interval; draining is local, it sends nothing to Telegram. */
        const val UPDATES_POLL_MS = 5_000L
        /** Shared channel history pages: long enough for one browse session across categories. */
        const val HISTORY_PAGE_TTL_MS = 120_000L
        const val HISTORY_SHARED_PAGE = 60
        const val HISTORY_PAGE_LIMIT = 256
        const val SEARCH_GLOBAL_LIMIT = 50
        const val SEARCH_SUPPLEMENT_CHANNELS = 3
        const val SEARCH_CHANNEL_LIMIT = 20
    }
}

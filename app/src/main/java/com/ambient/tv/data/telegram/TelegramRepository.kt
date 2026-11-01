package com.ambient.tv.data.telegram

import android.content.Context
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.files.MediaFileServer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current native sign-in stage. Phone codes/passwords are NEVER persisted or logged. */
enum class MonogramLoginStep { UNAVAILABLE, READY, QR, CODE, PASSWORD, AUTHORIZED, ERROR }
data class MonogramLoginState(
    val step: MonogramLoginStep = MonogramLoginStep.UNAVAILABLE,
    val qrUrl: String? = null,
    val account: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
)
/** Implemented by the optional upstream Monogram source module, not a synthetic login. */
interface MonogramAuthClientPort {
    suspend fun restoreSession(): MonogramLoginState
    suspend fun beginQrLogin(): MonogramLoginState
    suspend fun pollQrLogin(): MonogramLoginState
    suspend fun sendPhoneCode(phone: String): MonogramLoginState
    suspend fun resendPhoneCode(): MonogramLoginState
    suspend fun submitPhoneCode(code: String): MonogramLoginState
    suspend fun submitTwoFactorPassword(password: String): MonogramLoginState
    suspend fun logout()
    /** Destroys the unauthenticated native client after a credentials change. */
    suspend fun resetForCredentials() = Unit
    /** Forgets this device's session and caches even when the server logout failed. */
    suspend fun forgetLocalSession() = Unit
}

/** Results are cursor-based; tokens never escape the authenticated account. */
data class TelegramMediaPage(val items: List<TelegramMedia>, val nextCursor: String? = null)
data class TelegramChannelPage(val items: List<TelegramChannel>, val nextCursor: String? = null)

/** A narrow bridge boundary: Monogram/mono-rs and its GPL sources are not bundled into core APKs.
 * An optional, separately licensed native module may install its authorized client here. */
interface TelegramClientPort {
    val accountName: String
    /** Stable native account identity; no cache is shared across different logins. */
    val cacheIdentity: String? get() = null
    /** Invalidates native catalog memory, not encrypted disk fallback. */
    suspend fun refreshCatalog() = Unit
    suspend fun channels(cursor: String? = null, limit: Int = 60): TelegramChannelPage
    /** Read the channel for a selected post without materializing the whole subscription feed. */
    suspend fun channel(chatId: Long): TelegramChannel? = null
    /** Joining is a real MTProto action; unsupported leave must not be simulated locally. */
    suspend fun joinChannel(chatId: Long): Boolean = false
    /** Available in the pinned native opt-in build with the leave-channel RPC extension. */
    suspend fun leaveChannel(chatId: Long): Boolean = false
    suspend fun browse(section: String, cursor: String? = null, limit: Int = 60): TelegramMediaPage
    suspend fun media(chatId: Long, messageId: Int): TelegramMedia?
    /** The original photo is fetched only when the viewer opens, never while listing. */
    suspend fun photoFile(chatId: Long, messageId: Int): String? = null
    /** A byte range from a Telegram file; Monogram downloadMessageMediaChunk does the MTProto I/O. */
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray
    /**
     * Playable video/audio posts from subscribed channels matching [query]. One request per
     * submitted search; throws [TelegramFloodWaitException] when Telegram asks to wait.
     */
    suspend fun search(query: String, limit: Int = TELEGRAM_SEARCH_LIMIT): List<TelegramMedia> = emptyList()
}

@Singleton
class TelegramRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val diskCache: TelegramDiskCache,
    private val preferences: AppPreferences,
    private val mediaFileServer: MediaFileServer,
    private val tokenStore: TokenStore,
) {
    // Loading Rust/UniFFI is expensive; never initialize it on the UI thread
    // or on an ordinary no-Monogram build. Only enabled systems restore sessions.
    private val appContext = context.applicationContext
    private val backend: MonogramAuthClientPort? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!BuildConfig.MONOGRAM_NATIVE) null
        else runCatching {
            val factory = Class.forName("com.ambient.tv.data.telegram.MonogramNativeBackend")
            factory.getConstructor(Context::class.java, TokenStore::class.java)
                .newInstance(appContext, tokenStore) as MonogramAuthClientPort
        }.getOrNull()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLogin = MutableStateFlow(MonogramLoginState(
        if (BuildConfig.MONOGRAM_NATIVE) MonogramLoginStep.READY else MonogramLoginStep.UNAVAILABLE))
    val login: StateFlow<MonogramLoginState> = mutableLogin.asStateFlow()
    val nativeAvailable: Boolean get() = BuildConfig.MONOGRAM_NATIVE
    val configuredApiId: String get() = tokenStore.monogramApiId
    val configuredApiHash: String get() = tokenStore.monogramApiHash
    val apiCredentialsReady: Boolean get() = tokenStore.monogramCredentials() != null

    /** Settings save, never an implicit edit while QR/OTP/2FA or playback is active. */
    suspend fun configureApiCredentials(idText: String, hashText: String) =
        sessionRestoreMutex.withLock {
            val id = idText.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException(appContext.getString(R.string.monogram_error_api_id))
            val hash = hashText.trim().lowercase(java.util.Locale.ROOT)
            require(hash.matches(Regex("[a-f0-9]{32}"))) { appContext.getString(R.string.monogram_error_api_hash) }
            check(!available && !mutableLogin.value.busy &&
                mutableLogin.value.step in setOf(MonogramLoginStep.READY, MonogramLoginStep.UNAVAILABLE)) {
                appContext.getString(R.string.monogram_error_signout_first)
            }
            if (id.toString() == tokenStore.monogramApiId && hash == tokenStore.monogramApiHash) {
                return@withLock
            }
            if (nativeAvailable) obtainBackend()?.resetForCredentials()
            disconnect()
            tokenStore.setMonogramCredentials(id, hash)
            restoredSessionOnce = false
            mutableLogin.value = MonogramLoginState(
                if (nativeAvailable) MonogramLoginStep.READY else MonogramLoginStep.UNAVAILABLE)
        }

    private suspend fun obtainBackend(): MonogramAuthClientPort? =
        withContext(Dispatchers.IO) { backend }

    private val sessionRestoreMutex = Mutex()
    private var restoredSessionOnce = false

    /** One asynchronous local session probe; never reset a pending QR/2FA login. */
    suspend fun restoreIfNeeded() = sessionRestoreMutex.withLock {
        if (!nativeAvailable || !apiCredentialsReady || available || restoredSessionOnce ||
            mutableLogin.value.step != MonogramLoginStep.READY ||
            mutableLogin.value.busy) return@withLock
        restoredSessionOnce = true
        val target = obtainBackend()
        if (target == null) {
            mutableLogin.value = MonogramLoginState(MonogramLoginStep.UNAVAILABLE)
            return@withLock
        }
        try {
            applyLogin(target.restoreSession())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            restoredSessionOnce = false
            throw cancelled
        } catch (error: Exception) {
            // Keep the sign-in controls reachable if the session is invalid.
            mutableLogin.value = MonogramLoginState(MonogramLoginStep.READY,
                error = error.message ?: appContext.getString(R.string.monogram_error_session_restore))
        }
    }
    @Volatile private var client: TelegramClientPort? = null
    private val playbackUrls = LinkedHashMap<String, String>()
    private val streamEpoch = java.util.concurrent.atomic.AtomicLong()

    private fun applyLogin(state: MonogramLoginState) {
        mutableLogin.value = state.copy(busy = false)
        val currentBackend = backend
        if (state.step == MonogramLoginStep.AUTHORIZED && currentBackend is TelegramClientPort) {
            installAuthorized(currentBackend)
        } else if (state.step != MonogramLoginStep.AUTHORIZED) {
            disconnect()
        }
    }

    /** One sign-in/sign-out step at a time; a second tap while busy is ignored atomically. */
    private val loginMutex = Mutex()

    private suspend fun transition(block: suspend MonogramAuthClientPort.() -> MonogramLoginState) {
        if (!apiCredentialsReady) {
            mutableLogin.value = mutableLogin.value.copy(error = appContext.getString(R.string.monogram_api_required))
            return
        }
        val target = obtainBackend() ?: run {
            mutableLogin.value = MonogramLoginState(MonogramLoginStep.UNAVAILABLE)
            return
        }
        if (!loginMutex.tryLock()) return
        try {
            runTransition(target, block)
        } finally {
            loginMutex.unlock()
        }
    }

    private suspend fun runTransition(
        target: MonogramAuthClientPort,
        block: suspend MonogramAuthClientPort.() -> MonogramLoginState,
    ) {
        val previous = mutableLogin.value
        mutableLogin.value = previous.copy(busy = true, error = null)
        try {
            applyLogin(target.block())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            mutableLogin.value = previous.copy(busy = false)
            throw cancelled
        } catch (error: Exception) {
            // Wrong OTP / two-factor password MUST keep the same input step.
            // Disconnecting on an auth error could also invalidate an existing session.
            mutableLogin.value = previous.copy(
                busy = false, error = error.message ?: appContext.getString(R.string.monogram_error_request))
        }
    }
    suspend fun requestQr() = transition { beginQrLogin() }
    suspend fun pollQr() = transition { pollQrLogin() }
    suspend fun requestPhone(phone: String) = transition { sendPhoneCode(phone) }
    suspend fun resendCode() = transition { resendPhoneCode() }
    suspend fun confirmCode(code: String) = transition { submitPhoneCode(code) }
    suspend fun confirmPassword(password: String) = transition { submitTwoFactorPassword(password) }
    suspend fun signOut() {
        val target = obtainBackend() ?: return
        if (!loginMutex.tryLock()) return
        try {
            val before = mutableLogin.value
            mutableLogin.value = before.copy(busy = true, error = null)
            val identity = client?.cacheIdentity
            try {
                target.logout()
                // Disconnect FIRST: in-flight browse calls must not repopulate cache
                // after the explicit logout cleanup finishes.
                applyLogin(MonogramLoginState(MonogramLoginStep.READY))
                identity?.let { diskCache.clearAccount(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                mutableLogin.value = before.copy(busy = false)
                throw cancelled
            } catch (_: Exception) {
                // Telegram could not confirm the logout (often offline). This device still
                // forgets the account: no client, no session file and no private caches remain.
                disconnect()
                runCatching { target.forgetLocalSession() }
                identity?.let { runCatching { diskCache.clearAccount(it) } }
                restoredSessionOnce = false
                mutableLogin.value = MonogramLoginState(MonogramLoginStep.READY,
                    error = appContext.getString(R.string.monogram_logout_local_only))
            }
        } finally {
            loginMutex.unlock()
        }
    }
    private val mutableCatalogRevision = MutableStateFlow(0L)
    val catalogRevision: StateFlow<Long> = mutableCatalogRevision.asStateFlow()
    /** A channel exit revokes local content immediately, before disk cleanup completes. */
    private val blockedChannels = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Reject results of requests that finished after logout, account switch or channel exit. */
    private fun sameAccount(source: TelegramClientPort, identity: String?, generation: Long): Boolean =
        client === source && source.cacheIdentity == identity &&
            streamEpoch.get() == generation

    private val mutableAuthorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = mutableAuthorized.asStateFlow()
    val available: Boolean get() = client != null
    val accountName: String? get() = client?.accountName

    /** Install only an already authenticated Monogram client, never a partially signed-in one. */
    fun installAuthorized(port: TelegramClientPort) {
        if (client !== port) {
            streamEpoch.incrementAndGet()
            revokePlaybackUrls()
            blockedChannels.clear()
        }
        client = port
        mutableAuthorized.value = true
    }

    fun disconnect() {
        streamEpoch.incrementAndGet()
        revokePlaybackUrls()
        blockedChannels.clear()
        client = null
        clearResumePositions()
        mutableAuthorized.value = false
    }

    /**
     * Last playback position per post for renderer switches and reopening after the 30 s
     * background stop. Kept in memory only (never on disk or in backups) and dropped on
     * logout, account switch and channel exit, because post ids are account-private.
     */
    private val resumePositions = LinkedHashMap<String, Long>()

    fun resumePositionMs(mediaKey: String): Long =
        synchronized(resumePositions) { resumePositions[mediaKey] ?: 0L }

    fun rememberResumePosition(mediaKey: String, positionMs: Long, durationMs: Long) {
        synchronized(resumePositions) {
            resumePositions.remove(mediaKey)
            // Finished (or nearly finished) items start from the beginning next time.
            if (!monogramResumeWorthKeeping(positionMs, durationMs)) return
            resumePositions[mediaKey] = positionMs
            while (resumePositions.size > RESUME_MAX_ENTRIES) resumePositions.remove(resumePositions.keys.first())
        }
    }

    /** Start used by the player session that is (or was last) acquired for a post. */
    private var sessionStart: Pair<String, Long>? = null

    fun noteSessionStart(mediaKey: String, positionMs: Long) {
        synchronized(resumePositions) { sessionStart = mediaKey to positionMs }
    }

    fun sessionStartMs(mediaKey: String): Long = synchronized(resumePositions) {
        sessionStart?.takeIf { it.first == mediaKey }?.second ?: 0L
    }

    private fun clearResumePositions() {
        synchronized(resumePositions) {
            resumePositions.clear()
            sessionStart = null
        }
    }

    /**
     * Native VLC/MPV cannot read a Media3 DataSource. Use the same bounded, capability-token
     * loopback transport as SMB/NAS. The URL contains no account identifier or credentials,
     * while every byte is fetched through the currently authorized Monogram session.
     */
    suspend fun playerHttpUrl(media: TelegramMedia): String = withContext(Dispatchers.IO) {
        require(media.playableOnTelegram && media.kind != TelegramMediaKind.PHOTO &&
            media.fileSize > 0L) { appContext.getString(R.string.monogram_error_not_streamable) }
        val authenticated = checkNotNull(client) { appContext.getString(R.string.monogram_error_not_authorized) }
        val generation = streamEpoch.get()
        val key = "${authenticated.cacheIdentity}:${media.mediaKey}"
        synchronized(playbackUrls) { playbackUrls[key] }?.let { return@withContext it }
        val url = mediaFileServer.register(
            sourceId = MONOGRAM_LOCAL_MEDIA_SOURCE,
            mime = "application/octet-stream",
            fileName = "monogram-media.bin",
        ) { TelegramSeekableMedia(
            media = media,
            partLoader = { offset -> kotlinx.coroutines.runBlocking {
                mediaPart(media.chatId, media.messageId, offset)
            } },
            authorized = { client === authenticated && streamEpoch.get() == generation },
        ) }
        // An account may be logged out while the URL is being registered on a worker.
        if (client !== authenticated || streamEpoch.get() != generation) {
            mediaFileServer.revoke(MONOGRAM_LOCAL_MEDIA_SOURCE)
            error(appContext.getString(R.string.monogram_error_account_changed))
        }
        synchronized(playbackUrls) {
            playbackUrls[key] = url
            // MediaFileServer itself bounds its capability registry. Keep our URL
            // references smaller, so no evicted token is reused by a future renderer.
            while (playbackUrls.size > 24) playbackUrls.remove(playbackUrls.keys.first())
        }
        url
    }

    private fun revokePlaybackUrls() {
        synchronized(playbackUrls) { playbackUrls.clear() }
        mediaFileServer.revoke(MONOGRAM_LOCAL_MEDIA_SOURCE)
    }

    private companion object {
        const val MONOGRAM_LOCAL_MEDIA_SOURCE = "monogram-mtproto"
        const val RESUME_MAX_ENTRIES = 200
    }

    /** Cache is only read after native account authorization confirms the same identity. */
    suspend fun refreshCatalog() { client?.refreshCatalog() }
    suspend fun cachedChannels(): List<TelegramChannel> {
        val source = client ?: return emptyList()
        val generation = streamEpoch.get()
        val identity = source.cacheIdentity ?: return emptyList()
        val cached = diskCache.channels(identity)
        return if (sameAccount(source, identity, generation))
            cached.filterNot { it.chatId in blockedChannels } else emptyList()
    }

    suspend fun channel(chatId: Long): TelegramChannel? {
        val account = client ?: return null
        if (chatId in blockedChannels) return null
        val generation = streamEpoch.get()
        val identity = account.cacheIdentity
        val cached = identity?.let { diskCache.channels(it) }
            ?.firstOrNull { it.chatId == chatId }
        val found = try {
            account.channel(chatId) ?: cached
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { cached }
        return found.takeIf { sameAccount(account, identity, generation) &&
            chatId !in blockedChannels }
    }

    suspend fun joinChannel(chatId: Long): Boolean {
        val account = client ?: return false
        val joined = account.joinChannel(chatId)
        if (joined) {
            blockedChannels.remove(chatId)
            account.refreshCatalog()
            mutableCatalogRevision.value++
        }
        return joined
    }

    suspend fun leaveChannel(chatId: Long): Boolean {
        val account = client ?: return false
        val left = account.leaveChannel(chatId)
        if (left) {
            blockedChannels.add(chatId)
            streamEpoch.incrementAndGet()
            revokePlaybackUrls()
            clearResumePositions()
            account.cacheIdentity?.let { diskCache.clearAccount(it) }
            mutableCatalogRevision.value++
        }
        return left
    }
    suspend fun cachedBrowse(section: String): List<TelegramMedia> {
        val source = client ?: return emptyList()
        val generation = streamEpoch.get()
        val identity = source.cacheIdentity ?: return emptyList()
        val cached = diskCache.media(identity, section)
        return if (sameAccount(source, identity, generation))
            cached.filterNot { it.chatId in blockedChannels } else emptyList()
    }

    suspend fun channelsPage(cursor: String? = null, limit: Int = 60): TelegramChannelPage {
        val selected = client ?: return TelegramChannelPage(emptyList())
        val generation = streamEpoch.get()
        val identity = selected.cacheIdentity
        val page = selected.channels(cursor, limit)
            .let { result -> result.copy(items = result.items.filterNot { it.chatId in blockedChannels }) }
        if (!sameAccount(selected, identity, generation)) return TelegramChannelPage(emptyList())
        selected.cacheIdentity?.let { id ->
            if (page.items.isNotEmpty()) diskCache.saveChannels(id, page.items, cursor == null)
        }
        return page
    }
    suspend fun browsePage(section: String, cursor: String? = null, limit: Int = 60): TelegramMediaPage {
        val selected = client ?: return TelegramMediaPage(emptyList())
        val generation = streamEpoch.get()
        val identity = selected.cacheIdentity
        val page = selected.browse(section, cursor, limit)
            .let { result -> result.copy(items = result.items.filterNot { it.chatId in blockedChannels }) }
        if (!sameAccount(selected, identity, generation)) return TelegramMediaPage(emptyList())
        selected.cacheIdentity?.let { id ->
            if (page.items.isNotEmpty()) diskCache.saveMedia(id, section, page.items, cursor == null)
        }
        return page
    }
    suspend fun channels(): List<TelegramChannel> = try {
        channelsPage().items.ifEmpty { cachedChannels() }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) { cachedChannels() }

    suspend fun browse(section: String, limit: Int = 60): List<TelegramMedia> = try {
        browsePage(section, limit = limit).items.ifEmpty { cachedBrowse(section).take(limit) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) { cachedBrowse(section).take(limit) }

    /** Hydrate the full post text on a disconnected launch only for the verified active account. */
    suspend fun media(chatId: Long, messageId: Int): TelegramMedia? {
        val selected = client ?: return null
        if (chatId in blockedChannels) return null
        val generation = streamEpoch.get()
        val identity = selected.cacheIdentity
        val live = try {
            selected.media(chatId, messageId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        if (!sameAccount(selected, identity, generation) || chatId in blockedChannels) return null
        if (live != null) return live
        if (identity == null) return null
        val sections = listOf("recent", "videos", "youtube", "audio", "photos", "channel:$chatId")
        for (section in sections) {
            val cached = diskCache.media(identity, section)
                .firstOrNull { it.chatId == chatId && it.messageId == messageId }
            if (cached != null) return cached.takeIf {
                sameAccount(selected, identity, generation) && chatId !in blockedChannels
            }
        }
        return null
    }
    suspend fun photoFile(chatId: Long, messageId: Int): String? {
        val selected = client ?: return null
        check(chatId !in blockedChannels) { appContext.getString(R.string.monogram_error_channel_left) }
        val generation = streamEpoch.get()
        val identity = selected.cacheIdentity
        val result = selected.photoFile(chatId, messageId)
        return result.takeIf { sameAccount(selected, identity, generation) &&
            chatId !in blockedChannels }
    }
    /** Monotonic end of a FLOOD_WAIT: until then, search answers without contacting Telegram. */
    @Volatile private var searchBlockedUntilMs = 0L

    private fun noteSearchFloodWait(seconds: Int) {
        val until = System.nanoTime() / 1_000_000L + seconds.coerceAtLeast(1) * 1_000L
        if (until > searchBlockedUntilMs) searchBlockedUntilMs = until
    }

    /**
     * Global search for the app's search screen. Empty without the native build or a signed-in
     * account. The native call keeps running in the background after [TELEGRAM_SEARCH_TIMEOUT_MS],
     * but the screen no longer waits for it.
     */
    suspend fun search(query: String, limit: Int = TELEGRAM_SEARCH_LIMIT): List<TelegramMedia> {
        if (!BuildConfig.MONOGRAM_NATIVE) return emptyList()
        val selected = client ?: return emptyList()
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val waitMs = searchBlockedUntilMs - System.nanoTime() / 1_000_000L
        if (waitMs > 0) throw TelegramFloodWaitException(((waitMs + 999L) / 1_000L).toInt())
        val generation = streamEpoch.get()
        val identity = selected.cacheIdentity
        val request = scope.async { selected.search(trimmed, limit) }
        request.invokeOnCompletion { failure ->
            (failure as? TelegramFloodWaitException)?.let { noteSearchFloodWait(it.seconds) }
        }
        val found = try {
            kotlinx.coroutines.withTimeoutOrNull(TELEGRAM_SEARCH_TIMEOUT_MS) { request.await() }
        } catch (flood: TelegramFloodWaitException) {
            noteSearchFloodWait(flood.seconds)
            throw flood
        } ?: throw IllegalStateException(appContext.getString(R.string.monogram_error_search_timeout))
        if (!sameAccount(selected, identity, generation)) return emptyList()
        return telegramSearchResults(found, subscribed = null, excluded = blockedChannels, limit = limit)
    }

    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray {
        val selected = checkNotNull(client) { appContext.getString(R.string.monogram_error_not_authorized) }
        check(chatId !in blockedChannels) { appContext.getString(R.string.monogram_error_channel_left) }
        val generation = streamEpoch.get()
        val identity = selected.cacheIdentity
        val bytes = selected.mediaPart(chatId, messageId, offset)
        check(sameAccount(selected, identity, generation) && chatId !in blockedChannels) {
            appContext.getString(R.string.monogram_error_account_changed)
        }
        return bytes
    }

    // Last in the class: the IO coroutine may reach disconnect()/installAuthorized() at once,
    // and those use properties that must already be initialized by the constructor.
    init {
        if (nativeAvailable && preferences.telegramEnabled) {
            scope.launch { restoreIfNeeded() }
        }
    }
}

/** Positions under 10 s or within the last 15 s are not worth resuming. */
internal fun monogramResumeWorthKeeping(positionMs: Long, durationMs: Long): Boolean =
    positionMs >= 10_000L && (durationMs <= 0L || positionMs < durationMs - 15_000L)

/** The search screen stops waiting for Telegram after this long. */
internal const val TELEGRAM_SEARCH_TIMEOUT_MS = 15_000L

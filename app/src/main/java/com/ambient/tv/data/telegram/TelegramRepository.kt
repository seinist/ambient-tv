package com.ambient.tv.data.telegram

import android.content.Context
import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    suspend fun browse(section: String, cursor: String? = null, limit: Int = 60): TelegramMediaPage
    suspend fun media(chatId: Long, messageId: Int): TelegramMedia?
    /** The original photo is fetched only when the viewer opens, never while listing. */
    suspend fun photoFile(chatId: Long, messageId: Int): String? = null
    /** A byte range from a Telegram file; Monogram downloadMessageMediaChunk does the MTProto I/O. */
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray
}

@Singleton
class TelegramRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val diskCache: TelegramDiskCache,
    private val preferences: AppPreferences,
) {
    // Loading Rust/UniFFI is expensive; never initialize it on the UI thread
    // or on an ordinary no-Monogram build. Only enabled systems restore sessions.
    private val appContext = context.applicationContext
    private val backend: MonogramAuthClientPort? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!BuildConfig.MONOGRAM_NATIVE) null
        else runCatching {
            val factory = Class.forName("com.ambient.tv.data.telegram.MonogramNativeBackend")
            factory.getConstructor(Context::class.java).newInstance(appContext) as MonogramAuthClientPort
        }.getOrNull()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLogin = MutableStateFlow(MonogramLoginState(
        if (BuildConfig.MONOGRAM_NATIVE) MonogramLoginStep.READY else MonogramLoginStep.UNAVAILABLE))
    val login: StateFlow<MonogramLoginState> = mutableLogin.asStateFlow()
    val nativeAvailable: Boolean get() = BuildConfig.MONOGRAM_NATIVE

    private suspend fun obtainBackend(): MonogramAuthClientPort? =
        withContext(Dispatchers.IO) { backend }

    /** Idempotent session restoration; also called when Monogram was enabled in Settings. */
    suspend fun restoreIfNeeded() {
        if (!nativeAvailable || available || mutableLogin.value.busy) return
        val target = obtainBackend()
        if (target == null) {
            mutableLogin.value = MonogramLoginState(MonogramLoginStep.UNAVAILABLE)
            return
        }
        try {
            applyLogin(target.restoreSession())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableLogin.value = MonogramLoginState(MonogramLoginStep.READY,
                error = error.message ?: "Monogram session restore failed")
        }
    }
    @Volatile private var client: TelegramClientPort? = null
    /** Exact launch row (not a guessed global "recent" queue). Switching pages never changes it. */
    private val queues = LinkedHashMap<String, List<String>>()
    private var activeQueue: List<String> = emptyList()

    @Synchronized
    fun registerPlaybackQueue(source: String, ids: List<String>) {
        val safe = ids.filter { telegramMediaIds(it) != null }.distinct().take(500)
        queues.remove(source)
        queues[source] = safe
        while (queues.size > 24) queues.remove(queues.keys.first())
    }

    @Synchronized
    fun activatePlaybackQueue(ids: List<String>, selected: String) {
        activeQueue = ids.filter { telegramMediaIds(it) != null }.distinct().take(500)
            .takeIf { selected in it } ?: listOf(selected)
    }

    @Synchronized
    fun activateHomePlaybackQueue(selected: String) {
        if (selected in activeQueue) return
        val found = queues.values.firstOrNull { selected in it }
        activeQueue = found ?: listOf(selected)
    }

    @Synchronized
    fun playbackCandidates(current: String, direction: Int): List<Pair<Long, Int>> {
        val i = activeQueue.indexOf(current)
        if (i < 0 || direction == 0) return emptyList()
        val next = if (direction > 0) activeQueue.drop(i + 1)
            else activeQueue.take(i).asReversed()
        return next.mapNotNull(::telegramMediaIds)
    }

    @Synchronized
    fun clearPlaybackQueues() {
        activeQueue = emptyList()
        queues.clear()
    }

    init {
        if (nativeAvailable && preferences.telegramEnabled) {
            scope.launch { restoreIfNeeded() }
        }
    }

    private fun applyLogin(state: MonogramLoginState) {
        mutableLogin.value = state.copy(busy = false)
        val currentBackend = backend
        if (state.step == MonogramLoginStep.AUTHORIZED && currentBackend is TelegramClientPort) {
            installAuthorized(currentBackend)
        } else if (state.step != MonogramLoginStep.AUTHORIZED) {
            disconnect()
        }
    }

    private suspend fun transition(block: suspend MonogramAuthClientPort.() -> MonogramLoginState) {
        val target = obtainBackend() ?: run {
            mutableLogin.value = MonogramLoginState(MonogramLoginStep.UNAVAILABLE)
            return
        }
        if (mutableLogin.value.busy) return
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
                busy = false, error = error.message ?: "Monogram request failed")
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
        val before = mutableLogin.value
        mutableLogin.value = before.copy(busy = true, error = null)
        try {
            val identity = client?.cacheIdentity
            target.logout()
            identity?.let { diskCache.clearAccount(it) }
            applyLogin(MonogramLoginState(MonogramLoginStep.READY))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            mutableLogin.value = before.copy(busy = false)
            throw cancelled
        } catch (error: Exception) {
            mutableLogin.value = before.copy(
                busy = false, error = error.message ?: "Monogram logout failed")
        }
    }
    private val mutableAuthorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = mutableAuthorized.asStateFlow()
    val available: Boolean get() = client != null
    val accountName: String? get() = client?.accountName

    /** Install only an already authenticated Monogram client, never a partially signed-in one. */
    fun installAuthorized(port: TelegramClientPort) {
        client = port
        mutableAuthorized.value = true
    }

    fun disconnect() {
        client = null
        clearPlaybackQueues()
        mutableAuthorized.value = false
    }

    /** Cache is only read after native account authorization confirms the same identity. */
    suspend fun refreshCatalog() { client?.refreshCatalog() }
    suspend fun cachedChannels(): List<TelegramChannel> =
        client?.cacheIdentity?.let { diskCache.channels(it) }.orEmpty()
    suspend fun cachedBrowse(section: String): List<TelegramMedia> =
        client?.cacheIdentity?.let { diskCache.media(it, section) }.orEmpty()

    suspend fun channelsPage(cursor: String? = null, limit: Int = 60): TelegramChannelPage {
        val selected = client ?: return TelegramChannelPage(emptyList())
        val page = selected.channels(cursor, limit)
        selected.cacheIdentity?.let { id ->
            if (page.items.isNotEmpty()) diskCache.saveChannels(id, page.items, cursor == null)
        }
        return page
    }
    suspend fun browsePage(section: String, cursor: String? = null, limit: Int = 60): TelegramMediaPage {
        val selected = client ?: return TelegramMediaPage(emptyList())
        val page = selected.browse(section, cursor, limit)
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

    suspend fun media(chatId: Long, messageId: Int): TelegramMedia? = client?.media(chatId, messageId)
    suspend fun photoFile(chatId: Long, messageId: Int): String? = client?.photoFile(chatId, messageId)
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray =
        checkNotNull(client) { "Telegram account is not authorized" }.mediaPart(chatId, messageId, offset)
}

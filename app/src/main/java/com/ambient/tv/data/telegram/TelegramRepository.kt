package com.ambient.tv.data.telegram

import android.content.Context
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
)
/** Implemented by the optional upstream Monogram source module, not a synthetic login. */
interface MonogramAuthClientPort {
    suspend fun restoreSession(): MonogramLoginState
    suspend fun beginQrLogin(): MonogramLoginState
    suspend fun pollQrLogin(): MonogramLoginState
    suspend fun sendPhoneCode(phone: String): MonogramLoginState
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
    suspend fun channels(cursor: String? = null, limit: Int = 60): TelegramChannelPage
    suspend fun browse(section: String, cursor: String? = null, limit: Int = 60): TelegramMediaPage
    suspend fun media(chatId: Long, messageId: Int): TelegramMedia?
    /** A byte range from a Telegram file; Monogram downloadMessageMediaChunk does the MTProto I/O. */
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray
}

@Singleton
class TelegramRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val diskCache: TelegramDiskCache,
) {
    // The class exists only in GPL-enabled builds. The normal CC0 APK never loads
    // a native Telegram client or impersonates a successful authorization.
    private val backend: MonogramAuthClientPort? = runCatching {
        val factory = Class.forName("com.ambient.tv.data.telegram.MonogramNativeBackend")
        factory.getConstructor(Context::class.java).newInstance(context) as MonogramAuthClientPort
    }.getOrNull()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLogin = MutableStateFlow(MonogramLoginState(
        if (backend == null) MonogramLoginStep.UNAVAILABLE else MonogramLoginStep.READY))
    val login: StateFlow<MonogramLoginState> = mutableLogin.asStateFlow()
    val nativeAvailable: Boolean get() = backend != null
    @Volatile private var client: TelegramClientPort? = null

    init {
        if (backend != null) scope.launch {
            applyLogin(runCatching { backend.restoreSession() }.getOrElse {
                MonogramLoginState(MonogramLoginStep.ERROR, error = it.message)
            })
        }
    }

    private fun applyLogin(state: MonogramLoginState) {
        mutableLogin.value = state
        if (state.step == MonogramLoginStep.AUTHORIZED && backend is TelegramClientPort) {
            installAuthorized(backend)
        } else if (state.step != MonogramLoginStep.AUTHORIZED) {
            disconnect()
        }
    }

    private suspend fun transition(block: suspend MonogramAuthClientPort.() -> MonogramLoginState) {
        val target = backend ?: return
        try {
            applyLogin(target.block())
        } catch (error: Exception) {
            applyLogin(MonogramLoginState(MonogramLoginStep.ERROR, error = error.message))
        }
    }
    suspend fun requestQr() = transition { beginQrLogin() }
    suspend fun pollQr() = transition { pollQrLogin() }
    suspend fun requestPhone(phone: String) = transition { sendPhoneCode(phone) }
    suspend fun confirmCode(code: String) = transition { submitPhoneCode(code) }
    suspend fun confirmPassword(password: String) = transition { submitTwoFactorPassword(password) }
    suspend fun signOut() {
        val target = backend ?: return
        try {
            val identity = client?.cacheIdentity
            target.logout()
            identity?.let { diskCache.clearAccount(it) }
            applyLogin(MonogramLoginState(MonogramLoginStep.READY))
        } catch (error: Exception) {
            mutableLogin.value = MonogramLoginState(MonogramLoginStep.ERROR, error = error.message)
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
        mutableAuthorized.value = false
    }

    /** Cache is only read after native account authorization confirms the same identity. */
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
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray =
        checkNotNull(client) { "Telegram account is not authorized" }.mediaPart(chatId, messageId, offset)
}

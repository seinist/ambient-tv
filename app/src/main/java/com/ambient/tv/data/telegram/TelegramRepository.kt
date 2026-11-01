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
    suspend fun channels(cursor: String? = null, limit: Int = 60): TelegramChannelPage
    suspend fun browse(section: String, cursor: String? = null, limit: Int = 60): TelegramMediaPage
    suspend fun media(chatId: Long, messageId: Int): TelegramMedia?
    /** A byte range from a Telegram file; Monogram downloadMessageMediaChunk does the MTProto I/O. */
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray
}

@Singleton
class TelegramRepository @Inject constructor(
    @ApplicationContext context: Context,
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
            target.logout()
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

    suspend fun channelsPage(cursor: String? = null, limit: Int = 60): TelegramChannelPage =
        client?.channels(cursor, limit) ?: TelegramChannelPage(emptyList())
    suspend fun browsePage(section: String, cursor: String? = null, limit: Int = 60): TelegramMediaPage =
        client?.browse(section, cursor, limit) ?: TelegramMediaPage(emptyList())
    suspend fun channels(): List<TelegramChannel> = channelsPage().items
    suspend fun browse(section: String, limit: Int = 60): List<TelegramMedia> =
        browsePage(section, limit = limit).items

    suspend fun media(chatId: Long, messageId: Int): TelegramMedia? = client?.media(chatId, messageId)
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray =
        checkNotNull(client) { "Telegram account is not authorized" }.mediaPart(chatId, messageId, offset)
}

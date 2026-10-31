package com.ambient.tv.data.telegram

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A narrow bridge boundary: Monogram/mono-rs and its GPL sources are not bundled into core APKs.
 * An optional, separately licensed native module may install its authorized client here. */
interface TelegramClientPort {
    val accountName: String
    suspend fun channels(): List<TelegramChannel>
    suspend fun browse(section: String, limit: Int = 60): List<TelegramMedia>
    suspend fun media(chatId: Long, messageId: Int): TelegramMedia?
    /** A byte range from a Telegram file; Monogram downloadMessageMediaChunk does the MTProto I/O. */
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray
}

@Singleton
class TelegramRepository @Inject constructor() {
    @Volatile private var client: TelegramClientPort? = null
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

    suspend fun channels(): List<TelegramChannel> = client?.channels().orEmpty()
    suspend fun browse(section: String, limit: Int = 60): List<TelegramMedia> =
        client?.browse(section, limit).orEmpty()

    suspend fun media(chatId: Long, messageId: Int): TelegramMedia? = client?.media(chatId, messageId)
    suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray =
        checkNotNull(client) { "Telegram account is not authorized" }.mediaPart(chatId, messageId, offset)
}

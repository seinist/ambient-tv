package com.ambient.tv.data.telegram

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

package com.ambient.tv.data.telegram

import android.content.Context
import android.util.Base64
import com.ambient.tv.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.mtproto.MtprotoNative
import org.monogram.mtproto.MtprotoNativeLoader
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.MessageDto
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.QrLoginDto

/**
 * The ONLY application-side dependency on Monogram, compiled exclusively with
 * MONOGRAM_NATIVE=1. Uses upstream :native:mtproto, never its UI/feature modules.
 */
class MonogramNativeBackend(context: Context) : MonogramAuthClientPort, TelegramClientPort {
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
    private val browseMutex = Mutex()
    private val accountIdFile = File(session.parentFile, "account.id")
    private var accountId: Long? = accountIdFile.takeIf { it.isFile }?.let {
        runCatching { it.readText().trim().toLongOrNull() }.getOrNull()
    }
    private var handle = 0L
    private var connected = false
    private var epoch = System.nanoTime()
    private var chats = mutableListOf<ChatDto>()
    private var chatsExhausted = false
    private var phone: String? = null
    private var codeHash: String? = null
    private var qrExpiresAt = 0L
    private var label = "Monogram"
    private var loginState = MonogramLoginState(MonogramLoginStep.READY)
    override val accountName get() = label
    override val cacheIdentity: String? get() = accountId?.toString()

    /** Serializes native operations and keeps network work off the UI thread. */
    private suspend fun <T> nativeCall(connect: Boolean = true, action: (Long) -> T): T =
        withContext(Dispatchers.IO) {
            val current = mutex.withLock {
                if (handle == 0L) {
                    handle = native.createClient(BuildConfig.MONOGRAM_API_ID,
                        BuildConfig.MONOGRAM_API_HASH, session.absolutePath)
                    native.setDownloadChunkKib(512)
                    connected = false
                }
                if (connect && !connected) {
                    native.connect(handle)
                    connected = true
                }
                handle
            }
            action(current)
        }

    private fun authorized(id: Long? = null): MonogramLoginState {
        if (id != null) {
            if (accountId != id) {
                cache.deleteRecursively()
                cache.mkdirs()
            }
            accountId = id
            accountIdFile.writeText(id.toString())
        }
        accountId?.let { label = "Monogram #$it" }
        chats.clear()
        chatsExhausted = false
        phone = null
        codeHash = null
        epoch++
        loginState = MonogramLoginState(MonogramLoginStep.AUTHORIZED, account = label)
        return loginState
    }

    override suspend fun restoreSession() = nativeCall {
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

    override suspend fun sendPhoneCode(number: String): MonogramLoginState =
        nativeCall(connect = false) { h ->
            require(number.startsWith("+") && number.length in 7..19) { "Invalid phone number" }
            val sent = native.sendAuthCode(h, number)
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
        nativeCall { h -> native.logout(h) }
        mutex.withLock {
            chats.clear()
            chatsExhausted = false
            epoch++
            label = "Monogram"
            phone = null
            codeHash = null
            loginState = MonogramLoginState(MonogramLoginStep.READY)
            cache.deleteRecursively()
            cache.mkdirs()
            accountIdFile.delete()
            accountId = null
        }
    }

    private fun requireAuthorized(h: Long) {
        check(native.isAuthorized(h)) { "Monogram account is not authorized" }
    }

    private fun knownChannels(h: Long, count: Int): List<ChatDto> {
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
        return chats.filter { it.isChannel && !it.left }
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
                TelegramChannel(it.id, it.title, thumbnailUrl = preview(h, it.id, 0, true))
            }, if (index + result.size < all.size || (!chatsExhausted && result.isNotEmpty()))
                cursor(index + result.size) else null)
        } }

    /** Inline previews only: do not download the full channel's posters on first load. */
    private fun preview(h: Long, chat: Long, id: Int, peer: Boolean = false): String? {
        val bytes = if (peer) native.peekPeerInlineThumb(h, chat)
            else native.peekMessageInlineThumb(h, chat, id)
        if (bytes.isNullOrEmpty() || bytes.size > 512 * 1024) return null
        val out = File(cache, if (peer) "channel-$chat.bin" else "thumb-$chat-$id.bin")
        if (!out.isFile || out.length() != bytes.size.toLong()) out.writeBytes(bytes)
        return out.toURI().toString()
    }

    private fun media(h: Long, m: MessageDto, channel: String): TelegramMedia? {
        val youtube = telegramYouTubeIds(m.text.orEmpty()).firstOrNull()
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
            protectedContent = m.noforwards,
        )
    }

    // Cursor tracks both channel index and history offset; no large message lists are kept.
    override suspend fun browse(section: String, cursor: String?, limit: Int): TelegramMediaPage =
        browseMutex.withLock { nativeCall { h ->
            requireAuthorized(h)
            val parts = cursor?.split(':')
            require(parts == null || (parts.size == 3 && parts[0].toLongOrNull() == epoch))
            var index = parts?.get(1)?.toIntOrNull() ?: 0
            var offset = parts?.get(2)?.toIntOrNull() ?: 0
            require(index >= 0 && offset >= 0)
            val channelId = section.takeIf { it.startsWith("channel:") }
                ?.substringAfter(':')?.toLongOrNull()
            val available = if (channelId != null) {
                listOf(channelId to (knownChannels(h, 1).firstOrNull { it.id == channelId }?.title ?: "Monogram"))
            } else knownChannels(h, index + 2).map { it.id to it.title }
            val items = ArrayList<TelegramMedia>()
            var steps = 0
            while (index < available.size && items.size < limit.coerceIn(1, 60) && steps++ < 12) {
                val (id, title) = available[index]
                val fetchLimit = minOf(60, limit.coerceIn(1, 60) - items.size)
                val history = native.getHistoryPage(h, id, fetchLimit, offset, 0, 0)
                val mapped = history.mapNotNull { media(h, it, title) }.filter {
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

    override suspend fun media(chatId: Long, messageId: Int): TelegramMedia? = nativeCall { h ->
        requireAuthorized(h)
        val title = knownChannels(h, 1).firstOrNull { it.id == chatId }?.title ?: "Monogram"
        native.getMessage(h, chatId, messageId)?.let { media(h, it, title) }
    }

    override suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long): ByteArray =
        nativeCall { h ->
            requireAuthorized(h)
            require(offset >= 0L && offset % TELEGRAM_MEDIA_PART_BYTES == 0L)
            val dir = File(cache, "chunks").apply { mkdirs() }.canonicalFile
            val cacheFile = File(dir, "$chatId-$messageId-$offset.bin")
            if (cacheFile.isFile && cacheFile.length() in 1..TELEGRAM_MEDIA_PART_BYTES.toLong()) {
                cacheFile.setLastModified(System.currentTimeMillis())
                return@nativeCall cacheFile.readBytes()
            }
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
                if (accountId == currentIdentity && native.isAuthorized(h)) {
                    // An account switch must never publish the previous account's bytes.
                    if (!cacheFile.isFile) actual.copyTo(cacheFile, overwrite = false)
                    trimChunkCache(dir)
                }
                payload
            } finally {
                temp.delete()
                actual?.takeIf { it.parentFile == dir }?.delete()
            }
        }

    private fun trimChunkCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (item in files) {
            if (bytes <= 64L * 1024L * 1024L) break
            bytes -= item.length()
            item.delete()
        }
    }
}

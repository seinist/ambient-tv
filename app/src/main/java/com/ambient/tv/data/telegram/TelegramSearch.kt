package com.ambient.tv.data.telegram

/** Telegram global search results shown in the app's search, per request. */
const val TELEGRAM_SEARCH_LIMIT = 24

/** Telegram asked to wait (FLOOD_WAIT_n); no further search is sent until it has passed. */
class TelegramFloodWaitException(val seconds: Int) : IllegalStateException("FLOOD_WAIT_$seconds")

private val FLOOD_WAIT = Regex("FLOOD(?:_PREMIUM)?_WAIT_(\\d+)")

/** Seconds from a Telegram RPC error such as "RPC 420: FLOOD_WAIT_17", null for other errors. */
fun telegramFloodWaitSeconds(message: String?): Int? =
    message?.let { FLOOD_WAIT.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Search runs only in the opt-in native build, with Telegram enabled and signed in. */
fun telegramSearchAvailable(nativeBuild: Boolean, enabled: Boolean, authorized: Boolean): Boolean =
    nativeBuild && enabled && authorized

/**
 * Only playable video and audio posts from subscribed channels. [subscribed] null means the
 * caller already restricted the candidates to subscribed channels.
 */
fun telegramSearchResults(
    candidates: List<TelegramMedia>,
    subscribed: Set<Long>?,
    excluded: Set<Long>,
    limit: Int,
): List<TelegramMedia> = candidates.asSequence()
    .filter { subscribed == null || it.chatId in subscribed }
    .filter { it.chatId !in excluded }
    .filter { it.playableOnTelegram && (it.kind == TelegramMediaKind.VIDEO || it.kind == TelegramMediaKind.AUDIO) }
    .distinctBy(TelegramMedia::mediaKey)
    .take(limit.coerceAtLeast(0))
    .toList()

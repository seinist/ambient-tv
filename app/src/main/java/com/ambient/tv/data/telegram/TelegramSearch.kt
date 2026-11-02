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

/**
 * Longest FLOOD_WAIT that is waited out silently, as in the Monogram app (1..5 s, up to
 * [TELEGRAM_FLOOD_AUTO_RETRIES] times). Longer waits are shown to the user with the seconds.
 */
internal const val TELEGRAM_FLOOD_AUTO_WAIT_SECONDS = 5
internal const val TELEGRAM_FLOOD_AUTO_RETRIES = 6

/** Delay before resending a request Telegram rejected with FLOOD_WAIT, or null to report it. */
fun telegramFloodRetryDelayMs(seconds: Int): Long? =
    if (seconds in 0..TELEGRAM_FLOOD_AUTO_WAIT_SECONDS) (seconds + 1).coerceAtLeast(2) * 1_000L else null

/** The session is no longer valid (revoked, deactivated, unknown key): sign in again. */
class TelegramSessionLostException(val type: String) : IllegalStateException("Telegram session ended: $type")

enum class TelegramFailureKind { FLOOD, NETWORK, SESSION, OTHER }

/** Same classification as the Monogram app's TelegramError for the cases Ambient TV acts on. */
fun classifyTelegramFailure(message: String?): TelegramFailureKind {
    val raw = message?.trim()?.removePrefix("v1=")?.trim().orEmpty()
    if (telegramFloodWaitSeconds(raw) != null) return TelegramFailureKind.FLOOD
    val lower = raw.lowercase()
    if (lower.startsWith("session invalidated:")) return TelegramFailureKind.SESSION
    val upper = raw.uppercase()
    // AUTH_KEY_UNSYNCHRONIZED / AUTH_KEY_PERM_EMPTY are transient: a reconnect fixes them.
    if (upper.contains("AUTH_KEY_UNSYNCHRONIZED") || upper.contains("AUTH_KEY_PERM_EMPTY")) return TelegramFailureKind.NETWORK
    if (upper.contains("AUTH_KEY") || upper.contains("SESSION_REVOKED") || upper.contains("SESSION_EXPIRED") ||
        upper.contains("USER_DEACTIVATED")) return TelegramFailureKind.SESSION
    if (lower.contains("rpc timeout") || lower == "timeout" || lower.contains("connection closed") ||
        lower.contains("connection error") || lower.contains("failed to connect") ||
        lower.contains("network is unreachable") || lower.contains("os error") ||
        lower.contains("invalid mtproto") || lower.contains("msg_key") || lower.contains("unknown mtproto constructor") ||
        lower.contains("invalid inbound message")) return TelegramFailureKind.NETWORK
    return TelegramFailureKind.OTHER
}

/** Session error type for [TelegramSessionLostException], e.g. "AUTH_KEY_UNREGISTERED". */
fun telegramSessionErrorType(message: String?): String {
    val raw = message?.trim()?.removePrefix("v1=")?.trim().orEmpty()
    return Regex("[A-Z][A-Z0-9_]{3,63}").findAll(raw.uppercase()).map { it.value }
        .firstOrNull { it.contains("AUTH_KEY") || it.startsWith("SESSION_") || it.startsWith("USER_DEACTIVATED") }
        ?: "SESSION_INVALIDATED"
}

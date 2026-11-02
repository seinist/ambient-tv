package com.ambient.tv.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Like the Monogram app: 1..5 s FLOOD_WAITs are retried silently, longer ones are shown. */
class TelegramFloodWaitPolicyTest {
    @Test fun shortWaitsAreWaitedOut() {
        assertEquals(2_000L, telegramFloodRetryDelayMs(0))
        assertEquals(4_000L, telegramFloodRetryDelayMs(3))
        assertEquals(6_000L, telegramFloodRetryDelayMs(TELEGRAM_FLOOD_AUTO_WAIT_SECONDS))
    }

    @Test fun longWaitsAreReported() {
        assertNull(telegramFloodRetryDelayMs(6))
        assertNull(telegramFloodRetryDelayMs(14))
        assertNull(telegramFloodRetryDelayMs(3_600))
    }

    @Test fun rpcErrorMessagesAreRecognised() {
        assertEquals(14, telegramFloodWaitSeconds("RPC 420: FLOOD_WAIT_14"))
        assertEquals(7, telegramFloodWaitSeconds("FLOOD_PREMIUM_WAIT_7"))
        assertNull(telegramFloodWaitSeconds("CHANNEL_PRIVATE"))
    }

    @Test fun failuresAreClassifiedLikeMonogram() {
        assertEquals(TelegramFailureKind.FLOOD, classifyTelegramFailure("RPC 420: FLOOD_WAIT_14"))
        assertEquals(TelegramFailureKind.SESSION, classifyTelegramFailure("RPC 401: AUTH_KEY_UNREGISTERED"))
        assertEquals(TelegramFailureKind.SESSION, classifyTelegramFailure("v1=SESSION_REVOKED"))
        assertEquals(TelegramFailureKind.SESSION, classifyTelegramFailure("session invalidated: USER_DEACTIVATED_BAN"))
        assertEquals(TelegramFailureKind.NETWORK, classifyTelegramFailure("RPC 401: AUTH_KEY_UNSYNCHRONIZED"))
        assertEquals(TelegramFailureKind.NETWORK, classifyTelegramFailure("connection closed by peer"))
        assertEquals(TelegramFailureKind.NETWORK, classifyTelegramFailure("timeout"))
        assertEquals(TelegramFailureKind.NETWORK, classifyTelegramFailure("io: Network is unreachable (os error 101)"))
        assertEquals(TelegramFailureKind.OTHER, classifyTelegramFailure("RPC 400: CHANNEL_PRIVATE"))
        assertEquals("AUTH_KEY_UNREGISTERED", telegramSessionErrorType("RPC 401: AUTH_KEY_UNREGISTERED"))
        assertEquals("USER_DEACTIVATED_BAN", telegramSessionErrorType("session invalidated: USER_DEACTIVATED_BAN"))
    }
}

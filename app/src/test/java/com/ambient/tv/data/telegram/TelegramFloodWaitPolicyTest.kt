package com.ambient.tv.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Short FLOOD_WAITs are waited out and the request resent; long ones are reported. */
class TelegramFloodWaitPolicyTest {
    @Test fun shortWaitsAreWaitedOut() {
        assertEquals(14_000L, telegramFloodRetryDelayMs(14))
        assertEquals(1_000L, telegramFloodRetryDelayMs(0))
        assertEquals(30_000L, telegramFloodRetryDelayMs(TELEGRAM_FLOOD_AUTO_WAIT_SECONDS))
    }

    @Test fun longWaitsAreReported() {
        assertNull(telegramFloodRetryDelayMs(31))
        assertNull(telegramFloodRetryDelayMs(3_600))
    }

    @Test fun rpcErrorMessagesAreRecognised() {
        assertEquals(14, telegramFloodWaitSeconds("RPC 420: FLOOD_WAIT_14"))
        assertEquals(7, telegramFloodWaitSeconds("FLOOD_PREMIUM_WAIT_7"))
        assertNull(telegramFloodWaitSeconds("CHANNEL_PRIVATE"))
    }
}

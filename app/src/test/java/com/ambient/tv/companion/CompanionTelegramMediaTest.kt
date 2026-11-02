package com.ambient.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Monogram posts travel between companion devices like SmartTube videos. */
class CompanionTelegramMediaTest {
    @Test fun channelPostIdRoundTrips() {
        val id = telegramCompanionId(-1001234567890L, 42)
        assertEquals("-1001234567890/42", id)
        assertEquals(-1001234567890L to 42, parseTelegramCompanionId(id))
    }

    @Test fun malformedIdsAreRejected() {
        listOf("", "0/1", "5/0", "5/-1", "abc/1", "5/1/2", "5:1", "-0/3", "1/99999999999")
            .forEach { assertNull(it, parseTelegramCompanionId(it)) }
    }

    @Test fun telegramPostsSurviveInThePersistedQueue() {
        assertTrue(persistableQueueMedia(CompanionMedia("telegram", "-1001234567890/42")))
        // A source scope belongs to servers; a Monogram post has none.
        assertFalse(persistableQueueMedia(CompanionMedia("telegram", "-1001234567890/42", "a".repeat(64))))
        assertFalse(persistableQueueMedia(CompanionMedia("telegram", "not-a-post")))
    }

    @Test fun nextUpKeyMapsToThePlayerRoute() {
        assertEquals("telegram_player/-1001234567890/42", telegramPlayerRouteFor("telegram:-1001234567890:42"))
        assertNull(telegramPlayerRouteFor("telegram-youtube:-100:42:abcdefghijk"))
        assertNull(telegramPlayerRouteFor("plex-123"))
    }
}

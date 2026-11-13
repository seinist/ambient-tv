package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDetailTelegramLinksTest {
    @Test fun idLinksOpenTheirChannelOrPost() {
        val links = playerDetailTelegramLinks(
            "Teil 2: https://t.me/c/1234567890/42, Kanal t.me/c/1234567890 und nochmal https://t.me/c/1234567890/42")
        assertEquals(listOf(-1001234567890L to 42, -1001234567890L to null), links.map { it.chatId to it.messageId })
    }

    @Test fun publicNameLinksAndLookalikeHostsAreLeftAsText() {
        assertEquals(emptyList<PlayerDetailTelegramLink>(), playerDetailTelegramLinks(
            "https://t.me/somechannel/12 https://nott.me/c/1234567890/1 https://t.me/+invite"))
    }
}

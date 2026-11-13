package com.ambient.tv.ui.player

import com.ambient.tv.data.telegram.telegramUsernameOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerDetailTelegramLinksTest {
    @Test fun idLinksOpenTheirChannelOrPost() {
        val links = playerDetailTelegramLinks(
            "Teil 2: https://t.me/c/1234567890/42, Kanal t.me/c/1234567890 und nochmal https://t.me/c/1234567890/42")
        assertEquals(listOf(-1001234567890L to 42, -1001234567890L to null), links.map { it.chatId to it.messageId })
    }

    @Test fun publicNameLinksAreResolvedWhenOpened() {
        val links = playerDetailTelegramLinks(
            "Mehr: https://t.me/somechannel/12, t.me/s/OtherNews und t.me/somechannel/12")
        assertEquals(listOf(Triple(null, "somechannel", 12), Triple(null, "OtherNews", null)),
            links.map { Triple(it.chatId, it.username, it.messageId) })
    }

    @Test fun invitesStickersLookalikesAndShortNamesAreNoChannels() {
        assertEquals(emptyList<PlayerDetailTelegramLink>(), playerDetailTelegramLinks(
            "https://t.me/+invite https://t.me/joinchat/abc https://t.me/addstickers/Pack " +
                "https://nott.me/somechannel https://t.me/abc https://t.me/share/url?url=x"))
    }

    @Test fun usernamesFollowTelegramsRules() {
        assertEquals("somechannel", telegramUsernameOrNull("@somechannel"))
        assertNull(telegramUsernameOrNull("abc"))
        assertNull(telegramUsernameOrNull("1channel"))
        assertNull(telegramUsernameOrNull("some-channel"))
    }
}

package com.ambient.tv.ui.navigation

import com.ambient.tv.ui.common.MediaSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMenuTest {
    @Test fun entriesFollowTheMenuOrderNotTheStoredOrder() {
        assertEquals(listOf("recent", "videos", "photos"),
            telegramMenuEntries(listOf("photos", "recent", "videos")).map { it.key })
    }

    @Test fun withoutAChoiceTheOverviewStays() {
        assertEquals(listOf("recent"), telegramMenuEntries(emptyList()).map { it.key })
        assertEquals(listOf("recent"), telegramMenuEntries(listOf("unknown")).map { it.key })
    }

    @Test fun menuOrderListsMonogramOnlyWhenSignedIn() {
        val off = SidebarUiState(telegramEnabled = true, telegramAuthorized = false)
        assertFalse(MediaSystem.TELEGRAM in menuOrderSystems(off))
        val on = SidebarUiState(telegramEnabled = true, telegramAuthorized = true)
        assertTrue(MediaSystem.TELEGRAM in menuOrderSystems(on))
    }
}

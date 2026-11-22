package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionGamepadHoldTest {
    private val info = CompanionGamepadInfo(sessionId = "game", interaction = "menu", contextRevision = 4)

    @Test fun touchAndPhysicalConfirmOwnersShareOneHoldAndReleaseOnlyTheLastOwner() {
        val commands = mutableListOf<CompanionGamepadCommand>()
        val hold = CompanionGamepadHold(false) { commands += it; true }
        hold.set("touch", true, info)
        hold.set("controller", true, info)
        hold.renew()
        hold.set("touch", false, info)
        assertTrue(hold.held)
        hold.set("controller", false, info)
        assertFalse(hold.held)
        assertEquals(listOf("ok_down", "ok_renew", "ok_up"), commands.map { it.action })
        assertTrue(commands.all { it.contextRevision == 4L })
    }

    @Test fun renewNeverReacquiresUsingAReplacementContextAndRejectCancelsTheOldTicket() {
        val commands = mutableListOf<CompanionGamepadCommand>()
        val hold = CompanionGamepadHold(false) { commands += it; it.action != "ok_renew" }
        hold.set("touch", true, info)
        hold.renew()
        assertFalse(hold.held)
        assertEquals(listOf("ok_down", "ok_renew", "ok_cancel"), commands.map { it.action })
        hold.renew()
        assertEquals(3, commands.size)
    }

    @Test fun unavailableRewindNeverStartsAndClearingARealHoldSendsARelease() {
        val commands = mutableListOf<CompanionGamepadCommand>()
        val hold = CompanionGamepadHold(true) { commands += it; true }
        hold.set("touch", true, info)
        val playing = info.copy(ready = true, interaction = "playing")
        hold.set("touch", true, playing)
        assertTrue(commands.isEmpty())
        hold.set("touch", true, playing.copy(rewindAvailable = true))
        hold.renew()
        hold.clear()
        assertEquals(listOf(true, true, false), commands.map { it.pressed })
        assertFalse(hold.held)
    }
}

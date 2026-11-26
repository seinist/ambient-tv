package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightInputMailboxTest {
    @Test fun analogReplacementDoesNotCrossDiscreteEdges() {
        val mailbox = MoonlightInputMailbox(8)
        val seen = mutableListOf<Int>()
        assertEquals(MoonlightInputMailbox.Offer.START, mailbox.offer("analog") { seen += 1 })
        assertEquals(MoonlightInputMailbox.Offer.QUEUED, mailbox.offer("analog") { seen += 2 })
        mailbox.offer(null) { seen += 3 }
        mailbox.offer("analog") { seen += 4 }
        while (true) { val task = mailbox.take() ?: break; task() }
        assertEquals(listOf(2, 3, 4), seen)
        assertEquals(MoonlightInputMailbox.Offer.START, mailbox.offer(null) {})
    }
    @Test fun overflowClosesInsteadOfSilentlyDroppingRelease() {
        val mailbox = MoonlightInputMailbox(1)
        mailbox.offer(null) {}
        assertEquals(MoonlightInputMailbox.Offer.OVERFLOW, mailbox.offer(null) {})
        assertEquals(MoonlightInputMailbox.Offer.CLOSED, mailbox.offer(null) {})
        assertNull(mailbox.take())
    }
    @Test fun closedGenerationCannotReplayInputs() {
        val mailbox = MoonlightInputMailbox()
        mailbox.offer(null) { error("stale") }
        mailbox.close()
        assertNull(mailbox.take())
        assertEquals(MoonlightInputMailbox.Offer.CLOSED, mailbox.offer(null) {})
    }
    @Test fun analogKeyPreservesButtonAndNeutralEdges() {
        fun key(button: Int, axis: Short) = MoonlightPadUpdateKey(false, 0, 1, button, 0, 0, axis, 0, 0, 0, null)
        assertEquals(key(1, 100), key(1, 200))
        assertNotEquals(key(1, 100), key(0, 100))
        assertNotEquals(key(1, 100), key(1, 0))
        assertNotEquals(key(1, 100), key(1, -100))
    }
}

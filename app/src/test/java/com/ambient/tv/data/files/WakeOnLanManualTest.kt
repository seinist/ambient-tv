package com.ambient.tv.data.files

import com.ambient.tv.notifications.WakeNoticeTracker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeOnLanManualTest {
    private val posted = mutableListOf<Any>()
    private val dismissed = mutableListOf<Any>()
    private val notices = WakeNoticeTracker({ posted += it }, { dismissed += it })
    private val sends = mutableListOf<String>()
    private val wol = WakeOnLan(directSend = { mac, _, _ -> sends += mac }, notices = notices)
    private val source = FileSource(
        id = "nas", name = "NAS", kind = "smb", host = "nas.local", share = "media",
        wolMac = "AA:BB:CC:DD:EE:FF",
    )

    @Test
    fun repeatManualPressWhileWakingIsIgnored() = runTest {
        notices.begin(wol.machineKey(source), "files", source.name, "files:${source.id}")
        val postsBefore = posted.size
        assertTrue(wol.isMachineWaking(source))

        assertFalse(wol.wakeAndWait(source, manual = true))

        assertTrue(sends.isEmpty())
        assertEquals(postsBefore, posted.size)
        assertTrue(dismissed.isEmpty())
        assertTrue(wol.isMachineWaking(source))
    }

    @Test
    fun manualPressIsIgnoredWhileAnotherSystemWakesSameMachine() = runTest {
        notices.begin(wol.machineKey(source), "dlna", "NAS DLNA", "dlna:x")
        assertTrue(wol.isMachineWaking(source))
        assertFalse(wol.wakeAndWait(source, manual = true))
        assertTrue(sends.isEmpty())
    }

    @Test
    fun idleMachineIsNotWaking() {
        assertFalse(wol.isMachineWaking(source))
    }
}

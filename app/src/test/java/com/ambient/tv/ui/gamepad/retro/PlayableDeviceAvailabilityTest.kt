package com.ambient.tv.ui.gamepad.retro

import com.ambient.tv.companion.COMPANION_HANDHELD
import com.ambient.tv.companion.COMPANION_TV
import com.ambient.tv.companion.CompanionPeer
import org.junit.Assert.*
import org.junit.Test

class PlayableDeviceAvailabilityTest {
    private val first = CompanionPeer("fingerprint-a", "Phone", role = COMPANION_HANDHELD)
    private val second = CompanionPeer("fingerprint-b", "Phone", role = COMPANION_HANDHELD)

    @Test fun offlinePairedPhoneRemainsConfigurableWithoutRunningGame() {
        val snapshot = playableDeviceAvailability(emptyList(), listOf(first), emptySet(), emptyMap(), "Phone")
        assertEquals(listOf(RetroInputKind.COMPANION), availableGameInputKinds(snapshot.devices))
        val device = snapshot.devices.single()
        assertEquals(companionControllerDescriptor(first.fingerprint), device.descriptor)
        assertEquals(CompanionControllerAvailability(false), snapshot.companion[device.descriptor])
    }

    @Test fun sameNamePhonesAndRenamesCannotShareConnectionOrPlayerStatus() {
        val snapshot = playableDeviceAvailability(emptyList(), listOf(first.copy(name = "Renamed"), second),
            setOf(first.fingerprint), mapOf(first.fingerprint to 2, second.fingerprint to 1), "Phone")
        assertEquals(2, snapshot.devices.size)
        assertEquals(CompanionControllerAvailability(true, 2), snapshot.companion[companionControllerDescriptor(first.fingerprint)])
        assertEquals(CompanionControllerAvailability(false), snapshot.companion[companionControllerDescriptor(second.fingerprint)])
    }

    @Test fun noPairingDoesNotCreatePhoneOrPhysicalControllerAndTvPeersStayExcluded() {
        val tv = first.copy(role = COMPANION_TV)
        val snapshot = playableDeviceAvailability(emptyList(), listOf(tv), setOf(tv.fingerprint), emptyMap(), "Phone")
        assertTrue(snapshot.devices.isEmpty())
        assertTrue(availableGameInputKinds(snapshot.devices).isEmpty())
    }

    @Test fun physicalKindsComeFromActualDevicesAndPhonesDoNotBecomeGamepads() {
        val keyboard = RetroInputDevice(3, RetroInputKind.KEYBOARD, "keyboard-3", "Keyboard")
        val snapshot = playableDeviceAvailability(listOf(keyboard), listOf(first, first.copy(name = "Duplicate")),
            emptySet(), emptyMap(), "Phone")
        assertEquals(listOf(RetroInputKind.KEYBOARD, RetroInputKind.COMPANION), availableGameInputKinds(snapshot.devices))
        assertEquals(2, snapshot.devices.size)
        assertFalse(snapshot.devices.any { it.kind == RetroInputKind.GAMEPAD })
    }
}

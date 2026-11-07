package com.ambient.tv.ui.common

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPanelFocusRegistryTest {
    @Test fun tenThousandVisitedRowsRetainOnlyMountedTargets() {
        val registry = PlayerPanelFocusRegistry()
        val mounted = ArrayDeque<Pair<Int, List<Any>>>()
        fun unmount(row: Pair<Int, List<Any>>) {
            val (index, owners) = row
            registry.unregister("preset:$index", owners[0])
            registry.unregister("favorite:$index", owners[1])
            registry.unregister("bar:Favorit", owners[2])
        }

        repeat(10_000) { index ->
            if (mounted.size == 24) unmount(mounted.removeFirst())
            val owners = List(3) { Any() }
            registry.register("preset:$index", owners[0]) { true }
            registry.register("favorite:$index", owners[1]) { true }
            registry.register("bar:Favorit", owners[2]) { true }
            mounted.addLast(index to owners)
            assertEquals(mounted.size * 3, registry.registeredTargetCount)
            assertEquals(mounted.size * 2 + 1, registry.registeredKeyCount)
        }

        while (mounted.isNotEmpty()) unmount(mounted.removeFirst())
        assertEquals(0, registry.registeredTargetCount)
        assertEquals(0, registry.registeredKeyCount)
    }

    @Test fun disposingOutgoingCopyCannotRemoveIncomingCopy() {
        val registry = PlayerPanelFocusRegistry()
        val outgoing = Any()
        val incoming = Any()
        var focused = ""
        registry.register("entry:visuals", outgoing) { focused = "outgoing"; true }
        registry.register("entry:visuals", incoming) { focused = "incoming"; true }

        assertTrue(registry.requestFocus("entry:visuals"))
        assertEquals("incoming", focused)
        registry.unregister("entry:visuals", outgoing)
        assertTrue(registry.requestFocus("entry:visuals"))
        assertEquals("incoming", focused)
        assertEquals(1, registry.registeredTargetCount)

        registry.unregister("entry:visuals", incoming)
        assertFalse(registry.requestFocus("entry:visuals"))
        assertEquals(0, registry.registeredKeyCount)
    }

    @Test fun disposingNewestCopyRevealsTheStillMountedOlderOwner() {
        val registry = PlayerPanelFocusRegistry()
        // Equal values are still separate owners: disposal must compare identities.
        data class Owner(val value: Int)
        val older = Owner(1)
        val newer = Owner(1)
        var focused = ""
        registry.register("bar:Favorit", older) { focused = "older"; true }
        registry.register("bar:Favorit", newer) { focused = "newer"; true }
        registry.unregister("bar:Favorit", newer)

        assertEquals(1, registry.registeredTargetCount)
        assertTrue(registry.requestFocus("bar:Favorit"))
        assertEquals("older", focused)
        registry.unregister("bar:Favorit", newer)
        assertEquals(1, registry.registeredTargetCount)
    }

    @Test fun unreadyIncomingTargetDoesNotSendFocusToOutgoingPanel() {
        val registry = PlayerPanelFocusRegistry()
        var outgoingRequests = 0
        var incomingState = 0
        registry.register("entry:video", Any()) { outgoingRequests++; true }
        registry.register("entry:video", Any()) {
            when (incomingState) {
                0 -> false
                1 -> error("FocusRequester is not attached yet")
                else -> true
            }
        }

        assertFalse(registry.requestFocus("entry:video"))
        incomingState = 1
        assertFalse(registry.requestFocus("entry:video"))
        incomingState = 2
        assertTrue(registry.requestFocus("entry:video"))
        assertEquals(0, outgoingRequests)
    }

    @Test fun restoredKeyResolvesItsRecreatedOwnerWithoutRetainingTheOldOne() {
        val registry = PlayerPanelFocusRegistry()
        val key = "projectm:favorite:classic/preset-9999.milk"
        val original = Any()
        var originalRequests = 0
        var replacementRequests = 0
        registry.register(key, original) { originalRequests++; true }
        registry.unregister(key, original)
        assertFalse(registry.requestFocus(key))
        registry.register(key, Any()) { replacementRequests++; true }

        assertTrue(registry.requestFocus(key))
        assertEquals(0, originalRequests)
        assertEquals(1, replacementRequests)
        assertEquals(1, registry.registeredTargetCount)
    }

    @Test fun missingTargetsAndRepeatedRegistrationDoNotAccumulateEntries() {
        val registry = PlayerPanelFocusRegistry()
        repeat(10_000) { assertFalse(registry.requestFocus("preset:$it")) }
        assertEquals(0, registry.registeredKeyCount)
        val owner = Any()
        repeat(10_000) { registry.register("same", owner) { true } }
        assertEquals(1, registry.registeredTargetCount)
        registry.unregister("same", owner)
        assertEquals(0, registry.registeredKeyCount)
    }
}

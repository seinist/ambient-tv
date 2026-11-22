package com.ambient.tv.companion

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompanionInputContractsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class OldPad(val buttons: Int = 0, val leftX: Float = 0f, val leftY: Float = 0f,
                              val rightX: Float = 0f, val rightY: Float = 0f)

    @Test fun digitalTapGateRetainsPressureAndItsRepressGap() {
        val gate = CompanionButtonEdgeGate(minPressMs = 35, minGapMs = 17)
        val triggers = CompanionTriggerEdges()
        val l2 = com.ambient.tv.ui.gamepad.retro.RetroPadButton.L2.mask
        fun sample(raw: Int, value: Float, at: Long): Float? =
            triggers.pressure(0, raw, gate.update(raw, at), value)
        assertEquals(.7f, sample(l2, .7f, 0))
        assertEquals(.7f, sample(0, 0f, 2)) // retained digital DOWN never reads as zero analog pressure.
        assertEquals(0f, sample(0, 0f, 35))
        assertEquals(0f, sample(l2, .6f, 36)) // the deliberate UP gap must be neutral for analog too.
        assertEquals(.6f, sample(l2, .6f, 52))
        assertEquals(.6f, sample(0, 0f, 54))
        assertEquals(0f, sample(0, 0f, 87))
        assertNull(triggers.pressure(0, l2, l2, null)) // older peers retain digital fallback semantics.
    }

    @Test fun optionalTriggersWorkInBothProtocolDirections() {
        val old = json.decodeFromString<CompanionGamepadState>("""{"buttons":4096}""")
        assertNull(old.leftTrigger)
        val current = CompanionGamepadState(buttons = 8192, leftTrigger = .25f, rightTrigger = .75f)
        current.validate()
        val wire = json.encodeToString(current)
        assertEquals(current, json.decodeFromString<CompanionGamepadState>(wire))
        assertEquals(OldPad(buttons = 8192), json.decodeFromString<OldPad>(wire))
        assertTrue(runCatching { current.copy(leftTrigger = Float.NaN).validate() }.isFailure)
        assertTrue(runCatching { current.copy(rightTrigger = 1.01f).validate() }.isFailure)
    }

    @Test fun pressureEdgesAreOrderedButContinuousTravelCanCoalesce() {
        val neutral = CompanionGamepadState(leftTrigger = 0f, rightTrigger = 0f)
        val partial = neutral.copy(leftTrigger = .2f)
        assertFalse(companionGamepadCriticalEdge(null, neutral))
        assertTrue(companionGamepadCriticalEdge(neutral, partial))
        assertFalse(companionGamepadCriticalEdge(partial, partial.copy(leftTrigger = .3f)))
        assertTrue(companionGamepadCriticalEdge(partial, neutral))
    }

    @Test fun smallAcksRetainOnlySameSessionDescriptionsAndExplicitEmptyClears() {
        val full = CompanionGamepadInfo(sessionId = "one", inputDescriptions = mapOf("0:8" to "Jump"))
        val ack = full.copy(ready = true, inputDescriptions = null)
        assertFalse(json.encodeToString(ack).contains("inputDescriptions"))
        assertEquals(full.copy(ready = true), companionGamepadInfoWithMetadata(full, ack))
        assertEquals(emptyMap<String, String>(), companionGamepadInfoWithMetadata(full,
            ack.copy(inputDescriptions = emptyMap()))?.inputDescriptions)
        assertNull(companionGamepadInfoWithMetadata(full, ack.copy(sessionId = "two"))?.inputDescriptions)
        assertNull(companionGamepadInfoWithMetadata(full, null))
    }
}

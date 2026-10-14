package com.ambient.tv.ui.sound

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NavSoundDesignTest {
    @Test fun exportedSetPreservesAllEventsAndContainsNoSettings() {
        var bank = NavSoundBank()
        UiSound.entries.forEach { bank = bank.with(it, NavSoundDesign.preset("deep", it).copy(echo = 35)) }
        val file = bank.encode()
        val restored = NavSoundBank.import(file)
        UiSound.entries.forEach { assertEquals(bank.design(it), restored.design(it)) }
        assertTrue(file.contains("ambient-navigation-sounds"))
        assertFalse(file.contains("navSoundsEnabled"))
        assertFalse(file.contains("servers"))
    }

    @Test fun invalidOrFutureImportsAreRejectedInsteadOfOverwritingDraftWithDefaults() {
        val valid = NavSoundBank().encode()
        for (file in listOf("{}", "[]", "not json", "x".repeat(32769),
            valid.replace("\"version\":1", "\"version\":99"),
            valid.replace("\"lengthMs\":60", "\"lengthMs\":999999"),
            valid.replace("ambient-navigation-sounds", "other-app"),
            valid.replace("\"FOCUS\"", "\"UNKNOWN\""))) {
            assertTrue(file.take(80), runCatching { NavSoundBank.import(file) }.isFailure)
        }
    }

    @Test fun presetsProduceDeterministicBoundedClickFreeSamples() {
        for (preset in NavSoundDesign.presetNames) for (kind in UiSound.entries) {
            val design = NavSoundDesign.preset(preset, kind)
            assertEquals(design, design.normalized())
            val pcm = NavSoundRenderer.pcm(design, kind)
            assertArrayEquals(pcm, NavSoundRenderer.pcm(design, kind))
            assertEquals(0, pcm.first().toInt())
            assertEquals(0, pcm.last().toInt())
            assertTrue(pcm.any { abs(it.toInt()) > 100 })
            assertTrue(pcm.all { abs(it.toInt()) < 32767 })
            assertTrue(NavSoundRenderer.wav(design, kind).size < 1_000_000)
        }
    }

    @Test fun templatesRemainDistinctAndExportableForEveryEvent() {
        for (kind in UiSound.entries) {
            val designs = NavSoundDesign.presetNames.map { NavSoundDesign.preset(it, kind) }
            assertEquals(kind.name, designs.size, designs.toSet().size)
        }
        for (preset in NavSoundDesign.presetNames) {
            val bank = NavSoundBank(sounds = UiSound.entries.associate {
                it.name to NavSoundDesign.preset(preset, it)
            })
            val restored = NavSoundBank.import(bank.encode())
            UiSound.entries.forEach { assertEquals(bank.design(it), restored.design(it)) }
        }
    }

    @Test fun echoProducesTailAndMaximumParametersStayWithinSampleBudget() {
        val dry = NavSoundDesign(lengthMs = 30, releaseMs = 10, reverb = 0, echo = 0)
        val dryPcm = NavSoundRenderer.pcm(dry, UiSound.SELECT)
        val echo = NavSoundRenderer.pcm(dry.copy(echo = 60, echoMs = 100), UiSound.SELECT)
        assertTrue(echo.size > dryPcm.size)
        assertTrue(echo.drop(2400).any { abs(it.toInt()) > 100 })
        val maximum = dry.copy(lengthMs = 1500, releaseMs = 1000, reverb = 70, tailMs = 4000,
            echo = 60, echoMs = 600, feedback = 60, volume = 100)
        assertTrue(NavSoundRenderer.wav(maximum, UiSound.SELECT).size < 1_000_000)
    }

    @Test fun programmaticFocusAndFocusFollowingClickRemainSilent() {
        val gate = UiSoundEventGate()
        assertFalse(gate.accept(UiSound.FOCUS, 100))
        gate.input(23, 100, 100) // OK
        assertTrue(gate.accept(UiSound.SELECT, 100))
        assertFalse(gate.accept(UiSound.FOCUS, 116)) // next composition frame
        assertFalse(gate.accept(UiSound.FOCUS, 500)) // slow screen entry
        gate.input(20, 501, 501)
        assertTrue(gate.accept(UiSound.FOCUS, 502)) // genuine direction immediately after click
        assertFalse(gate.accept(UiSound.FOCUS, 503)) // manual moveFocus plus onFocusChanged
    }

    @Test fun eachDirectionPressSoundsOnceAndFastIndependentClicksSurvive() {
        val gate = UiSoundEventGate()
        repeat(3) { index ->
            gate.input(20, index.toLong(), index.toLong())
            assertTrue(gate.accept(UiSound.FOCUS, index.toLong()))
            gate.input(20, index.toLong(), index.toLong()) // same event seen at nested root
            assertFalse(gate.accept(UiSound.FOCUS, index.toLong()))
            assertTrue(gate.accept(UiSound.SELECT, index.toLong()))
        }
        gate.input(20, 20, 20)
        assertFalse(gate.accept(UiSound.FOCUS, 800))
    }
}

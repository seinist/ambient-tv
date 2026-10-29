package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSubtitleSelectionTest {
    @Test fun vlcKeepsItsNativeOffKeyAndHasOnlyOneOffOption() {
        val selection = playbackSubtitleSelection(
            nativeOptions = listOf("-1" to "Subtitles off", "3" to "English"),
            selectedNative = "-1",
            teletextOptions = listOf("off" to "Off", "auto" to "Teletext automatic"),
            teletextChoice = "off",
            offLabel = "Fallback off",
        )

        assertEquals(listOf("-1", "3", "teletext:auto"), selection.options.map { it.first })
        assertEquals("-1" to "Subtitles off", selection.options.first())
        assertEquals("-1", selection.selectedKey)
        assertNull(teletextSubtitleChoice(selection.selectedKey))
    }

    @Test fun nativeTrackAndTeletextPageWithTheSameNumberRemainDistinct() {
        val native = listOf("off" to "Off", "150" to "Native subtitles")
        val teletext = listOf("150" to "Teletext page 150")
        val nativeSelected = playbackSubtitleSelection(native, "150", teletext, "off", "Off")
        val teletextSelected = playbackSubtitleSelection(native, "150", teletext, "150", "Off")

        assertEquals(listOf("off", "150", "teletext:150"), nativeSelected.options.map { it.first })
        assertEquals("150", nativeSelected.selectedKey)
        assertNull(teletextSubtitleChoice(nativeSelected.selectedKey))
        assertEquals("teletext:150", teletextSelected.selectedKey)
        assertEquals("150", teletextSubtitleChoice(teletextSelected.selectedKey))
    }

    @Test fun automaticTeletextCanBeSelectedBeforeAnySubtitlePageIsKnown() {
        val selection = playbackSubtitleSelection(
            listOf("off" to "Off"), "off",
            listOf("off" to "Off", "auto" to "Teletext automatic"), "auto", "Off",
        )

        assertEquals(listOf("off", "teletext:auto"), selection.options.map { it.first })
        assertEquals("teletext:auto", selection.selectedKey)
        assertEquals("auto", teletextSubtitleChoice(selection.selectedKey))
    }

    @Test fun announcedAndReceivedCopiesOfTheSamePageKeepTheFirstLabel() {
        val selection = playbackSubtitleSelection(
            listOf("off" to "Off"), "off",
            listOf("auto" to "Automatic", "150" to "Page 150 · DEU", "150" to "Page 150",
                "auto" to "Automatic again", "888" to "Page 888"), "150", "Off",
        )

        assertEquals(listOf("off", "teletext:auto", "teletext:150", "teletext:888"),
            selection.options.map { it.first })
        assertEquals("Page 150 · DEU", selection.options.single { it.first == "teletext:150" }.second)
        assertEquals("teletext:150", selection.selectedKey)
    }

    @Test fun savedPageUsesTheFallbackOptionProvidedForTheNewChannel() {
        val selection = playbackSubtitleSelection(
            listOf("off" to "Off", "2" to "Native subtitles"), "2",
            listOf("auto" to "Automatic", "777" to "Current channel page", "150" to "Saved page 150"),
            "150", "Off",
        )

        assertEquals("teletext:150", selection.selectedKey)
        assertEquals("Saved page 150", selection.options.single { it.first == selection.selectedKey }.second)
    }

    @Test fun unavailableTeletextDoesNotOverrideTheNativeSelection() {
        val native = listOf("off" to "Off", "2" to "Native subtitles")
        for (choice in listOf("auto", "150")) {
            val selection = playbackSubtitleSelection(native, "2", emptyList(), choice, "Off")
            assertEquals(native, selection.options)
            assertEquals("2", selection.selectedKey)
        }
        val missingPage = playbackSubtitleSelection(native, "2", listOf("auto" to "Automatic"), "150", "Off")
        assertEquals("2", missingPage.selectedKey)
    }

    @Test fun missingNativeOffOptionIsAddedWithoutChangingTrackKeys() {
        val empty = playbackSubtitleSelection(emptyList(), "missing", emptyList(), "off", "Subtitles off")
        assertEquals(listOf("off" to "Subtitles off"), empty.options)
        assertEquals("off", empty.selectedKey)

        val withTrack = playbackSubtitleSelection(listOf("7" to "German"), "7", emptyList(), "off", "Subtitles off")
        assertEquals(listOf("off" to "Subtitles off", "7" to "German"), withTrack.options)
        assertEquals("7", withTrack.selectedKey)
    }

    @Test fun duplicateOffAliasesAndAnObsoleteSelectionFallBackToOneNativeOffKey() {
        val selection = playbackSubtitleSelection(
            listOf("-1" to "Off", "off" to "Other off", "-1" to "Duplicate off", "3" to "English"),
            "removed-track", listOf("off" to "Teletext off"), "off", "Fallback off",
        )

        assertEquals(listOf("-1" to "Off", "3" to "English"), selection.options)
        assertEquals("-1", selection.selectedKey)
    }

    @Test fun teletextRoutingAcceptsAutomaticAndPagesWithinTheBroadcastRange() {
        for (choice in listOf("auto", "100", "150", "899")) {
            assertEquals(choice, teletextSubtitleChoice("teletext:$choice"))
        }
    }

    @Test fun invalidTeletextKeysCannotEnterTheSelectorOrBypassNativeRouting() {
        for (key in listOf("off", "-1", "auto", "150", "teletext:off", "teletext:",
            "teletext:99", "teletext:900", "teletext:-1", "teletext:abc", "teletext:150:1")) {
            assertNull(key, teletextSubtitleChoice(key))
        }
        val selection = playbackSubtitleSelection(
            listOf("off" to "Off"), "off",
            listOf("off" to "Off", "99" to "Invalid", "900" to "Invalid", "unknown" to "Invalid"),
            "900", "Off",
        )
        assertEquals(listOf("off" to "Off"), selection.options)
        assertEquals("off", selection.selectedKey)
    }
}

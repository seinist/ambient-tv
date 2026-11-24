package com.ambient.tv.ui.games

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.ambient.tv.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class GameLibraryProgressTest {
    private fun context(language: String): Context {
        val application = RuntimeEnvironment.getApplication()
        return application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }

    @Test fun downloadingMatchingAndEnrichingReportDistinctWorkWithCurrentCounts() {
        val context = context("en")
        val downloading = GameLibraryUiState(scanning = true, scanPhase = GameLibraryScanPhase.NAME_DATABASES,
            scanProcessed = 2, scanDiscovered = 4)
        assertEquals("Loading game names: 2 / 4 systems", gameLibraryProgressMessage(context, downloading))
        val matching = downloading.copy(scanPhase = GameLibraryScanPhase.MATCHING_NAMES,
            scanProcessed = 7, scanDiscovered = 15)
        assertEquals("Matching game names: 7 / 15", gameLibraryProgressMessage(context, matching))
        val enriching = matching.copy(scanPhase = GameLibraryScanPhase.METADATA, enriching = true,
            scanProcessed = 3, scanDiscovered = 15)
        assertEquals("Game information: 3 / 15", gameLibraryProgressMessage(context, enriching))
    }

    @Test fun anUnknownGameCountKeepsTheCurrentPhaseRatherThanClaimingAFreshFileScan() {
        val context = context("en")
        val initial = GameLibraryUiState(scanning = true)
        assertEquals("Scanning games…", gameLibraryProgressMessage(context, initial))
        assertEquals("Loading game names…", gameLibraryProgressMessage(context,
            initial.copy(scanPhase = GameLibraryScanPhase.NAME_DATABASES)))
        assertEquals("Matching game names…", gameLibraryProgressMessage(context,
            initial.copy(scanPhase = GameLibraryScanPhase.MATCHING_NAMES)))
        assertEquals("Add artwork and information", gameLibraryProgressMessage(context,
            initial.copy(scanPhase = GameLibraryScanPhase.METADATA)))
    }

    @Test fun theExplicitPhaseWinsOverAnOldEnrichmentFlag() {
        val context = context("en")
        val state = GameLibraryUiState(scanning = true, enriching = true,
            scanPhase = GameLibraryScanPhase.FILES, scanProcessed = 1, scanDiscovered = 20)
        assertEquals("Checked 1 of 20 discovered files", gameLibraryProgressMessage(context, state))
        assertEquals("Loading game names: 1 / 20 systems", gameLibraryProgressMessage(context,
            state.copy(scanPhase = GameLibraryScanPhase.NAME_DATABASES)))
    }

    @Test fun germanProgressUsesTheSameOperationPhases() {
        val context = context("de")
        val state = GameLibraryUiState(scanning = true, scanPhase = GameLibraryScanPhase.NAME_DATABASES,
            scanProcessed = 1, scanDiscovered = 3)
        assertEquals("Spielenamen werden geladen: 1 / 3 Systeme", gameLibraryProgressMessage(context, state))
        assertEquals("Spielenamen werden zugeordnet: 1 / 3", gameLibraryProgressMessage(context,
            state.copy(scanPhase = GameLibraryScanPhase.MATCHING_NAMES)))
        assertEquals("Spieldaten: 1 / 3", gameLibraryProgressMessage(context,
            state.copy(scanPhase = GameLibraryScanPhase.METADATA)))
    }

    @Test fun completedCatalogMessagesNeverKeepAnInProgressClaim() {
        for (language in listOf("en", "de")) {
            val context = context(language)
            val messages = listOf(
                context.getString(R.string.game_name_databases_loaded, 200, 2),
                context.getString(R.string.game_name_databases_loaded_one, 100, "SNES"),
                context.getString(R.string.game_catalog_imported, 100),
            )
            messages.forEach { message ->
                assertFalse("Finished $language message: $message", message.contains('…'))
                assertFalse("Finished $language message: $message", message.contains("werden eingelesen"))
                assertFalse("Finished $language message: $message", message.contains("being scanned"))
            }
        }
    }
}

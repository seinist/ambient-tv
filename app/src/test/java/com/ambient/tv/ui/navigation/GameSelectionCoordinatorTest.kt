package com.ambient.tv.ui.navigation

import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.games.GameCoreExperiment
import com.ambient.tv.games.GameCoreSource
import com.ambient.tv.games.library.LibraryGame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameSelectionCoordinatorTest {
    private class Fixture(scope: TestScope, network: Boolean = false) {
        var page: String? = "games"
        var source: GameSelectionSource? = GameSelectionSource(FileSource(
            id = "snes-source", name = "SNES", kind = if (network) "smb" else "local",
            treeUri = "content://storage/tree/snes", host = "nas", share = "games",
            wolMac = if (network) "00:11:22:33:44:55" else "", showInFiles = false,
        ), "first-source-incarnation")
        val game = LibraryGame("files:snes-source:Z2FtZS5zZmM", "folder", "snes-source", "game.sfc",
            "game.sfc", "snes", "A game", null, 1024, 0, false, 0)
        val games = mutableMapOf(game.fileId to game)
        var lookup: suspend (String) -> LibraryGame? = { games[it] }
        var needsWake: suspend (String) -> Boolean = { false }
        var wake: suspend (String) -> Boolean = { true }
        var checks = 0
        var wakes = 0
        var launchOffers = 0
        val details = mutableListOf<LibraryGame>()
        val started = mutableListOf<LibraryGame>()
        val startedFresh = mutableListOf<LibraryGame>()
        val startedTests = mutableListOf<Pair<LibraryGame, GameCoreExperiment>>()
        var deferTarget = false
        var completeTarget: ((() -> Unit) -> Unit)? = null
        var targetGame: LibraryGame? = null
        val playbackRequests = PlaybackStartRequests()
        val pendingChoices = mutableSetOf<String>()
        var radioJob: Job? = null
        var otherTarget: (() -> Unit)? = null
        val intents = BrowseSelectionIntents(
            cancelPlayback = playbackRequests::cancel,
            clearPending = {
                pendingChoices.clear()
                radioJob?.cancel()
                radioJob = null
                otherTarget = null
            },
        )
        val coordinator = GameSelectionCoordinator(
            scope, origin = { page }, source = { source }, lookup = { lookup(it) },
            needsWake = { checks++; needsWake(it) }, wake = { wakes++; wake(it) },
            onNewSelection = { intents.begin() },
            openDetails = { details += it },
            launch = { selected, complete ->
                launchOffers++
                targetGame = selected
                completeTarget = complete
                if (!deferTarget) complete { started += selected }
            },
            launchFresh = { selected, complete -> complete { startedFresh += selected } },
            launchExperiment = { selected, test, complete -> complete { startedTests += selected to test } },
        )
        fun completeTarget() { completeTarget?.invoke { started += requireNotNull(targetGame) } }
        fun selectOther(keepPlaybackRequest: Boolean = false): BrowseSelectionIntents.Ticket {
            coordinator.cancel()
            return intents.begin(keepPlaybackRequest)
        }
    }

    @Test fun testLaunchSurvivesWakeConsentAndDoesNotChangeTheNextRegularLaunch() = runTest {
        val f = Fixture(this, network = true)
        f.needsWake = { true }
        val experiment = GameCoreExperiment("snes9x", GameCoreSource.THINLTO)
        f.coordinator.play(f.game.fileId, experiment)
        runCurrent()
        assertEquals(GameSelectionPhase.WAKE, f.coordinator.state.value?.phase)
        assertTrue(f.startedTests.isEmpty())

        f.coordinator.playPending(allowWake = true)
        runCurrent()
        assertEquals(listOf(f.game to experiment), f.startedTests)
        assertTrue(f.started.isEmpty())

        f.needsWake = { false }
        f.coordinator.play(f.game.fileId)
        runCurrent()
        assertEquals(listOf(f.game), f.started)
        assertEquals(1, f.startedTests.size)
    }

    @Test fun gameOptionsRetireALateVideoSelectionOnTheSameHomePage() = runTest {
        val f = Fixture(this)
        f.page = "home"
        val video = requireNotNull(f.playbackRequests.begin("selection:movie", f.page, "server"))
        val resolved = CompletableDeferred<Unit>()
        var videoStarts = 0
        launch {
            withContext(NonCancellable) { resolved.await() }
            if (f.playbackRequests.current(video, f.page, "server")) {
                f.pendingChoices += "media"
                videoStarts++
            }
        }
        runCurrent()
        f.coordinator.select(f.game.fileId, MediaSelectionPreferences.ASK)
        runCurrent()
        resolved.complete(Unit)
        runCurrent()
        assertEquals(GameSelectionPhase.OPTIONS, f.coordinator.state.value?.phase)
        assertTrue(f.pendingChoices.isEmpty())
        assertEquals(0, videoStarts)
    }

    @Test fun gameInfoRetiresOtherChoiceDialogsAndADeferredTargetBeforeItsCatalogReadCompletes() = runTest {
        val f = Fixture(this)
        f.page = "home"
        val targetIntent = f.intents.capture()
        var videoStarts = 0
        val lateTarget: () -> Unit = { if (f.intents.current(targetIntent)) videoStarts++ }
        f.otherTarget = lateTarget
        f.pendingChoices += listOf("media", "youtube", "telegram")
        val catalog = CompletableDeferred<LibraryGame?>()
        f.lookup = { catalog.await() }
        f.coordinator.details(f.game.fileId)
        assertTrue(f.pendingChoices.isEmpty())
        assertNull(f.otherTarget)
        lateTarget()
        assertEquals(0, videoStarts)
        runCurrent()
        catalog.complete(f.game)
        runCurrent()
        assertEquals(listOf(f.game), f.details)
    }

    @Test fun aNewVideoChoiceRetiresALateGameSelectionWithoutLeavingHome() = runTest {
        for (behavior in listOf(MediaSelectionPreferences.DETAILS, MediaSelectionPreferences.PLAY)) {
            val f = Fixture(this)
            f.page = "home"
            val catalog = CompletableDeferred<LibraryGame?>()
            f.lookup = { withContext(NonCancellable) { catalog.await() } }
            f.coordinator.select(f.game.fileId, behavior)
            runCurrent()
            f.selectOther(keepPlaybackRequest = true)
            val video = requireNotNull(f.playbackRequests.begin("selection:movie", f.page, "server"))
            catalog.complete(f.game)
            runCurrent()
            assertTrue(f.started.isEmpty())
            assertTrue(f.details.isEmpty())
            assertNull(f.coordinator.state.value)
            assertTrue(f.playbackRequests.current(video, f.page, "server"))
        }
    }

    @Test fun aNewVideoChoiceRetiresAnAlreadyOfferedGameHandoff() = runTest {
        val f = Fixture(this)
        f.deferTarget = true
        f.coordinator.play(f.game.fileId)
        runCurrent()
        assertEquals(1, f.launchOffers)
        f.selectOther(keepPlaybackRequest = true)
        f.completeTarget()
        runCurrent()
        assertTrue(f.started.isEmpty())
        assertNull(f.coordinator.state.value)
    }

    @Test fun repeatedGamePlayKeepsItsOwnPlaybackGateAndSelectionIntent() = runTest {
        val f = Fixture(this)
        f.deferTarget = true
        f.coordinator.play(f.game.fileId)
        runCurrent()
        val intent = f.intents.capture()
        val gate = requireNotNull(f.playbackRequests.begin("game_player", f.page, "rom-source"))
        f.coordinator.play(f.game.fileId)
        runCurrent()
        assertTrue(f.intents.current(intent))
        assertTrue(f.playbackRequests.current(gate, f.page, "rom-source"))
        assertEquals(1, f.launchOffers)
        f.completeTarget()
        runCurrent()
        assertEquals(listOf(f.game), f.started)
    }

    @Test fun gameSelectionCancelsAnInFlightRadioStartAndRetiresItsLateCallback() = runTest {
        for (nonCancellable in listOf(false, true)) {
            val f = Fixture(this)
            val radioIntent = f.selectOther()
            val ready = CompletableDeferred<Unit>()
            var radioStarts = 0
            val radio = launch {
                if (nonCancellable) withContext(NonCancellable) { ready.await() } else ready.await()
                if (f.intents.current(radioIntent)) radioStarts++
            }
            f.radioJob = radio
            runCurrent()
            f.coordinator.select(f.game.fileId, MediaSelectionPreferences.ASK)
            assertTrue(radio.isCancelled)
            assertNull(f.radioJob)
            ready.complete(Unit)
            runCurrent()
            assertEquals(0, radioStarts)
            assertEquals(GameSelectionPhase.OPTIONS, f.coordinator.state.value?.phase)
        }
    }

    @Test fun repeatedVideoSelectionsStillUseTheExistingPlaybackTicketCoalescing() = runTest {
        val f = Fixture(this)
        f.selectOther(keepPlaybackRequest = true)
        val video = requireNotNull(f.playbackRequests.begin("selection:movie", f.page, "server"))
        f.selectOther(keepPlaybackRequest = true)
        assertNull(f.playbackRequests.begin("selection:movie", f.page, "server"))
        assertTrue(f.playbackRequests.current(video, f.page, "server"))
    }

    @Test fun explicitInfoForTheSameGameReplacesAnInFlightPlayIntent() = runTest {
        val f = Fixture(this, network = true)
        val probe = CompletableDeferred<Boolean>()
        f.needsWake = { withContext(NonCancellable) { probe.await() } }
        f.coordinator.play(f.game.fileId)
        runCurrent()
        val playing = f.intents.capture()
        f.coordinator.details(f.game.fileId)
        runCurrent()
        probe.complete(false)
        runCurrent()
        assertFalse(f.intents.current(playing))
        assertEquals(listOf(f.game), f.details)
        assertTrue(f.started.isEmpty())
        assertEquals(0, f.launchOffers)
    }

    @Test fun detailsAndAskNeverProbeOrWakeTheSource() = runTest {
        val f = Fixture(this, network = true)
        f.needsWake = { error("Browsing must not touch the NAS") }
        f.coordinator.select(f.game.fileId, MediaSelectionPreferences.DETAILS)
        runCurrent()
        assertEquals(listOf(f.game), f.details)
        f.coordinator.select(f.game.fileId, MediaSelectionPreferences.ASK)
        runCurrent()
        assertEquals(GameSelectionPhase.OPTIONS, f.coordinator.state.value?.phase)
        f.coordinator.detailsPending()
        runCurrent()
        assertEquals(2, f.details.size)
        assertEquals(0, f.checks)
        assertEquals(0, f.wakes)
        assertTrue(f.started.isEmpty())
    }

    @Test fun hiddenLocalSourceStartsWithoutInternetOrASeparateFilesEnableFlag() = runTest {
        val f = Fixture(this)
        f.needsWake = { error("A local ROM does not need a network probe") }
        f.coordinator.select(f.game.fileId, MediaSelectionPreferences.PLAY)
        runCurrent()
        assertEquals(listOf(f.game), f.started)
        assertEquals(0, f.checks)
        assertEquals(0, f.wakes)
        assertNull(f.coordinator.state.value)
    }

    @Test fun startOverFromTheOptionsKeepsItsChoiceThroughWake() = runTest {
        val f = Fixture(this, network = true)
        f.needsWake = { true }
        f.coordinator.select(f.game.fileId, MediaSelectionPreferences.ASK)
        runCurrent()
        f.coordinator.playPending(fresh = true)
        runCurrent()
        assertEquals(GameSelectionPhase.WAKE, f.coordinator.state.value?.phase)
        f.coordinator.playPending(allowWake = true)
        runCurrent()
        assertEquals(listOf(f.game), f.startedFresh)
        assertTrue(f.started.isEmpty())
    }

    @Test fun optionsPlayUsesTheSameSourceCheckAndExplicitWakeAsDirectPlay() = runTest {
        val f = Fixture(this, network = true)
        f.needsWake = { true }
        f.coordinator.select(f.game.fileId, MediaSelectionPreferences.ASK)
        runCurrent()
        assertEquals(0, f.checks)
        f.coordinator.playPending()
        runCurrent()
        assertEquals(GameSelectionPhase.WAKE, f.coordinator.state.value?.phase)
        assertEquals(0, f.wakes)
        f.coordinator.playPending(allowWake = true)
        runCurrent()
        assertEquals(1, f.wakes)
        assertEquals(listOf(f.game), f.started)
    }

    @Test fun wakingAnAlreadyRecoveredServerOnlyRetriesTheLaunch() = runTest {
        val f = Fixture(this, network = true)
        f.needsWake = { true }
        f.coordinator.play(f.game.fileId)
        runCurrent()
        f.needsWake = { false }
        f.coordinator.playPending(allowWake = true)
        runCurrent()
        assertEquals(0, f.wakes)
        assertEquals(listOf(f.game), f.started)
    }

    @Test fun repeatedPlayAndTargetCallbacksStartOneSession() = runTest {
        val f = Fixture(this)
        f.deferTarget = true
        repeat(3) { f.coordinator.play(f.game.fileId) }
        runCurrent()
        assertEquals(1, f.launchOffers)
        f.completeTarget()
        f.completeTarget()
        runCurrent()
        f.completeTarget()
        runCurrent()
        assertEquals(listOf(f.game), f.started)
    }

    @Test fun aLateWakeCannotReopenPlaybackAfterBack() = runTest {
        val f = Fixture(this, network = true)
        val wakeDone = CompletableDeferred<Boolean>()
        f.needsWake = { true }
        // An underlying machine-wide wake may complete after its requesting page has left.
        f.wake = { withContext(NonCancellable) { wakeDone.await() } }
        f.coordinator.play(f.game.fileId)
        runCurrent()
        f.coordinator.playPending(allowWake = true)
        runCurrent()
        f.page = "home"
        f.coordinator.navigationChanged()
        wakeDone.complete(true)
        runCurrent()
        assertTrue(f.started.isEmpty())
        assertEquals(0, f.launchOffers)
        assertNull(f.coordinator.state.value)
    }

    @Test fun sourceReplacementDuringAProbeCannotStartTheNewSource() = runTest {
        val f = Fixture(this, network = true)
        val checked = CompletableDeferred<Boolean>()
        f.needsWake = { checked.await() }
        f.coordinator.play(f.game.fileId)
        runCurrent()
        f.source = f.source!!.copy(source = f.source!!.source.copy(host = "different-nas"))
        checked.complete(false)
        runCurrent()
        assertTrue(f.started.isEmpty())
        assertEquals(0, f.launchOffers)
    }

    @Test fun removalAndReadditionWithIdenticalConfigInvalidatesTheTargetPicker() = runTest {
        val f = Fixture(this)
        f.deferTarget = true
        f.coordinator.play(f.game.fileId)
        runCurrent()
        f.source = f.source!!.copy(identity = "re-added-source")
        f.completeTarget()
        runCurrent()
        assertTrue(f.started.isEmpty())
    }

    @Test fun removingAHiddenSourceWhileOptionsAreOpenCannotLaunchIt() = runTest {
        val f = Fixture(this)
        f.coordinator.select(f.game.fileId, MediaSelectionPreferences.ASK)
        runCurrent()
        f.source = null
        f.coordinator.playPending()
        runCurrent()
        assertTrue(f.started.isEmpty())
        assertNull(f.coordinator.state.value)
    }

    @Test fun removingOrReassigningTheRomDuringTargetSelectionCannotLaunchIt() = runTest {
        for (remove in listOf(true, false)) {
            val f = Fixture(this)
            f.deferTarget = true
            f.coordinator.play(f.game.fileId)
            runCurrent()
            if (remove) f.games.clear() else f.games[f.game.fileId] = f.game.copy(systemId = "nes")
            f.completeTarget()
            runCurrent()
            assertTrue(f.started.isEmpty())
            assertEquals(GameSelectionFailure.MISSING, f.coordinator.state.value?.failure)
        }
    }

    @Test fun aCancelledCatalogReadCannotReplaceANewerSelection() = runTest {
        val f = Fixture(this)
        val firstRead = CompletableDeferred<LibraryGame?>()
        val next = f.game.copy(fileId = "files:snes-source:bmV4dC5zZmM", path = "next.sfc", title = "Next game")
        f.games[next.fileId] = next
        f.lookup = { id -> if (id == f.game.fileId) withContext(NonCancellable) { firstRead.await() } else f.games[id] }
        f.coordinator.details(f.game.fileId)
        runCurrent()
        f.coordinator.details(next.fileId)
        runCurrent()
        firstRead.complete(f.game)
        runCurrent()
        assertEquals(listOf(next), f.details)
    }

    @Test fun aFailedWakeKeepsAnActionableRetryAndNeverStarts() = runTest {
        val f = Fixture(this, network = true)
        f.needsWake = { true }
        f.wake = { false }
        f.coordinator.play(f.game.fileId)
        runCurrent()
        f.coordinator.playPending(allowWake = true)
        runCurrent()
        assertEquals(GameSelectionPhase.FAILED, f.coordinator.state.value?.phase)
        assertTrue(f.started.isEmpty())
        f.needsWake = { false }
        f.coordinator.playPending()
        runCurrent()
        assertEquals(listOf(f.game), f.started)
    }
}

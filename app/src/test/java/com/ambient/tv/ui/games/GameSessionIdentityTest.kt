package com.ambient.tv.ui.games

import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.games.GameCore
import com.ambient.tv.games.GameSystem
import com.ambient.tv.games.PreparedGame
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class GameSessionIdentityTest {
    private val core = GameCore("nestopia", "Nestopia UE", "GPL-2.0")
    private val system = GameSystem("nes", "Nintendo Entertainment System", setOf("nes"), core)
    private val game = PreparedGame(
        fileId = "same-rom",
        title = "Same game",
        file = File("game.nes"),
        system = system,
        saveKey = "same-save",
        sourceName = "Device",
        entry = FileEntry("device", "game.nes", "game.nes", directory = false),
    )

    private fun session() = GameSession(
        game = game,
        core = core,
        vulkan = false,
        coreFile = File("nestopia_libretro.so"),
        coreStamp = "same-core-build",
        systemDirectory = File("system"),
        savesDirectory = File("saves"),
        sram = null,
        autoState = null,
    )

    @Test fun identicalStartParametersStillCreateDistinctCompositionKeys() {
        val previous = session()
        val restarted = session()

        assertSame(previous.game, restarted.game)
        assertNotSame(previous, restarted)
        assertNotEquals(previous, restarted)
        assertNotEquals(GameUiState.Ready(previous), GameUiState.Ready(restarted))
    }

    @Test fun newReadySessionCannotBeConflatedWithTheDestroyedSession() {
        val previous = session()
        val restarted = session()
        val observed = MutableStateFlow<GameUiState>(GameUiState.Ready(previous))

        // A fast restart can finish before Compose observes the temporary Preparing state.
        observed.value = GameUiState.Ready(restarted)

        assertSame(restarted, (observed.value as GameUiState.Ready).session)
    }
}

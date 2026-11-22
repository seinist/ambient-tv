package com.ambient.tv.ui.games

import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.games.GameStateStore
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.OsdPlayer

/**
 * One in-memory snapshot governs the toolbar, its settings, state cards and execution guards.
 * Unsupported or temporarily unavailable actions are omitted, rather than shown disabled.
 * Core stamps are resolved by the session owner; rendering this policy performs no file access.
 */
internal data class GameOsdAvailability(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val stateAvailable: Boolean = false,
    val frameExact: Boolean = false,
    val hardcore: Boolean = false,
    val netplay: Boolean = false,
    val supportsCheats: Boolean = false,
    val supportsNetplay: Boolean = false,
    val supportsCoreOptions: Boolean = true,
    val directContent: Boolean = false,
    val achievements: Boolean = false,
    val discCount: Int = 0,
    val states: List<GameStateEntry> = emptyList(),
    val coreId: String = "",
    val coreStamp: String = "",
    val legacyCoreStamp: String? = null,
    /** Includes the user's setting, the actual native buffer and core/session restrictions. */
    val rewindAvailable: Boolean = false,
) {
    private val interactive get() = loaded && !busy

    // Saving does not restore or alter the running timeline; hardcore only prohibits loading.
    val save get() = interactive && stateAvailable
    val load get() = save && !hardcore && !netplay
    /** Existing files can still be renamed/deleted when the current core cannot load them. */
    val manageStates get() = !busy && states.isNotEmpty()
    val reset get() = interactive && !netplay
    val fastForward get() = interactive && !netplay
    val coreOptions get() = interactive && supportsCoreOptions && !netplay
    val cheats get() = interactive && supportsCheats && !hardcore && !netplay
    /** An existing session must retain its disconnect controls if a capability changes. */
    val netplaySetup get() = interactive && (netplay ||
        (supportsNetplay && stateAvailable && frameExact && !directContent && !hardcore))
    val discs get() = interactive && discCount > 1 && !netplay
    val rewind get() = interactive && rewindAvailable && frameExact && !hardcore && !netplay

    fun compatible(entry: GameStateEntry): Boolean = coreId.isNotEmpty() && coreStamp.isNotEmpty() &&
        GameStateStore.compatible(entry, coreId, coreStamp, legacyCoreStamp ?: coreStamp)

    fun canLoad(entry: GameStateEntry): Boolean = load && compatible(entry)

    /** Quickload never selects an incompatible newer file over a usable manual checkpoint. */
    val latestLoadableManual: GameStateEntry? = if (!load) null else states.asSequence()
        .filter { it.kind == GameStateKind.MANUAL && compatible(it) }
        .maxWithOrNull(compareBy<GameStateEntry> { it.createdAt }.thenBy { it.number })

    fun available(button: OsdButton): Boolean = when (button) {
        OsdButton.GAME_SAVE -> save
        OsdButton.GAME_LOAD -> latestLoadableManual != null
        OsdButton.GAME_STATES -> manageStates
        OsdButton.GAME_RESET -> reset
        OsdButton.GAME_FAST_FORWARD -> fastForward
        OsdButton.GAME_REWIND -> rewind
        OsdButton.GAME_CORE -> coreOptions
        OsdButton.GAME_ACHIEVEMENTS -> achievements
        OsdButton.GAME_CHEATS -> cheats
        OsdButton.GAME_NETPLAY -> netplaySetup
        OsdButton.GAME_DISC -> discs
        OsdButton.EXIT, OsdButton.GAME_PICTURE, OsdButton.GAME_CONTROLS, OsdButton.GAME_INFO -> true
        else -> false
    }

    /** Keep Games settings independent of whichever video's action bar was composed last. */
    fun buttons(): List<OsdButton> = OsdPlayer.GAMES.buttons.filter(::available)
}

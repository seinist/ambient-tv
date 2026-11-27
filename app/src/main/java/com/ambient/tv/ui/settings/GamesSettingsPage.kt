package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameSystem
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.metadata.GameMetadataProviderIds
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.games.GameMetadataSettingsPanel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.LocalUiTokens

/** Settings/Games is a small destination hub, not one long list of every installed core. */
internal enum class GamesSettingsPage {
    HOME, LIBRARY, METADATA, SYSTEMS, SYSTEM, CORE, ALL_CORES, CONTROLS, PLAY, ACHIEVEMENTS,
    STORAGE, SHADERS, SYSTEM_FILES, CACHE,
}

/** Search keeps niche settings discoverable without filling the overview with technical rows. */
internal fun gamesSettingsPageForSearch(query: String): GamesSettingsPage? {
    val q = query.lowercase()
    return when {
        q == "home" -> GamesSettingsPage.HOME
        q == "library" || listOf("spielebibliothek", "spieleordner", "game library", "game folder", "rom folder").any { it in q } -> GamesSettingsPage.LIBRARY
        q == "systems" -> GamesSettingsPage.SYSTEMS
        q == "metadata" -> GamesSettingsPage.METADATA
        q == "controls" -> GamesSettingsPage.CONTROLS
        q == "play" -> GamesSettingsPage.PLAY
        q == "storage" -> GamesSettingsPage.STORAGE
        listOf("shader", "filterpaket", "filter pack").any { it in q } -> GamesSettingsPage.SHADERS
        listOf("cache", "zwischenspeicher").any { it in q } -> GamesSettingsPage.CACHE
        listOf("systemdatei", "system file").any { it in q } -> GamesSettingsPage.SYSTEM_FILES
        listOf("achievement", "erfolge", "hardcore").any { it in q } -> GamesSettingsPage.ACHIEVEMENTS
        listOf("controller", "gamepad", "belegung", "mapping", "rumble", "vibration", "tasten").any { it in q } -> GamesSettingsPage.CONTROLS
        listOf("metadaten", "metadata", "cover", "artwork", "screenscraper", "thegamesdb", "bildregion", "datenbank", "dat import").any { it in q } -> GamesSettingsPage.METADATA
        listOf("bios", "firmware", "emulator", "libretro", "core", "konsole").any { it in q } -> GamesSettingsPage.SYSTEMS
        listOf("rewind", "zurückspul", "zurueckspul", "performance profile", "leistungsprofil", "game performance", "spieleleistung").any { it in q } -> GamesSettingsPage.PLAY
        else -> null
    }
}

/** A core opened from a system returns to that system; all other detail pages return to their section. */
internal fun gamesSettingsParent(page: GamesSettingsPage, fromSystem: Boolean = false): GamesSettingsPage = when (page) {
    GamesSettingsPage.SYSTEM -> GamesSettingsPage.SYSTEMS
    GamesSettingsPage.CORE -> if (fromSystem) GamesSettingsPage.SYSTEM else GamesSettingsPage.SYSTEMS
    GamesSettingsPage.ALL_CORES -> GamesSettingsPage.SYSTEMS
    GamesSettingsPage.ACHIEVEMENTS -> GamesSettingsPage.PLAY
    GamesSettingsPage.SHADERS, GamesSettingsPage.SYSTEM_FILES, GamesSettingsPage.CACHE -> GamesSettingsPage.STORAGE
    else -> GamesSettingsPage.HOME
}

@Composable
internal fun GamesSettingsScreen(
    onOpenFiles: () -> Unit,
    onOpenControls: () -> Unit,
    focusContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = gamesEntryPoint()
    val handheld = LocalHandheldUi.current
    val parentBack = LocalSettingsNestedBack.current
    val landingQuery = LocalSettingsSearchLanding.current
    val savedPages = rememberSaveableStateHolder()
    var page by rememberSaveable { mutableStateOf(GamesSettingsPage.HOME) }
    var libraryParent by rememberSaveable { mutableStateOf(GamesSettingsPage.HOME) }
    var selectedSystemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var showOtherSystems by rememberSaveable { mutableStateOf(false) }
    var metadataDialog by remember { mutableStateOf(false) }
    var indexedSystemIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Auto-detected folders have systemId="auto"; look at the indexed ROM systems on IO,
    // never run the SQLite query as part of the Settings composition.
    LaunchedEffect(page) {
        if (page == GamesSettingsPage.SYSTEMS) {
            indexedSystemIds = runCatching { entry.library().indexedSystems() }.getOrDefault(emptySet())
        }
    }
    LaunchedEffect(landingQuery) {
        if (page == GamesSettingsPage.HOME && !landingQuery.isNullOrBlank()) {
            gamesSettingsPageForSearch(landingQuery)?.let { page = it }
        }
    }

    fun back() {
        page = if (page == GamesSettingsPage.LIBRARY) libraryParent
            else gamesSettingsParent(page, selectedSystemId != null)
        if (page == GamesSettingsPage.SYSTEMS) selectedSystemId = null
        if (page != GamesSettingsPage.CORE) selectedCoreId = null
    }
    fun openRoot(target: GamesSettingsPage) {
        selectedSystemId = null
        selectedCoreId = null
        if (target == GamesSettingsPage.LIBRARY) libraryParent = page
        page = target
    }
    // The normal Settings header, remote Back and phone system Back all use the same parent
    // navigation. Do not introduce a second BackHandler that could close the wrong page.
    // The embedded folder manager owns the nested Back callback while it is visible.
    SideEffect { if (page != GamesSettingsPage.LIBRARY) {
        parentBack?.onBack = if (page == GamesSettingsPage.HOME) null else { { back() } }
    } }
    DisposableEffect(parentBack) { onDispose { parentBack?.onBack = null } }

    val assignedIds = remember(page, indexedSystemIds) {
        (entry.library().folders().map { it.systemId } + indexedSystemIds)
            .filter { GameSystems.byId(it) != null }.toSet()
    }
    val (mySystems, otherSystems) = remember(assignedIds) {
        GameSystems.alphabetical.partition { it.id in assignedIds }
    }
    val system = selectedSystemId?.let(GameSystems::byId)
    val coreManager = entry.cores()
    val selectedCore = selectedCoreId?.let { id -> GameSystems.cores.firstOrNull { it.id == id } }

    savedPages.SaveableStateProvider(page.name + ":" + selectedSystemId.orEmpty() + ":" + selectedCoreId.orEmpty()) {
        if (page == GamesSettingsPage.LIBRARY) {
            com.ambient.tv.ui.games.GameLibraryScreen(
                initialSection = "all",
                onSelect = {}, onOpenDetails = {},
                onOpenGameSettings = { target ->
                    openRoot(gamesSettingsPageForSearch(target.orEmpty()) ?: GamesSettingsPage.HOME)
                },
                onBack = ::back,
                embeddedManager = true,
                modifier = modifier,
                focusSettingsContent = focusContent,
            )
            return@SaveableStateProvider
        }
        val listState = rememberLazyListState()
        LaunchedEffect(page, selectedSystemId, selectedCoreId) {
            // Preserve the previously visited list's saved scroll state. The focused item may have
            // been disposed when switching pages, so give the new page a fresh focus target.
            if (!handheld) {
                withFrameNanos { }
                focusContent()
            }
        }
        TvLazyColumn(
            modifier = modifier.fillMaxSize(),
            state = listState,
            allowUpExit = true,
            verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap),
        ) {
            if (page != GamesSettingsPage.HOME) item(key = "games-subpage-heading") {
                val title = when (page) {
                    GamesSettingsPage.METADATA -> stringResource(R.string.games_settings_metadata_heading)
                    GamesSettingsPage.SYSTEMS -> stringResource(R.string.games_settings_systems_heading)
                    GamesSettingsPage.SYSTEM -> system?.name.orEmpty()
                    GamesSettingsPage.CORE -> selectedCore?.name.orEmpty()
                    GamesSettingsPage.ALL_CORES -> stringResource(R.string.games_cores)
                    GamesSettingsPage.CONTROLS -> stringResource(R.string.games_settings_controls_heading)
                    GamesSettingsPage.PLAY -> stringResource(R.string.games_settings_play_heading)
                    GamesSettingsPage.ACHIEVEMENTS -> stringResource(R.string.games_ra)
                    GamesSettingsPage.STORAGE -> stringResource(R.string.games_settings_storage_heading)
                    GamesSettingsPage.SHADERS -> stringResource(R.string.games_shader_packs_title)
                    GamesSettingsPage.SYSTEM_FILES -> stringResource(R.string.games_system_files)
                    GamesSettingsPage.CACHE -> stringResource(R.string.games_cache)
                    GamesSettingsPage.LIBRARY -> stringResource(R.string.games_settings_library_heading)
                    GamesSettingsPage.HOME -> ""
                }
                SettingsSubheader(title, firstInContent = true)
            }

            when (page) {
                GamesSettingsPage.LIBRARY -> Unit // rendered above, without nesting two lazy lists
                GamesSettingsPage.HOME -> {
                    item(key = "games-library") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_library_heading),
                            summary = stringResource(R.string.games_settings_library_summary),
                            icon = Icons.Filled.Folder,
                            onClick = { openRoot(GamesSettingsPage.LIBRARY) },
                        )
                    }
                    item(key = "games-metadata") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_metadata_heading),
                            summary = stringResource(R.string.games_settings_metadata_summary),
                            icon = Icons.Filled.Image,
                            onClick = { openRoot(GamesSettingsPage.METADATA) },
                        )
                    }
                    item(key = "games-systems") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_systems_heading),
                            summary = stringResource(R.string.games_settings_systems_summary),
                            icon = Icons.Filled.SportsEsports,
                            onClick = { openRoot(GamesSettingsPage.SYSTEMS) },
                        )
                    }
                    item(key = "games-controls") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_controls_heading),
                            summary = stringResource(R.string.games_settings_controls_summary),
                            icon = Icons.Filled.Tune,
                            onClick = { openRoot(GamesSettingsPage.CONTROLS) },
                        )
                    }
                    item(key = "games-play") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_play_heading),
                            summary = stringResource(R.string.games_settings_play_summary),
                            icon = Icons.Filled.PlayArrow,
                            onClick = { openRoot(GamesSettingsPage.PLAY) },
                        )
                    }
                    item(key = "games-storage") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_storage_heading),
                            summary = stringResource(R.string.games_settings_storage_summary),
                            icon = Icons.Filled.Storage,
                            onClick = { openRoot(GamesSettingsPage.STORAGE) },
                        )
                    }
                }
                GamesSettingsPage.METADATA -> {
                    item(key = "games-metadata-provider") {
                        val options = entry.metadata().options()
                        val providerTitle = when (options.provider) {
                            "none" -> R.string.game_metadata_settings_off
                            GameMetadataProviderIds.LIBRETRO -> R.string.game_metadata_settings_libretro
                            GameMetadataProviderIds.SCREENSCRAPER -> R.string.game_metadata_settings_screenscraper
                            GameMetadataProviderIds.THEGAMESDB -> R.string.game_metadata_settings_thegamesdb
                            else -> R.string.game_metadata_settings_off
                        }
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_metadata_open),
                            summary = stringResource(providerTitle),
                            onClick = { metadataDialog = true },
                        )
                    }
                    item(key = "games-metadata-recognition") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_detect_title),
                            summary = stringResource(R.string.games_settings_detect_hint),
                            onClick = { openRoot(GamesSettingsPage.LIBRARY) },
                        )
                    }
                }
                GamesSettingsPage.SYSTEMS -> {
                    item(key = "games-my-systems") {
                        SettingsSubheader(stringResource(R.string.games_settings_my_systems))
                    }
                    if (mySystems.isEmpty()) item(key = "games-no-systems") {
                        SettingsHint(stringResource(R.string.games_settings_no_assigned))
                    }
                    items(mySystems, key = { "assigned:" + it.id }) { current ->
                        GamesSettingsSystemRow(current, coreManager.installed(GameSettings.state.systemCore(current), GameSettings.state.coreSource) != null) {
                            selectedSystemId = current.id
                            page = GamesSettingsPage.SYSTEM
                        }
                    }
                    item(key = "games-other-systems") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_more_systems),
                            summary = if (showOtherSystems) null else otherSystems.size.toString(),
                            onClick = { showOtherSystems = !showOtherSystems },
                        )
                    }
                    if (showOtherSystems) items(otherSystems, key = { "other:" + it.id }) { current ->
                        GamesSettingsSystemRow(current, coreManager.installed(GameSettings.state.systemCore(current), GameSettings.state.coreSource) != null) {
                            selectedSystemId = current.id
                            page = GamesSettingsPage.SYSTEM
                        }
                    }
                    item(key = "games-all-cores") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_all_cores),
                            summary = stringResource(R.string.games_cores_hint),
                            onClick = { selectedSystemId = null; selectedCoreId = null; page = GamesSettingsPage.ALL_CORES },
                        )
                    }
                }
                GamesSettingsPage.SYSTEM -> if (system != null) {
                    item(key = "games-system-core-choice") {
                        if (system.alternatives.isNotEmpty()) {
                            val current = GameSettings.state.systemCore(system)
                            val needsBios = stringResource(R.string.game_info_core_needs_bios)
                            SettingsSelect(
                                label = stringResource(R.string.games_system_cores),
                                options = system.cores.map { candidate ->
                                    SettingsSelectOption(
                                        text = listOfNotNull(candidate.name, needsBios.takeIf { candidate.needsBios },
                                            candidate.license.takeIf { !candidate.freeLicense }).joinToString(" · "),
                                        selected = candidate.id == current.id,
                                        onClick = { GameSettings.update { it.withSystemCore(system, candidate) } },
                                    )
                                },
                            )
                        }
                    }
                    item(key = "games-system-emulators-heading") { SettingsSubheader(stringResource(R.string.games_settings_manage_core)) }
                    items(system.cores, key = { "core:" + it.id }) { candidate ->
                        SettingsNavigationRow(
                            title = candidate.name,
                            summary = stringResource(when {
                                coreManager.installed(candidate, GameSettings.state.coreSource) != null -> R.string.games_settings_ready
                                coreManager.info(candidate) != null -> R.string.games_core_wrong_source
                                else -> R.string.games_core_missing
                            }),
                            onClick = { selectedCoreId = candidate.id; page = GamesSettingsPage.CORE },
                        )
                    }
                    if (system.bios.isNotEmpty()) item(key = "games-system-bios") {
                        GamesBios(onOpenFiles, system.id)
                    }
                }
                GamesSettingsPage.CORE -> if (selectedCore != null) item(key = "games-single-core") {
                    GamesCores(onlyCoreId = selectedCore.id)
                }
                GamesSettingsPage.ALL_CORES -> item(key = "games-all-core-controls") { GamesCores() }
                GamesSettingsPage.CONTROLS -> {
                    item(key = "games-rumble") {
                        TvSettingRow(
                            title = stringResource(R.string.game_settings_rumble),
                            body = stringResource(R.string.game_settings_rumble_hint),
                            checked = GameSettings.state.rumble,
                            onCheckedChange = { on -> GameSettings.update { it.withRumble(on) } },
                        )
                    }
                    item(key = "games-control-mapping") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_settings_control_mapping),
                            summary = stringResource(R.string.games_settings_control_mapping_hint),
                            onClick = onOpenControls,
                        )
                    }
                }
                GamesSettingsPage.PLAY -> {
                    item(key = "games-playback-options") { GamesPlay(includeRumble = false) }
                    item(key = "games-achievements-link") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_ra),
                            summary = entry.retroAchievements().account?.user
                                ?: stringResource(R.string.games_settings_achievements_hint),
                            onClick = { page = GamesSettingsPage.ACHIEVEMENTS },
                        )
                    }
                }
                GamesSettingsPage.ACHIEVEMENTS -> item(key = "games-ra-controls") { GamesRetroAchievements() }
                GamesSettingsPage.STORAGE -> {
                    item(key = "games-pack-link") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_shader_packs_title),
                            summary = stringResource(R.string.games_shader_packs_hint),
                            onClick = { page = GamesSettingsPage.SHADERS },
                        )
                    }
                    item(key = "games-system-files-link") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_system_files),
                            summary = stringResource(R.string.games_system_files_hint),
                            onClick = { page = GamesSettingsPage.SYSTEM_FILES },
                        )
                    }
                    item(key = "games-cache-link") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.games_cache),
                            summary = stringResource(R.string.games_settings_storage_cache),
                            onClick = { page = GamesSettingsPage.CACHE },
                        )
                    }
                }
                GamesSettingsPage.SHADERS -> item(key = "games-packs") { GamesShaderPacks() }
                GamesSettingsPage.SYSTEM_FILES -> item(key = "games-sys-files") { GamesSystemFiles() }
                GamesSettingsPage.CACHE -> item(key = "games-rom-cache") { GamesCache() }
            }
        }
    }
    if (metadataDialog) GameMetadataSettingsPanel(onDismiss = { metadataDialog = false })
}

@Composable
private fun GamesSettingsSystemRow(system: GameSystem, coreInstalled: Boolean, onClick: () -> Unit) {
    val core = GameSettings.state.systemCore(system)
    SettingsNavigationRow(
        title = system.name,
        summary = core.name + " · " +
            stringResource(if (coreInstalled) R.string.games_settings_ready else R.string.games_core_missing),
        onClick = onClick,
    )
}

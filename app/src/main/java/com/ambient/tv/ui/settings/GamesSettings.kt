package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.ambient.tv.games.GameCore
import com.ambient.tv.games.GameCoreOption
import com.ambient.tv.games.GameDataImporter
import com.ambient.tv.games.RetroAchievementsClient
import com.ambient.tv.games.GameCoreChannel
import com.ambient.tv.games.GameCoreSource
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.ambient.tv.games.GameBiosManager
import com.ambient.tv.games.GameBiosState
import com.ambient.tv.games.GameCoreManager
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GameSystems
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.games.titleRes
import com.ambient.tv.ui.games.gameOperationError
import com.ambient.tv.ui.theme.LocalUiTokens
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface GamesSettingsEntryPoint {
    fun bios(): GameBiosManager
    fun cores(): GameCoreManager
    fun roms(): GameRomPreparer
    fun data(): GameDataImporter
    fun retroAchievements(): RetroAchievementsClient
    fun channel(): GameCoreChannel
    fun shaderPacks(): com.ambient.tv.games.GameShaderPackStore
    fun shaderChannel(): com.ambient.tv.games.GameShaderPackChannel
}

private fun GameCoreSource.titleRes(): Int = when (this) {
    GameCoreSource.AUTO -> R.string.games_core_source_auto
    GameCoreSource.AMBIENT -> R.string.games_core_source_ambient
    GameCoreSource.BUILDBOT -> R.string.games_core_source_buildbot
}

/**
 * Settings → Playback → Games: rewind, cores (load, update, remove, options for all games), firmware
 * (BIOS) per system, further system files and the game copy cache. Everything lives in app storage.
 */
internal fun LazyListScope.gamesSettingsItems(onOpenLibrary: () -> Unit) {
    item(key = "games-library") {
        SettingsNavigationRow(
            title = stringResource(R.string.game_library_settings_entry),
            summary = stringResource(R.string.game_library_settings_entry_hint),
            onClick = onOpenLibrary,
        )
    }
    item(key = "games-metadata") {
        var sourcesOpen by remember { mutableStateOf(false) }
        SettingsNavigationRow(
            title = stringResource(R.string.game_metadata_settings_title),
            summary = stringResource(R.string.game_metadata_settings_hint),
            onClick = { sourcesOpen = true },
        )
        if (sourcesOpen) com.ambient.tv.ui.games.GameMetadataSettingsPanel(
            onDismiss = { sourcesOpen = false },
        )
    }
    item(key = "games-play") { GamesPlay() }
    item(key = "games-ra") { GamesRetroAchievements() }
    item(key = "games-system-cores") { GamesSystemCores() }
    item(key = "games-cores") { GamesCores() }
    item(key = "games-shader-packs") { GamesShaderPacks() }
    item(key = "games-bios") { GamesBios() }
    item(key = "games-files") { GamesSystemFiles() }
    item(key = "games-cache") { GamesCache() }
}

@Composable
private fun gamesEntryPoint(): GamesSettingsEntryPoint {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, GamesSettingsEntryPoint::class.java)
    }
}

@Composable
private fun GamesPlay() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsHint(stringResource(R.string.games_settings_explanation))
        TvSettingRow(
            title = stringResource(R.string.game_settings_rewind),
            body = stringResource(R.string.game_settings_rewind_hint),
            checked = GameSettings.state.rewind,
            onCheckedChange = { on -> GameSettings.update { it.withRewind(on) } },
        )
        TvSettingRow(
            title = stringResource(R.string.game_settings_rumble),
            body = stringResource(R.string.game_settings_rumble_hint),
            checked = GameSettings.state.rumble,
            onCheckedChange = { on -> GameSettings.update { it.withRumble(on) } },
        )
    }
}

/** RetroAchievements account (token only, in encrypted storage) and play options. */
@Composable
private fun GamesRetroAchievements() {
    val client = gamesEntryPoint().retroAchievements()
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(client.account) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val settings = GameSettings.state
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_ra))
        SettingsHint(stringResource(R.string.games_ra_hint))
        val signedIn = account
        if (signedIn == null) {
            TvTextField(user, { user = it }, stringResource(R.string.games_ra_user))
            TvTextField(password, { password = it }, stringResource(R.string.games_ra_password),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            error?.let { SettingsHint(it) }
            SettingsActionRow {
                TvActionButton(
                    text = stringResource(R.string.games_ra_login),
                    loading = busy,
                    enabled = !busy && user.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { client.login(user, password) }
                                .onSuccess { account = it; password = "" }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                )
            }
        } else {
            SettingsNavigationRow(
                title = stringResource(R.string.games_ra_signed_in, signedIn.user),
                summary = stringResource(R.string.games_ra_logout),
                onClick = {
                    client.logout()
                    account = null
                },
            )
            TvSettingRow(
                title = stringResource(R.string.games_ra_enabled),
                body = stringResource(R.string.games_ra_enabled_hint),
                checked = settings.achievements,
                onCheckedChange = { on -> GameSettings.update { it.withAchievements(on) } },
            )
            TvSettingRow(
                title = stringResource(R.string.games_ra_hardcore),
                body = stringResource(R.string.games_ra_hardcore_hint),
                checked = settings.hardcore,
                enabled = settings.achievements,
                onCheckedChange = { on -> GameSettings.update { it.withHardcore(on) } },
            )
        }
    }
}

@Composable
private fun GamesBios() {
    val bios = gamesEntryPoint().bios()
    var version by remember { mutableIntStateOf(0) }
    val systems = remember(version) { GameSystems.alphabetical.filter { it.bios.isNotEmpty() }.map { it to bios.status(it) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_bios))
        SettingsHint(stringResource(R.string.games_bios_hint))
        systems.forEach { (system, statuses) ->
            SettingsGroup(title = system.name, collapsible = true) {
                statuses.forEach { status ->
                    val required = when {
                        status.bios.requiredFor == null -> stringResource(R.string.games_bios_optional)
                        status.bios.requiredFor.isEmpty() -> stringResource(R.string.games_bios_required)
                        else -> stringResource(R.string.games_bios_required_for, status.bios.requiredFor.joinToString(", ") { ".$it" })
                    }
                    SettingsNavigationRow(
                        title = status.bios.file,
                        summary = listOf(status.bios.description, required, stringResource(status.state.titleRes())).joinToString(" · "),
                        enabled = status.state != GameBiosState.MISSING,
                        onClick = {
                            // Selecting a present file removes it (e.g. to replace it with another dump).
                            bios.remove(status.bios)
                            version++
                        },
                    )
                }
            }
        }
    }
}

/** Signed shader packs are only ever downloaded by an explicit click; nothing at game launch. */
@Composable
private fun GamesShaderPacks() {
    val entry = gamesEntryPoint()
    val packs = entry.shaderPacks()
    val channel = entry.shaderChannel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    val installed = remember(revision) {
        packs.installed().groupBy { it.selection.substringBefore('/') }
    }
    SettingsGroup(
        title = stringResource(R.string.games_shader_packs_title),
        subtitle = stringResource(R.string.games_shader_packs_hint),
        collapsible = true,
    ) {
        if (!channel.available) SettingsHint(stringResource(R.string.games_shader_packs_unavailable))
        SettingsActionRow {
            TvActionButton(
                text = stringResource(R.string.games_shader_packs_download),
                loading = loading,
                enabled = !loading && channel.available,
                onClick = {
                    loading = true
                    status = null
                    scope.launch {
                        runCatching { channel.installLatest() }
                            .onSuccess {
                                status = context.getString(R.string.games_shader_packs_installed, it.title)
                                revision++
                            }
                            .onFailure {
                                status = gameOperationError(context, it, R.string.games_shader_packs_failed)
                            }
                        loading = false
                    }
                },
            )
        }
        installed.forEach { (id, entries) ->
            SettingsActionRow {
                TvActionButton(
                    text = id + " (" + entries.size + ") · " +
                        stringResource(if (pendingRemoval == id) R.string.games_shader_packs_confirm
                            else R.string.games_shader_packs_remove),
                    enabled = !loading,
                    onClick = {
                        if (pendingRemoval == id) {
                            if (packs.remove(id)) revision++
                            pendingRemoval = null
                        } else pendingRemoval = id
                    },
                )
            }
        }
        if (installed.isEmpty()) SettingsHint(stringResource(R.string.games_shader_packs_empty))
        status?.let { SettingsHint(it) }
    }
}

/** Which core plays a system when several can; single games can still choose their own (Info panel). */
@Composable
private fun GamesSystemCores() {
    val settings = GameSettings.state
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_system_cores))
        SettingsHint(stringResource(R.string.games_system_cores_hint))
        GameSystems.alphabetical.filter { it.alternatives.isNotEmpty() }.forEach { system ->
            val current = settings.systemCore(system)
            SettingsNavigationRow(
                title = system.name,
                summary = listOfNotNull(
                    current.name,
                    stringResource(R.string.game_info_core_needs_bios).takeIf { current.needsBios },
                    current.license.takeIf { !current.freeLicense },
                ).joinToString(" · "),
                onClick = {
                    val next = system.cores[(system.cores.indexOfFirst { it.id == current.id } + 1) % system.cores.size]
                    GameSettings.update { it.withSystemCore(system, next) }
                },
            )
        }
    }
}

@Composable
private fun GamesCores() {
    val context = LocalContext.current
    val entry = gamesEntryPoint()
    val cores = entry.cores()
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    /** Downloads in progress: core id → progress (null while the size is unknown). */
    val downloads = remember { mutableStateMapOf<String, Float?>() }
    var failed by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember(context) { android.text.format.DateFormat.getMediumDateFormat(context) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_cores))
        SettingsHint(stringResource(R.string.games_cores_hint))
        val source = GameSettings.state.coreSource
        SettingsNavigationRow(
            title = stringResource(R.string.games_core_source),
            summary = stringResource(source.titleRes()) + if (!entry.channel().available && source != GameCoreSource.BUILDBOT) {
                " · " + stringResource(R.string.games_core_source_unavailable)
            } else "",
            onClick = {
                val next = GameCoreSource.entries[(source.ordinal + 1) % GameCoreSource.entries.size]
                GameSettings.update { it.withCoreSource(next) }
            },
        )
        failed?.let { SettingsHint(it) }
        GameSystems.cores.forEach { core ->
            key(core.id, version) {
                val systems = GameSystems.alphabetical.filter { system -> system.cores.any { it.id == core.id } }.joinToString(", ") { it.name }
                val info = remember(version) { cores.info(core) }
                val progress = downloads[core.id]
                val downloading = core.id in downloads
                SettingsGroup(
                    title = core.name,
                    subtitle = when {
                        downloading -> progress?.let { stringResource(R.string.game_progress, (it * 100).roundToInt()) }
                            ?: stringResource(R.string.game_core_loading, core.name)
                        info == null -> stringResource(R.string.games_core_missing)
                        info.origin == GameCoreManager.SOURCE_BUILDBOT ->
                            stringResource(R.string.games_core_from_buildbot, dateFormat.format(java.util.Date(info.installedAt)))
                        info.origin.startsWith(GameCoreManager.SOURCE_AMBIENT_PREFIX) -> stringResource(
                            R.string.games_core_from_ambient,
                            info.origin.removePrefix(GameCoreManager.SOURCE_AMBIENT_PREFIX),
                            dateFormat.format(java.util.Date(info.installedAt)),
                        )
                        else -> stringResource(R.string.games_core_from_file, info.origin, dateFormat.format(java.util.Date(info.installedAt)))
                    },
                    collapsible = true,
                ) {
                    SettingsHint(stringResource(R.string.games_core_summary, systems, core.license))
                    SettingsActionRow {
                        TvActionButton(
                            text = stringResource(if (info == null) R.string.game_core_load else R.string.games_core_update),
                            loading = downloading,
                            enabled = !downloading && cores.abi != null,
                            onClick = {
                                failed = null
                                downloads[core.id] = null
                                scope.launch {
                                    runCatching { cores.download(core, GameSettings.state.coreSource) { value -> downloads[core.id] = value } }
                                        .onFailure { failed = context.getString(R.string.games_core_download_failed, core.name, it.message.orEmpty()) }
                                    downloads.remove(core.id)
                                    version++
                                }
                            },
                        )
                        if (info != null && remember(version) { cores.canRollback(core) }) TvActionButton(
                            text = stringResource(R.string.games_core_rollback),
                            enabled = !downloading,
                            onClick = {
                                if (!cores.rollback(core)) {
                                    failed = context.getString(R.string.games_core_rollback_failed, core.name)
                                }
                                version++
                            },
                        )
                        if (info != null) TvActionButton(
                            text = stringResource(R.string.game_core_remove),
                            enabled = !downloading,
                            onClick = {
                                cores.remove(core)
                                version++
                            },
                        )
                    }
                    if (info != null) GameCoreOptionsSettings(core, cores.declaredOptions(core))
                }
            }
        }
    }
}

/**
 * The options a core declared the last time it ran, for all its games. Choosing a row steps to the
 * next value; games with their own values (set in the game's panel) keep them.
 */
@Composable
private fun GameCoreOptionsSettings(core: GameCore, declared: List<Pair<String, String>>) {
    val settings = GameSettings.state
    if (declared.isEmpty()) {
        SettingsHint(stringResource(R.string.games_core_options_unknown))
        return
    }
    val stored = settings.coreOptions(core)
    val options = declared.mapNotNull { (key, description) -> GameCoreOption.parse(key, description, stored[key]) }.filter { it.visible }
    SettingsHint(stringResource(R.string.games_core_options_hint))
    options.groupBy { it.category }.forEach { (category, items) ->
        val rows: @Composable () -> Unit = {
            items.forEach { option ->
                val own = option.key in stored
                SettingsNavigationRow(
                    title = option.title,
                    summary = listOfNotNull(
                        if (own) option.label else stringResource(R.string.games_core_option_default, option.label),
                        option.info.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    onClick = {
                        val index = option.values.indexOfFirst { it.value == option.value }
                        val next = option.values[(index + 1) % option.values.size].value
                        GameSettings.update { it.withCoreOption(core, option.key, next.takeIf { value -> value != option.default }) }
                    },
                )
            }
        }
        if (category != null) SettingsGroup(title = category.title, subtitle = category.info.ifBlank { null }, collapsible = true) { rows() }
        else rows()
    }
    if (stored.isNotEmpty()) SettingsNavigationRow(
        title = stringResource(R.string.game_core_options_reset),
        summary = stringResource(R.string.game_core_options_reset_hint),
        onClick = { GameSettings.update { it.withoutCoreOptions(core) } },
    )
}

/** Further files cores read from the system folder (copied from a folder in the file browser). */
@Composable
private fun GamesSystemFiles() {
    val context = LocalContext.current
    val importer = gamesEntryPoint().data()
    var version by remember { mutableIntStateOf(0) }
    val files = remember(version) { importer.systemFiles() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_system_files))
        SettingsHint(stringResource(R.string.games_system_files_hint))
        if (files.isEmpty()) SettingsHint(stringResource(R.string.games_system_files_none))
        else SettingsGroup(
            title = stringResource(R.string.games_system_files_count, files.size),
            subtitle = android.text.format.Formatter.formatShortFileSize(context, files.sumOf { it.size }),
            collapsible = true,
        ) {
            files.forEach { file ->
                SettingsNavigationRow(
                    title = file.path,
                    summary = stringResource(R.string.games_system_file_remove, android.text.format.Formatter.formatShortFileSize(context, file.size)),
                    onClick = {
                        importer.removeSystemFile(file)
                        version++
                    },
                )
            }
        }
    }
}

@Composable
private fun GamesCache() {
    val context = LocalContext.current
    val roms = gamesEntryPoint().roms()
    var version by remember { mutableIntStateOf(0) }
    val bytes = remember(version) { roms.cacheBytes() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_cache))
        SettingsHint(stringResource(R.string.games_cache_hint, android.text.format.Formatter.formatShortFileSize(context, bytes)))
        if (bytes > 0) SettingsActionRow {
            TvActionButton(stringResource(R.string.games_cache_clear), onClick = {
                roms.clearCache()
                version++
            })
        }
    }
}

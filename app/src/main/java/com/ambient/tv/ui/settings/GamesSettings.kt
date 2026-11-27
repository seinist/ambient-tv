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
import com.ambient.tv.games.GameCoreBuildbotConsentRequired
import com.ambient.tv.games.GameCoreSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.ambient.tv.games.GameBiosManager
import com.ambient.tv.games.GameBiosState
import com.ambient.tv.games.GameCoreManager
import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameSettings
import com.ambient.tv.games.GamePerformanceProfile
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
    fun library(): com.ambient.tv.games.library.GameLibraryRepository
    fun metadata(): com.ambient.tv.games.metadata.GameMetadataSettings
}

private fun GameCoreSource.titleRes(): Int = when (this) {
    GameCoreSource.AUTO -> R.string.games_core_source_auto
    GameCoreSource.AMBIENT -> R.string.games_core_source_ambient
    GameCoreSource.BUILDBOT -> R.string.games_core_source_buildbot
    GameCoreSource.OPTIMIZED -> R.string.games_core_source_optimized
    GameCoreSource.OPTIMIZED_A57 -> R.string.games_core_source_optimized_a57
    else -> error("Expected a regular core source")
}

/** Removing a file or core always asks first, like save states and shader packs. */
@Composable
private fun GamesRemoveDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    com.ambient.tv.ui.common.AppDialog(
        onDismiss = onDismiss,
        title = title,
        footer = {
            TvActionButton(stringResource(R.string.files_cancel), onClick = onDismiss)
            TvActionButton(stringResource(R.string.saved_profiles_delete), onClick = onConfirm)
        },
        content = null,
    )
}

@Composable
internal fun gamesEntryPoint(): GamesSettingsEntryPoint {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, GamesSettingsEntryPoint::class.java)
    }
}

private fun GamePerformanceProfile.titleRes(): Int = when (this) {
    GamePerformanceProfile.STANDARD -> R.string.game_performance_standard
    GamePerformanceProfile.REDUCED_BACKGROUND -> R.string.game_performance_reduced_background
    GamePerformanceProfile.LOW_OVERHEAD -> R.string.game_performance_low_overhead
}

private fun GamePerformanceProfile.hintRes(): Int = when (this) {
    GamePerformanceProfile.STANDARD -> R.string.game_performance_standard_hint
    GamePerformanceProfile.REDUCED_BACKGROUND -> R.string.game_performance_reduced_background_hint
    GamePerformanceProfile.LOW_OVERHEAD -> R.string.game_performance_low_overhead_hint
}

@Composable
internal fun GamesPlay(includeRumble: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsHint(stringResource(R.string.games_settings_explanation))
        val settings = GameSettings.state
        val profile = settings.performanceProfile
        SettingsSelect(
            label = stringResource(R.string.game_performance_profile),
            description = stringResource(profile.hintRes()),
            options = GamePerformanceProfile.entries.map { option ->
                SettingsSelectOption(
                    text = stringResource(option.titleRes()),
                    selected = option == profile,
                    onClick = { GameSettings.update { it.withPerformanceProfile(option) } },
                )
            },
        )
        com.ambient.tv.ui.games.GamesGlobalDisplaySettings()
        TvSettingRow(
            title = stringResource(R.string.game_settings_rewind),
            body = stringResource(if (profile.allowsRewind) R.string.game_settings_rewind_hint
                else R.string.game_performance_rewind_suspended),
            checked = settings.rewindForPlayback,
            enabled = profile.allowsRewind,
            onCheckedChange = { on -> GameSettings.update { it.withRewind(on) } },
        )
        if (includeRumble) TvSettingRow(
            title = stringResource(R.string.game_settings_rumble),
            body = stringResource(R.string.game_settings_rumble_hint),
            checked = GameSettings.state.rumble,
            onCheckedChange = { on -> GameSettings.update { it.withRumble(on) } },
        )
    }
}

/** RetroAchievements account (token only, in encrypted storage) and play options. */
@Composable
// Keep value-based password masking while the field uses the existing editing API.
@Suppress("DEPRECATION")
internal fun GamesRetroAchievements() {
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
internal fun GamesBios(onOpenFiles: () -> Unit, selectedSystemId: String? = null) {
    val bios = gamesEntryPoint().bios()
    var version by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<com.ambient.tv.games.GameBios?>(null) }
    val systems = remember(version) { GameSystems.alphabetical.filter { it.bios.isNotEmpty() && (selectedSystemId == null || it.id == selectedSystemId) }.map { it to bios.status(it) } }
    removing?.let { file ->
        GamesRemoveDialog(
            title = stringResource(R.string.games_remove_confirm, file.file),
            onDismiss = { removing = null },
            onConfirm = {
                bios.remove(file)
                removing = null
                version++
            },
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_bios))
        SettingsHint(stringResource(R.string.games_bios_hint))
        // Firmware comes from a folder: the file browser's "Take over game data" checks and copies it.
        SettingsNavigationRow(
            title = stringResource(R.string.games_bios_import),
            summary = stringResource(R.string.games_bios_import_hint),
            onClick = onOpenFiles,
        )
        systems.forEach { (system, statuses) ->
            SettingsGroup(title = system.name, collapsible = true) {
                statuses.forEach { status ->
                    val required = when {
                        status.bios.requiredFor == null -> stringResource(R.string.games_bios_optional)
                        status.bios.requiredFor.isEmpty() -> stringResource(R.string.games_bios_required)
                        else -> stringResource(R.string.games_bios_required_for, status.bios.requiredFor.joinToString(", ") { ".$it" })
                    }
                    val missing = status.state == GameBiosState.MISSING
                    SettingsNavigationRow(
                        title = status.bios.file,
                        summary = listOf(
                            status.bios.description,
                            required,
                            stringResource(status.state.titleRes()),
                            stringResource(if (missing) R.string.games_bios_import else R.string.games_bios_remove),
                        ).joinToString(" · "),
                        // A missing file leads to the import; a present one asks before it is removed.
                        onClick = { if (missing) onOpenFiles() else removing = status.bios },
                    )
                }
            }
        }
    }
}

/** Signed shader packs are only ever downloaded by an explicit click; nothing at game launch. */
@Composable
internal fun GamesShaderPacks() {
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

@Composable
internal fun GamesCores(onlyCoreId: String? = null) {
    val context = LocalContext.current
    val entry = gamesEntryPoint()
    val cores = entry.cores()
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    /** Downloads in progress: core id → progress (null while the size is unknown). */
    val downloads = remember { mutableStateMapOf<String, Float?>() }
    var failed by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Pair<GameCore, GameCoreSource>?>(null) }
    var confirmBuildbot by remember { mutableStateOf<GameCore?>(null) }
    val dateFormat = remember(context) { android.text.format.DateFormat.getMediumDateFormat(context) }

    fun download(core: GameCore, source: GameCoreSource) {
        failed = null
        downloads[core.id] = null
        scope.launch {
            try {
                cores.download(core, source) { value -> downloads[core.id] = value }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GameCoreBuildbotConsentRequired) {
                confirmBuildbot = core
            } catch (error: Exception) {
                failed = context.getString(R.string.games_core_download_failed, core.name, error.message.orEmpty())
            } finally {
                downloads.remove(core.id)
                version++
            }
        }
    }
    confirmBuildbot?.let { core ->
        com.ambient.tv.ui.common.AppDialog(
            onDismiss = { confirmBuildbot = null },
            title = stringResource(R.string.game_buildbot_confirm_title),
            hint = stringResource(R.string.game_buildbot_confirm_body, core.name),
            footer = {
                TvActionButton(stringResource(R.string.back), onClick = { confirmBuildbot = null })
                TvActionButton(stringResource(R.string.game_buildbot_confirm_action), onClick = {
                    confirmBuildbot = null
                    download(core, GameCoreSource.BUILDBOT)
                })
            },
        )
    }
    removing?.let { (core, removeSource) ->
        GamesRemoveDialog(
            title = stringResource(R.string.games_remove_confirm, core.name),
            onDismiss = { removing = null },
            onConfirm = {
                cores.remove(core, removeSource)
                removing = null
                version++
            },
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsSubheader(stringResource(R.string.games_cores))
        SettingsHint(stringResource(R.string.games_cores_hint))
        val source = GameSettings.state.coreSource
        if (onlyCoreId == null) {
            SettingsSelect(
                label = stringResource(R.string.games_core_source),
                description = when (source) {
                    GameCoreSource.AUTO -> stringResource(R.string.games_core_source_auto_hint)
                    GameCoreSource.AMBIENT -> stringResource(if (entry.channel().available)
                        R.string.games_core_source_ambient_hint else R.string.games_core_source_unavailable)
                    else -> null
                },
                options = GameCoreSource.regular.map { option ->
                    SettingsSelectOption(
                        text = stringResource(option.titleRes()),
                        selected = option == source,
                        enabled = (option == GameCoreSource.AUTO || option == GameCoreSource.BUILDBOT || entry.channel().available) &&
                            (option != GameCoreSource.OPTIMIZED_A57 || cores.abi == "arm64-v8a"),
                        onClick = { GameSettings.update { it.withCoreSource(option) } },
                    )
                },
            )
            TvSettingRow(
                title = stringResource(R.string.games_core_tests_enable),
                body = stringResource(R.string.games_core_tests_enable_hint),
                checked = GameSettings.state.coreExperimentsEnabled,
                onCheckedChange = { enabled -> GameSettings.update { it.withCoreExperimentsEnabled(enabled) } },
            )
        }
        failed?.let { SettingsHint(it) }
        val ordered = remember(version, source) {
            GameSystems.cores.filter { onlyCoreId == null || it.id == onlyCoreId }
                .sortedWith(compareBy<GameCore>({ cores.info(it, source) == null }, { it.name.lowercase() }))
        }
        ordered.forEach { core ->
            key(core.id) {
                val systems = GameSystems.alphabetical.filter { system -> system.cores.any { it.id == core.id } }.joinToString(", ") { it.name }
                val info = remember(version, source) { cores.info(core, source) }
                val matchesSource = remember(version, source) { cores.installed(core, source) != null }
                val progress = downloads[core.id]
                val downloading = core.id in downloads
                SettingsGroup(
                    title = core.name,
                    subtitle = when {
                        downloading -> progress?.let { stringResource(R.string.game_progress, (it * 100).roundToInt()) }
                            ?: stringResource(R.string.game_core_loading, core.name)
                        info == null -> stringResource(R.string.games_core_missing)
                        info.origin == GameCoreManager.SOURCE_UNKNOWN -> stringResource(R.string.games_core_from_unknown)
                        info.origin == GameCoreManager.SOURCE_BUILDBOT ->
                            stringResource(R.string.games_core_from_buildbot, dateFormat.format(java.util.Date(info.installedAt)))
                        info.origin.startsWith(GameCoreManager.SOURCE_EXPERIMENT_PREFIX) -> stringResource(
                            R.string.games_core_from_experiment,
                            info.origin.removePrefix(GameCoreManager.SOURCE_EXPERIMENT_PREFIX),
                            dateFormat.format(java.util.Date(info.installedAt)),
                        )
                        info.origin.startsWith(GameCoreManager.SOURCE_AMBIENT_PREFIX) -> stringResource(
                            R.string.games_core_from_ambient,
                            info.origin.removePrefix(GameCoreManager.SOURCE_AMBIENT_PREFIX),
                            dateFormat.format(java.util.Date(info.installedAt)),
                        )
                        else -> stringResource(R.string.games_core_from_file, info.origin, dateFormat.format(java.util.Date(info.installedAt)))
                    },
                    collapsible = onlyCoreId == null,
                ) {
                    SettingsHint(stringResource(R.string.games_core_summary, systems, core.license))
                    if (info != null && !matchesSource) SettingsHint(stringResource(
                        R.string.games_core_source_mismatch, stringResource(source.titleRes()),
                    ))
                    SettingsActionRow {
                        TvActionButton(
                            text = stringResource(if (matchesSource) R.string.games_core_update else R.string.game_core_load),
                            loading = downloading,
                            enabled = !downloading && cores.abi?.let { core.supportedAbis.isEmpty() || it in core.supportedAbis } == true &&
                                (source == GameCoreSource.AUTO || source == GameCoreSource.BUILDBOT || entry.channel().available) &&
                                (source.experimentalProfile == null || cores.abi?.let { source.supportsExperiment(core, it) } == true),
                            onClick = { download(core, source) },
                        )
                        if (remember(version, source) { cores.canRollback(core, source) }) TvActionButton(
                            text = stringResource(R.string.games_core_rollback),
                            enabled = !downloading,
                            onClick = {
                                if (!cores.rollback(core, source)) {
                                    failed = context.getString(R.string.games_core_rollback_failed, core.name)
                                }
                                version++
                            },
                        )
                        if (info != null) TvActionButton(
                            text = stringResource(R.string.game_core_remove),
                            enabled = !downloading,
                            onClick = { removing = core to source },
                        )
                    }
                    if (info != null) GameCoreOptionsSettings(core, cores.declaredOptions(core))
                }
            }
        }
    }
}

/**
 * The options a core declared the last time it ran, for all its games. Each option opens the shared
 * select; games with their own values (set in the game's panel) keep them.
 */
@Composable
private fun GameCoreOptionsSettings(core: GameCore, declared: List<Pair<String, String>>) {
    val settings = GameSettings.state
    if (declared.isEmpty()) {
        SettingsHint(stringResource(R.string.games_core_options_unknown))
        return
    }
    val stored = settings.coreOptions(core)
    // Renderer keys follow the game's graphics choice at every start, so they are not offered here.
    val rendererKeys = core.vulkanOptions.keys + core.glOptions.keys
    val options = declared.filter { (key, _) -> key !in rendererKeys }
        .mapNotNull { (key, description) -> GameCoreOption.parse(key, description, stored[key]) }.filter { it.visible }
    SettingsHint(stringResource(R.string.games_core_options_hint))
    options.groupBy { it.category }.forEach { (category, items) ->
        val rows: @Composable () -> Unit = {
            items.forEach { option ->
                val own = option.key in stored
                SettingsSelect(
                    label = option.title,
                    description = listOfNotNull(
                        stringResource(R.string.games_core_option_default, option.label).takeIf { !own },
                        option.info.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { null },
                    options = option.values.map { value ->
                        SettingsSelectOption(
                            text = value.label,
                            selected = value.value == option.value,
                            onClick = {
                                GameSettings.update {
                                    it.withCoreOption(core, option.key, value.value.takeIf { chosen -> chosen != option.default })
                                }
                            },
                        )
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
internal fun GamesSystemFiles() {
    val context = LocalContext.current
    val importer = gamesEntryPoint().data()
    var version by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<com.ambient.tv.games.GameSystemFile?>(null) }
    val files = remember(version) { importer.systemFiles() }
    removing?.let { file ->
        GamesRemoveDialog(
            title = stringResource(R.string.games_remove_confirm, file.path),
            onDismiss = { removing = null },
            onConfirm = {
                importer.removeSystemFile(file)
                removing = null
                version++
            },
        )
    }
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
                    onClick = { removing = file },
                )
            }
        }
    }
}

@Composable
internal fun GamesCache() {
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

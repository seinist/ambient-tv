package com.ambient.tv.ui.settings

import com.ambient.tv.ui.common.withTvFocusInsets

import androidx.compose.foundation.focusGroup

import com.ambient.tv.ui.mobile.DeviceActionRow

import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ambient.tv.ui.player.ImageChoice
import com.ambient.tv.ui.player.ImageHint
import com.ambient.tv.ui.common.displayTitle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.BuildConfig
import com.ambient.tv.branding.BrandMark
import com.ambient.tv.branding.BrandWordmark
import com.ambient.tv.R
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.data.local.systemHomeRowTypes
import com.ambient.tv.ui.common.systemHomeRowTypeTitle
import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.domain.model.DiscoveredPlexServer
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.EmbyServerProfile
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.systemString
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.QrCodeDialog
import com.ambient.tv.ui.common.OverlayLazyScrollbar
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.rememberPendingAction
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.wrapFocusDownToTop
import com.ambient.tv.ui.common.SettingsSectionHeader
import com.ambient.tv.ui.theme.AccentOptions
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.vpn.WireGuardRouteState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onFocusMenu: () -> Unit = onBack,
    onDone: () -> Unit,
    initialCategoryKey: String? = null,
    onExitGuardChanged: (SettingsExitGuard?) -> Unit = {},
    onUiPreferencesPreview: (Float, String, String, String, Boolean, String, String, Boolean, Int) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onPerformanceModePreview: (String, String) -> Unit = { _, _ -> },
    onVisibleBackgroundVideoPlaybackPreview: (Boolean, Int) -> Unit = { _, _ -> },
    onUiLanguageSaved: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenDlna: () -> Unit = {},
    onTestScreensaver: (SettingsUiState) -> Unit = {},
    onExitApp: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val handheldUi = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val displayContext = LocalContext.current
    val supportedDisplayModes = com.ambient.tv.ui.player.rememberAvailableDisplayModes(displayContext)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshStoredSettings() }
    LaunchedEffect(uiState.uiScale, uiState.accentColorKey, uiState.customAccentHex, uiState.customPlaybackAccentHex, uiState.dualAccentEnabled, uiState.themeModeKey, uiState.uiHdrModeKey, uiState.sdrToHdrEnabled, uiState.sdrToHdrPeakNits) {
        onUiPreferencesPreview(uiState.uiScale, uiState.accentColorKey, uiState.customAccentHex, uiState.customPlaybackAccentHex, uiState.dualAccentEnabled, uiState.themeModeKey, uiState.uiHdrModeKey, uiState.sdrToHdrEnabled, uiState.sdrToHdrPeakNits)
    }
    LaunchedEffect(uiState.uiPerformanceModeKey, uiState.playbackPerformanceModeKey) {
        onPerformanceModePreview(uiState.uiPerformanceModeKey, uiState.playbackPerformanceModeKey)
    }
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose {
            val saved = viewModel.savedAppearance
            onUiPreferencesPreview(saved.uiScale, saved.accentColorKey, saved.customAccentHex, saved.customPlaybackAccentHex, saved.dualAccentEnabled, saved.themeModeKey, saved.uiHdrModeKey, saved.sdrToHdrEnabled, saved.sdrToHdrPeakNits)
            onPerformanceModePreview(saved.uiPerformanceModeKey, saved.playbackPerformanceModeKey)
            onVisibleBackgroundVideoPlaybackPreview(saved.visibleBackgroundVideoPlayback, saved.backgroundVideoDimPercent)
        }
    }
    LaunchedEffect(uiState.visibleBackgroundVideoPlayback, uiState.backgroundVideoDimPercent) {
        onVisibleBackgroundVideoPlaybackPreview(uiState.visibleBackgroundVideoPlayback, uiState.backgroundVideoDimPercent)
    }
    LaunchedEffect(uiState.uiLanguageReloadRequired) {
        if (uiState.uiLanguageReloadRequired) {
            viewModel.acknowledgeUiLanguageReload()
            onUiLanguageSaved()
        }
    }

    var exportWithCredentials by remember { mutableStateOf(false) }
    var askExportPassphrase by remember { mutableStateOf(false) }
    var askSafeExportPassphrase by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var accentPickerRole by remember { mutableStateOf<AccentColorRole?>(null) }

    var playbackOverview by remember { mutableStateOf<PlaybackOverviewSnapshot?>(null) }
    var liveProfiles by remember { mutableStateOf<PlaybackOverviewSnapshot?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val passphrase = exportPassphrase
        exportPassphrase = null
        if (uri != null) viewModel.exportSettings(uri, includeCredentials = exportWithCredentials, passphrase = passphrase)
        else passphrase?.fill('\u0000')
    }
    // Android TV often has no document picker: tell the user instead of crashing.
    val pickerUnavailableMessage = stringResource(R.string.settings_backup_picker_unavailable)
    val launchBackupPicker: (() -> Unit) -> Unit = { launch ->
        if (runCatching(launch).isFailure) {
            exportWithCredentials = false
            android.widget.Toast.makeText(displayContext, pickerUnavailableMessage, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    if (askExportPassphrase) {
        BackupPassphraseDialog(
            confirm = true,
            wrongPassphrase = false,
            onDismiss = { askExportPassphrase = false },
            onPassphrase = { passphrase ->
                askExportPassphrase = false
                exportPassphrase = passphrase
                exportWithCredentials = true
                launchBackupPicker { exportLauncher.launch("ambient-tv-settings-with-credentials.json") }
            },
        )
    }
    if (askSafeExportPassphrase) {
        BackupPassphraseDialog(
            confirm = true,
            optional = true,
            wrongPassphrase = false,
            onDismiss = { askSafeExportPassphrase = false },
            onPassphrase = { passphrase ->
                askSafeExportPassphrase = false
                // Empty = plain JSON as before.
                exportPassphrase = passphrase.takeIf { it.isNotEmpty() }
                exportWithCredentials = false
                launchBackupPicker { exportLauncher.launch("ambient-tv-settings.json") }
            },
        )
    }
    uiState.pendingEncryptedImport?.let { pending ->
        BackupPassphraseDialog(
            confirm = false,
            wrongPassphrase = uiState.backupPassphraseWrong,
            onDismiss = viewModel::cancelEncryptedImport,
            onPassphrase = { passphrase -> viewModel.importSettings(pending, passphrase) },
        )
    }
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) viewModel.exportDiagnosticLog(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importSettings(uri) }

    val plexQr = uiState.linkQrUrl
    if (uiState.isLinkingAccount && !plexQr.isNullOrBlank()) {
        QrCodeDialog(
            title = stringResource(R.string.settings_link_account),
            hint = stringResource(R.string.settings_link_instructions),
            payload = plexQr,
            code = uiState.linkCode,
            onDismiss = viewModel::cancelAccountLinking,
            footer = {
                TvActionButton(
                    text = stringResource(R.string.settings_link_cancel),
                    onClick = viewModel::cancelAccountLinking,
                )
            },
        )
    }

    // The probe dialog takes focus; hand it back to the check button that started the probe.
    val checkUiFocus = remember { FocusRequester() }
    val checkPlaybackFocus = remember { FocusRequester() }
    var lastProbe by remember { mutableStateOf<com.ambient.tv.ui.performance.PerformanceProbeTarget?>(null) }
    LaunchedEffect(uiState.performanceProbe) {
        val finished = lastProbe
        lastProbe = uiState.performanceProbe
        if (uiState.performanceProbe != null || finished == null) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching {
            if (finished == com.ambient.tv.ui.performance.PerformanceProbeTarget.UI) checkUiFocus.requestFocus()
            else checkPlaybackFocus.requestFocus()
        }
    }

    uiState.performanceProbe?.let { probe ->
        val checkingUi = probe == com.ambient.tv.ui.performance.PerformanceProbeTarget.UI
        AppDialog(
            onDismiss = viewModel::cancelPerformanceProbe,
            onDismissRequest = viewModel::cancelPerformanceProbe,
            title = stringResource(
                if (checkingUi) R.string.settings_performance_probe_ui_title
                else R.string.settings_performance_probe_playback_title,
            ),
            hint = stringResource(
                if (checkingUi) R.string.settings_performance_probe_ui_body
                else R.string.settings_performance_probe_playback_body,
            ),
            scrollable = false,
            captureInitialFocus = true,
            footer = { TvActionButton(stringResource(R.string.player_cancel), viewModel::cancelPerformanceProbe) },
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
            }
        }
    }

    LaunchedEffect(viewModel) { viewModel.uiLanguageChanged.collect { onUiLanguageSaved() } }
    SettingsHub(
        state = uiState,
        viewModel = viewModel,
        initialCategoryKey = initialCategoryKey,
        onExitGuardChanged = onExitGuardChanged,
        onBack = onBack,
        onFocusMenu = onFocusMenu,
        onDone = onDone,
        onSave = { after ->
            // Commits the deferred address/sign-in fields; the UI language change arrives via uiLanguageChanged.
            val changed = viewModel.commitPending()
            if (changed != null) after()
            changed != null
        },
        onRequestAppExit = { showExitConfirmation = true },
    ) { selectedCategory, listState, panelModifier, focusContent, requestExit ->
        Column(modifier = panelModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsPaneHeader(selectedCategory)
            if (selectedCategory == SettingsCategory.RECEIVER) {
                Text(stringResource(R.string.settings_network_receiver_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (selectedCategory.area == SettingsArea.SYSTEMS || selectedCategory in setOf(SettingsCategory.BACKUP, SettingsCategory.WIREGUARD, SettingsCategory.TRANSFER)) {
                Text(stringResource(R.string.settings_actions_immediate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selectedCategory == SettingsCategory.NAV_SOUNDS) {
                NavSoundEditor(
                    value = uiState.navSoundBank, volume = uiState.navSoundsVolume,
                    onValueChange = viewModel::onNavSoundBankChanged,
                    soundsEnabled = uiState.navSoundsEnabled,
                    onEnabledChange = viewModel::onNavSoundsEnabledChanged,
                    onVolumeChange = viewModel::onNavSoundsVolumeChanged,
                    modifier = Modifier.weight(1f),
                )
                return@Column
            }
            val wakeRevision by viewModel.wakeSourcesRevision.collectAsState(initial = 0L to 0L)
            val wakeGroups = remember(uiState, wakeRevision, selectedCategory) {
                if (selectedCategory == SettingsCategory.WAKE) viewModel.wakeGroups(uiState) else emptyList()
            }
            val scrollbarContentFocus = remember { FocusRequester() }
            val listOverflow = listState.canScrollForward || listState.canScrollBackward
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.focusRequester(scrollbarContentFocus).focusGroup().fillMaxSize().wrapFocusDownToTop(listState).padding(end = if (listOverflow) 16.dp else 0.dp),
                    contentPadding = PaddingValues(bottom = 24.dp).withTvFocusInsets(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (selectedCategory) {
                        SettingsCategory.NAV_SOUNDS -> Unit // Full-page editor above.
                        SettingsCategory.COMPANION -> companionSettingsItems()
                        SettingsCategory.TRANSFER -> {
                            item { CompanionTransferSection() }
                            item { SettingsPageLink(SettingsCategory.COMPANION, "transfer-devices") }
                            item { SettingsPageLink(SettingsCategory.BACKUP, "transfer-backup") }
                        }
                        SettingsCategory.WAKE -> networkWakeItems(uiState, viewModel, wakeGroups, requestExit)
                        SettingsCategory.RECEIVER -> networkReceiverItems(viewModel)
                        SettingsCategory.NOTIFICATIONS -> {
                            notificationHistoryItems(uiState, viewModel)
                        }
                        SettingsCategory.APPEARANCE -> {
                            item { AppearancePreview(uiScale = uiState.uiScale, themeModeKey = uiState.themeModeKey) }
                            item {
                                UiHdrSettings(
                                    mode = uiState.uiHdrModeKey,
                                    onModeChanged = viewModel::onUiHdrModeChanged,
                                    sdrToHdrEnabled = uiState.sdrToHdrEnabled,
                                    onSdrToHdrChanged = viewModel::onSdrToHdrChanged,
                                    sdrToHdrPeakNits = uiState.sdrToHdrPeakNits,
                                    onSdrToHdrPeakNitsChanged = viewModel::onSdrToHdrPeakNitsChanged,
                                )
                            }
                            item {
                                SettingsGroup(title = stringResource(R.string.settings_theme_mode)) {
                                    SettingsSelect(options = buildList {
                                        listOf("dark", "oled", "dim").forEach { mode ->
                                            add(SettingsSelectOption(
                                                text = themeModeLabel(mode),
                                                selected = uiState.themeModeKey == mode,
                                                onClick = { viewModel.onThemeModeChanged(mode) },
                                            ))
                                        }
                                    })
                                }
                            }
                            item {
                                com.ambient.tv.ui.common.RightPanelValueSlider(
                                    title = stringResource(R.string.settings_ui_scale),
                                    subtitle = stringResource(R.string.settings_ui_scale_hint),
                                    value = (uiState.uiScale * 100).roundToInt(),
                                    valueRange = com.ambient.tv.data.local.UiScalePolicy.percentRange,
                                    step = 5,
                                    valueLabel = "${(uiState.uiScale * 100).roundToInt()}%",
                                    onValueChange = { viewModel.onUiScaleChanged(it / 100f) },
                                )
                            }
                            item {
                                if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) return@item
                                var overscan by remember { mutableIntStateOf(viewModel.overscanPercent) }
                                com.ambient.tv.ui.common.RightPanelValueSlider(
                                    title = stringResource(R.string.settings_overscan),
                                    subtitle = stringResource(R.string.settings_overscan_hint),
                                    value = overscan,
                                    valueRange = com.ambient.tv.data.local.OverscanPolicy.percentRange,
                                    step = 1,
                                    valueLabel = "$overscan%",
                                    onValueChange = { overscan = it; viewModel.overscanPercent = it },
                                )
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_accent_color),
                                    subtitle = stringResource(if (uiState.dualAccentEnabled) R.string.settings_accent_pair_hint else R.string.settings_accent_single_hint),
                                ) {
                                    SettingsSelect(options = (AccentOptions.entries.map { it.key } + listOf("artwork", "custom")).map { key ->
                                        SettingsSelectOption(
                                            text = accentLabel(key),
                                            selected = uiState.accentColorKey == key,
                                            onClick = {
                                                viewModel.onAccentColorChanged(key)
                                                if (key == "custom") accentPickerRole = AccentColorRole.PRIMARY
                                            },
                                            swatchColors = accentSwatchColors(key, uiState.customAccentHex, uiState.customPlaybackAccentHex, uiState.dualAccentEnabled),
                                        )
                                    })
                                    AccentPairPreview(uiState.accentColorKey, uiState.customAccentHex, uiState.customPlaybackAccentHex, uiState.dualAccentEnabled)
                                }
                            }
                            item {
                                TvSettingRow(
                                    title = stringResource(R.string.settings_dual_accent),
                                    body = stringResource(R.string.settings_dual_accent_hint),
                                    checked = uiState.dualAccentEnabled,
                                    onCheckedChange = viewModel::onDualAccentChanged,
                                )
                            }
                            if (uiState.dualAccentEnabled) item {
                                TvActionButton(
                                    text = stringResource(R.string.settings_swap_accent_colors),
                                    onClick = viewModel::onSwapAccentColors,
                                )
                            }
                            if (uiState.accentColorKey == "custom") {
                                item {
                                    CustomAccentSetting(
                                        role = AccentColorRole.PRIMARY,
                                        value = uiState.customAccentHex,
                                        onChoose = { accentPickerRole = AccentColorRole.PRIMARY },
                                    )
                                }
                                if (uiState.dualAccentEnabled) item {
                                    CustomAccentSetting(
                                        role = AccentColorRole.PLAYBACK,
                                        value = com.ambient.tv.ui.theme.normalizedCustomPlaybackAccentHex(uiState.customPlaybackAccentHex, uiState.customAccentHex),
                                        onChoose = { accentPickerRole = AccentColorRole.PLAYBACK },
                                    )
                                }
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_ui_performance),
                                    subtitle = stringResource(R.string.settings_ui_performance_hint),
                                ) {
                                    PerformanceModeSelect(
                                        selected = uiState.uiPerformanceModeKey,
                                        onSelected = viewModel::onUiPerformanceModeChanged,
                                    )
                                }
                            }
                            item {
                                TvActionButton(
                                    text = stringResource(R.string.settings_check_ui),
                                    onClick = viewModel::checkUiPerformance,
                                    loading = uiState.performanceProbe != null,
                                    modifier = Modifier.focusRequester(checkUiFocus),
                                    fillWidth = true,
                                )
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_playback_performance),
                                    subtitle = stringResource(R.string.settings_playback_performance_hint),
                                ) {
                                    PerformanceModeSelect(
                                        selected = uiState.playbackPerformanceModeKey,
                                        onSelected = viewModel::onPlaybackPerformanceModeChanged,
                                    )
                                }
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_frame_drop_duration),
                                    subtitle = stringResource(R.string.settings_frame_drop_duration_hint),
                                ) {
                                    SettingsSelect(options = listOf(0, 2, 5, 6, 10, 15, 30, 60).map { seconds ->
                                        SettingsSelectOption(
                                            text = if (seconds == 0) stringResource(R.string.player_automatic) else stringResource(R.string.unit_seconds, seconds),
                                            selected = uiState.frameDropTestSeconds == seconds,
                                            onClick = { viewModel.onFrameDropTestSecondsChanged(seconds) },
                                        )
                                    })
                                }
                            }
                            item {
                                TvActionButton(
                                    text = stringResource(R.string.settings_check_playback),
                                    onClick = viewModel::checkPlaybackPerformance,
                                    loading = uiState.performanceProbe != null,
                                    modifier = Modifier.focusRequester(checkPlaybackFocus),
                                    fillWidth = true,
                                )
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_home_hero_style),
                                    subtitle = stringResource(R.string.settings_home_hero_style_hint),
                                ) {
                                    TvSettingRow(
                                        title = stringResource(R.string.settings_home_background_everywhere),
                                        body = stringResource(R.string.settings_home_background_everywhere_hint),
                                        checked = uiState.homeBackgroundEverywhere,
                                        onCheckedChange = viewModel::onHomeBackgroundEverywhereChanged,
                                    )
                                    TvSettingRow(
                                        title = stringResource(R.string.settings_focus_artwork_backdrop),
                                        body = stringResource(R.string.settings_focus_artwork_backdrop_hint),
                                        checked = uiState.focusArtworkBackdrop,
                                        onCheckedChange = viewModel::onFocusArtworkBackdropChanged,
                                    )
                                    SettingsSelect(options = HomeHeroStyle.keys.map { style ->
                                        SettingsSelectOption(
                                            text = homeHeroStyleLabel(style),
                                            selected = uiState.homeHeroStyleKey == style,
                                            onClick = { viewModel.onHomeHeroStyleChanged(style) },
                                        )
                                    })
                                    com.ambient.tv.ui.common.RightPanelValueSlider(
                                        title = stringResource(R.string.settings_home_hero_intensity),
                                        value = uiState.homeHeroIntensityPercent,
                                        valueRange = 0..100,
                                        step = 10,
                                        valueLabel = "${uiState.homeHeroIntensityPercent} %",
                                        onValueChange = viewModel::onHomeHeroIntensityChanged,
                                    )
                                    com.ambient.tv.ui.common.PlayerDropUpSelector(
                                        label = stringResource(R.string.settings_home_hero_spread),
                                        value = homeHeroSpreadLabel(uiState.homeHeroSpreadKey),
                                        options = HomeHeroStyle.spreads.map { it to homeHeroSpreadLabel(it) },
                                        selectedKey = uiState.homeHeroSpreadKey,
                                        onSelected = viewModel::onHomeHeroSpreadChanged,
                                    )
                                }
                            }

                        }

                        SettingsCategory.LANGUAGE -> {
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_ui_language),
                                    subtitle = stringResource(R.string.settings_ui_language_hint),
                                ) {
                                    LanguageChoiceRow(
                                        selected = uiState.uiLanguageKey,
                                        onSelected = viewModel::onUiLanguageChanged,
                                    )
                                }
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_metadata_language),
                                    subtitle = MediaSystem.renamedAll(stringResource(R.string.settings_metadata_language_hint)),
                                ) {
                                    LanguageChoiceRow(
                                        selected = uiState.metadataLanguageKey,
                                        onSelected = viewModel::onMetadataLanguageChanged,
                                    )
                                }
                            }
                        }

                        SettingsCategory.MEDIA_SELECTION -> {
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_media_selection_title),
                                    subtitle = MediaSystem.renamedAll(stringResource(R.string.settings_media_selection_hint)),
                                ) {
                                    SettingsSelect(options = buildList {
                                        listOf(
                                            MediaSelectionPreferences.DETAILS,
                                            MediaSelectionPreferences.PLAY,
                                            MediaSelectionPreferences.ASK,
                                        ).forEach { behavior ->
                                            add(SettingsSelectOption(
                                                text = stringResource(when (behavior) {
                                                    MediaSelectionPreferences.PLAY -> R.string.settings_media_selection_play
                                                    MediaSelectionPreferences.ASK -> R.string.settings_media_selection_ask
                                                    else -> R.string.settings_media_selection_details
                                                }),
                                                selected = uiState.mediaSelectionBehaviorKey == behavior,
                                                onClick = { viewModel.onMediaSelectionBehaviorChanged(behavior) },
                                            ))
                                        }
                                    })
                                }
                            }
                        }

                        SettingsCategory.SCREENSAVER -> {
                            item {
                                TvSettingRow(
                                    title = stringResource(R.string.settings_screensaver_enabled),
                                    body = stringResource(R.string.settings_screensaver_enabled_hint),
                                    checked = uiState.screensaverEnabled,
                                    onCheckedChange = viewModel::onScreensaverEnabledChanged,
                                )
                            }
                            if (uiState.screensaverEnabled) {
                                item {
                                    SettingsGroup(
                                        title = stringResource(R.string.settings_screensaver_timeout),
                                        subtitle = stringResource(R.string.settings_screensaver_timeout_hint),
                                    ) {
                                        SettingsSelect(options = buildList {
                                            listOf(5, 10, 15, 30, 60).forEach { minutes ->
                                                add(SettingsSelectOption(
                                                    text = stringResource(R.string.settings_screensaver_minutes, minutes),
                                                    selected = uiState.screensaverTimeoutMinutes == minutes,
                                                    onClick = { viewModel.onScreensaverTimeoutChanged(minutes) },
                                                ))
                                            }
                                        })
                                    }
                                }
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_screensaver_mode),
                                    subtitle = stringResource(R.string.settings_screensaver_mode_hint),
                                ) {
                                    SettingsSelect(options = buildList {
                                        run {
                                            add(SettingsSelectOption(
                                                text = stringResource(R.string.settings_screensaver_mode_ambient),
                                                selected = uiState.screensaverModeKey == "ambient",
                                                onClick = { viewModel.onScreensaverModeChanged("ambient") },
                                            ))
                                        }
                                        listOf(
                                            "aurora" to R.string.settings_screensaver_mode_aurora,
                                            "fluid" to R.string.settings_screensaver_mode_fluid,
                                            "starfield" to R.string.settings_screensaver_mode_starfield,
                                            "silk" to R.string.settings_screensaver_mode_silk,
                                        ).forEach { (key, label) ->
                                            add(SettingsSelectOption(
                                                text = stringResource(label),
                                                selected = uiState.screensaverModeKey == key,
                                                onClick = { viewModel.onScreensaverModeChanged(key) },
                                            ))
                                        }
                                        run {
                                            add(SettingsSelectOption(
                                                text = stringResource(R.string.settings_screensaver_mode_media),
                                                selected = uiState.screensaverModeKey == "media",
                                                onClick = { viewModel.onScreensaverModeChanged("media") },
                                            ))
                                        }
                                        run {
                                            add(SettingsSelectOption(
                                                text = stringResource(R.string.settings_screensaver_mode_oled),
                                                selected = uiState.screensaverModeKey == "oled",
                                                onClick = { viewModel.onScreensaverModeChanged("oled") },
                                            ))
                                        }
                                        run {
                                            add(SettingsSelectOption(
                                                text = stringResource(R.string.settings_screensaver_mode_photos),
                                                selected = uiState.screensaverModeKey == "photos",
                                                onClick = { viewModel.onScreensaverModeChanged("photos") },
                                            ))
                                        }
                                    })
                                }
                            }
                            if (uiState.screensaverModeKey == "photos") {
                                item { ScreensaverPhotoSourcePicker(uiState, viewModel) }
                            }
                            if (com.ambient.tv.ui.common.ShaderScene.forKey(uiState.screensaverModeKey) != null) {
                                item {
                                    SettingsGroup(
                                        title = stringResource(R.string.settings_screensaver_quality),
                                        subtitle = stringResource(R.string.settings_screensaver_quality_hint),
                                    ) {
                                        SettingsSelect(options = buildList {
                                            listOf("economy", "balanced", "high").forEach { quality ->
                                                add(SettingsSelectOption(
                                                    text = when (quality) {
                                                        "economy" -> stringResource(R.string.settings_projectm_quality_economy)
                                                        "high" -> stringResource(R.string.settings_projectm_quality_high)
                                                        else -> stringResource(R.string.settings_projectm_quality_balanced)
                                                    },
                                                    selected = uiState.screensaverQualityKey == quality,
                                                    onClick = { viewModel.onScreensaverQualityChanged(quality) },
                                                ))
                                            }
                                        })
                                    }
                                }
                            }
                            item {
                                TvActionButton(
                                    text = stringResource(R.string.settings_screensaver_test),
                                    onClick = { onTestScreensaver(uiState) },
                                    // The overlay consumes KeyUp; avoid retaining the button's
                                    // private pressed state while keeping its DPAD focus.
                                    modifier = Modifier.activateOnTvKey { onTestScreensaver(uiState) },
                                )
                            }
                            item {
                                Text(stringResource(R.string.settings_screensaver_test_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item {
                                TvSettingRow(
                                    title = stringResource(R.string.settings_screensaver_clock),
                                    body = if (uiState.screensaverModeKey == "oled") {
                                        stringResource(R.string.settings_screensaver_clock_oled_hint)
                                    } else {
                                        stringResource(R.string.settings_screensaver_clock_hint)
                                    },
                                    checked = uiState.screensaverShowClock,
                                    onCheckedChange = viewModel::onScreensaverShowClockChanged,
                                )
                            }
                            if (uiState.screensaverShowClock) {
                                item {
                                    TvSettingRow(
                                        title = stringResource(R.string.settings_screensaver_date),
                                        body = stringResource(R.string.settings_screensaver_date_hint),
                                        checked = uiState.screensaverShowDate,
                                        onCheckedChange = viewModel::onScreensaverShowDateChanged,
                                    )
                                }
                                item {
                                    TvSettingRow(
                                        title = stringResource(R.string.settings_screensaver_24h),
                                        body = stringResource(R.string.settings_screensaver_24h_hint),
                                        checked = uiState.screensaverUse24HourClock,
                                        onCheckedChange = viewModel::onScreensaver24HourClockChanged,
                                    )
                                }
                            }
                            if (uiState.screensaverModeKey == "media") {
                                item {
                                    TvSettingRow(
                                        title = stringResource(R.string.settings_screensaver_artwork),
                                        body = stringResource(R.string.settings_screensaver_artwork_hint),
                                        checked = uiState.screensaverUseArtwork,
                                        onCheckedChange = viewModel::onScreensaverUseArtworkChanged,
                                    )
                                }
                                if (uiState.screensaverUseArtwork) {
                                    item {
                                        SettingsGroup(
                                            title = stringResource(R.string.settings_screensaver_artwork_interval),
                                            subtitle = stringResource(R.string.settings_screensaver_artwork_interval_hint),
                                        ) {
                                            SettingsSelect(options = buildList {
                                                listOf(15, 30, 60, 120).forEach { seconds ->
                                                    add(SettingsSelectOption(
                                                        text = stringResource(R.string.settings_screensaver_seconds, seconds),
                                                        selected = uiState.screensaverArtworkIntervalSeconds == seconds,
                                                        onClick = { viewModel.onScreensaverArtworkIntervalChanged(seconds) },
                                                    ))
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_screensaver_dim),
                                    subtitle = stringResource(R.string.settings_screensaver_dim_hint),
                                ) {
                                    SettingsSelect(options = buildList {
                                        listOf(40, 55, 70, 85, 100).forEach { percent ->
                                            add(SettingsSelectOption(
                                                text = "$percent%",
                                                selected = uiState.screensaverDimPercent == percent,
                                                onClick = { viewModel.onScreensaverDimPercentChanged(percent) },
                                            ))
                                        }
                                    })
                                }
                            }
                            if (uiState.screensaverModeKey == "oled") {
                                item {
                                    Text(
                                        stringResource(R.string.settings_screensaver_oled_explanation),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        SettingsCategory.IPTV -> { item { IptvSettingsSection() }; item { LiveTvExtrasSettingsGroup() } }

                        SettingsCategory.TRAILERS -> {
                            item { com.ambient.tv.trailers.TrailerSettings(onConnected = { viewModel.refreshTrailerConfiguration() }) }
                        }
                        SettingsCategory.DLNA,
                        SettingsCategory.FILES,
                        SettingsCategory.WIREGUARD,
                        SettingsCategory.PLEX,
                        SettingsCategory.JELLYFIN,
                        SettingsCategory.EMBY,
                        SettingsCategory.TVHEADEND,
                        SettingsCategory.SPOTIFY,
                        SettingsCategory.YOUTUBE -> {
                            settingsSystemCategoryItems(
                                category = selectedCategory,
                                handheldUi = handheldUi,
                                uiState = uiState,
                                viewModel = viewModel,
                                focusContent = focusContent,
                                requestExit = requestExit,
                                onOpenFiles = onOpenFiles,
                                onOpenDlna = onOpenDlna,
                            )
                        }

                        SettingsCategory.PLAYBACK -> {
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_playback_backend),
                                    subtitle = MediaSystem.renamedAll(stringResource(R.string.settings_playback_backend_hint)),
                                ) {
                                    SettingsSelect(options = buildList {
                                        listOf("auto", "media3", "vlc", "mpv").forEach { backend ->
                                            val mpvUnavailable = backend == "mpv" && !com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable
                                            add(SettingsSelectOption(
                                                text = when (backend) {
                                                    "media3" -> stringResource(R.string.player_backend_media3)
                                                    "vlc" -> stringResource(R.string.player_backend_vlc)
                                                    "mpv" -> stringResource(R.string.player_backend_mpv)
                                                    else -> stringResource(R.string.player_backend_auto)
                                                },
                                                selected = uiState.playbackBackendKey == backend,
                                                onClick = { if (!mpvUnavailable) viewModel.onPlaybackBackendChanged(backend) },
                                                enabled = !mpvUnavailable,
                                            ))
                                        }
                                    })
                                    SettingsVideoAcceleration(uiState, viewModel, uiState.playbackBackendKey)
                                }
                            }
                            item {
                                TvActionButton(
                                    text = stringResource(R.string.settings_playback_overview),
                                    onClick = { playbackOverview = viewModel.savedPlaybackOverview() },
                                    fillWidth = true,
                                )
                            }
                            item {
                                TvActionButton(
                                    text = stringResource(R.string.live_profiles_title),
                                    onClick = { liveProfiles = viewModel.savedPlaybackOverview() },
                                    fillWidth = true,
                                )
                            }
                            if (uiState.playbackBackendKey == "mpv" && !com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable) {
                                item { Text(stringResource(R.string.player_backend_mpv_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (supportedDisplayModes.size > 1) {
                                item {
                                    SettingsGroup(
                                        title = stringResource(R.string.settings_display_adjustment_title),
                                        subtitle = stringResource(R.string.settings_display_adjustment_hint),
                                    ) {
                                        SettingsSelect(options = buildList {
                                            listOf("off", "always", "start_stop", "start").forEach { key ->
                                                add(SettingsSelectOption(
                                                    text = stringResource(when (key) {
                                                        "always" -> R.string.settings_display_adjustment_always
                                                        "start_stop" -> R.string.settings_display_adjustment_start_stop
                                                        "start" -> R.string.settings_display_adjustment_start
                                                        else -> R.string.settings_display_adjustment_off
                                                    }),
                                                    selected = uiState.displayRefreshAdjustmentKey == key,
                                                    onClick = { viewModel.onDisplayRefreshAdjustmentChanged(key) },
                                                ))
                                            }
                                        })
                                        TvSettingRow(
                                            title = stringResource(R.string.settings_display_change_notifications),
                                            body = stringResource(R.string.settings_display_change_notifications_hint),
                                            checked = uiState.displayChangeNotificationsEnabled,
                                            onCheckedChange = viewModel::onDisplayChangeNotificationsChanged,
                                        )
                                        TvSettingRow(
                                            title = stringResource(R.string.settings_display_allow_double),
                                            checked = uiState.allowDoubleRefreshRate,
                                            onCheckedChange = viewModel::onAllowDoubleRefreshRateChanged,
                                        )
                                        TvSettingRow(
                                            title = stringResource(R.string.settings_display_allow_3_2),
                                            checked = uiState.allowThreeTwoPulldown,
                                            onCheckedChange = viewModel::onAllowThreeTwoPulldownChanged,
                                        )
                                    }
                                }
                                item {
                                    SettingsGroup(
                                        title = stringResource(R.string.settings_display_mode_title),
                                        subtitle = stringResource(R.string.settings_display_mode_hint),
                                    ) {
                                        SettingsSelect(options = buildList {
                                            add(SettingsSelectOption(
                                                text = stringResource(R.string.settings_display_mode_auto_content),
                                                selected = uiState.preferredDisplayModeKey == com.ambient.tv.data.local.AppPreferences.DISPLAY_MODE_AUTO_CONTENT,
                                                onClick = { viewModel.onPreferredDisplayModeChanged(com.ambient.tv.data.local.AppPreferences.DISPLAY_MODE_AUTO_CONTENT) },
                                            ))
                                            add(SettingsSelectOption(
                                                text = stringResource(R.string.settings_display_mode_auto_display),
                                                selected = uiState.preferredDisplayModeKey == com.ambient.tv.data.local.AppPreferences.DISPLAY_MODE_AUTO_DISPLAY,
                                                onClick = { viewModel.onPreferredDisplayModeChanged(com.ambient.tv.data.local.AppPreferences.DISPLAY_MODE_AUTO_DISPLAY) },
                                            ))
                                            run {
                                                add(SettingsSelectOption(
                                                    text = stringResource(R.string.settings_display_mode_system),
                                                    selected = uiState.preferredDisplayModeKey == "system",
                                                    onClick = { viewModel.onPreferredDisplayModeChanged("system") },
                                                ))
                                            }
                                            supportedDisplayModes.forEach { mode ->
                                                add(SettingsSelectOption(
                                                    text = mode.label,
                                                    selected = uiState.preferredDisplayModeKey == mode.key,
                                                    onClick = { viewModel.onPreferredDisplayModeChanged(mode.key) },
                                                ))
                                            }
                                        })
                                        Text(stringResource(R.string.settings_display_modes_allowed), style = MaterialTheme.typography.titleSmall)
                                        Text(stringResource(R.string.settings_display_modes_allowed_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (supportedDisplayModes.isEmpty()) {
                                            Text(stringResource(R.string.settings_display_modes_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            SettingsSelect(multiple = true, options = buildList {
                                                run {
                                                    add(SettingsSelectOption(
                                                        text = stringResource(R.string.settings_display_modes_all),
                                                        selected = uiState.displayModeWhitelist.isEmpty(),
                                                        onClick = { viewModel.onDisplayModeWhitelistChanged(emptySet()) },
                                                    ))
                                                }
                                                supportedDisplayModes.forEach { mode ->
                                                    add(SettingsSelectOption(
                                                        text = mode.label,
                                                        selected = mode.key in uiState.displayModeWhitelist,
                                                        onClick = {
                                                            val updated = uiState.displayModeWhitelist.toMutableSet().apply {
                                                                if (!add(mode.key)) remove(mode.key)
                                                            }
                                                            viewModel.onDisplayModeWhitelistChanged(updated)
                                                        },
                                                    ))
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                            item { PlayerOsdSettingsGroup() }
                            item { LiveTvExtrasSettingsGroup() }
                        }

                        SettingsCategory.BACKGROUND -> {
                            item {
                                SettingsGroup {
                                    TvSettingRow(
                                        title = stringResource(R.string.settings_visible_background_video),
                                        body = stringResource(R.string.settings_visible_background_video_hint),
                                        checked = uiState.visibleBackgroundVideoPlayback,
                                        onCheckedChange = viewModel::onVisibleBackgroundVideoPlaybackChanged,
                                    )
                                    if (uiState.visibleBackgroundVideoPlayback) {
                                        Text(stringResource(R.string.settings_background_video_dim), style = MaterialTheme.typography.titleSmall)
                                        SettingsSelect(options = buildList {
                                            listOf(90, 75, 60).forEach { dim ->
                                                add(SettingsSelectOption(
                                                    text = stringResource(R.string.settings_background_video_visibility, 100 - dim),
                                                    selected = uiState.backgroundVideoDimPercent == dim,
                                                    onClick = { viewModel.onBackgroundVideoDimChanged(dim) },
                                                ))
                                            }
                                        })
                                        Text(stringResource(R.string.settings_background_video_dim_hint), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        SettingsCategory.SUBTITLES -> {
                            item {
                                SettingsGroup(title = stringResource(R.string.subtitle_policy_title), subtitle = stringResource(R.string.subtitle_policy_hint)) {
                                    Text(stringResource(R.string.subtitle_mode), style = MaterialTheme.typography.titleSmall)
                                    SettingsSelect(options = buildList {
                                        listOf("off" to R.string.player_subtitles_off, "forced" to R.string.subtitle_forced_only, "full" to R.string.subtitle_full).forEach { (key, label) ->
                                            add(SettingsSelectOption(text = stringResource(label), selected = uiState.subtitleModeKey == key, onClick = { viewModel.onSubtitleModeChanged(key) }))
                                        }
                                    })
                                    TvSettingRow(
                                        title = stringResource(R.string.subtitle_full_on_foreign_audio),
                                        body = stringResource(R.string.subtitle_full_on_foreign_audio_hint),
                                        checked = uiState.fullSubtitlesOnForeignAudio,
                                        onCheckedChange = viewModel::onFullSubtitlesOnForeignAudioChanged,
                                    )
                                    TvSettingRow(
                                        title = stringResource(R.string.subtitle_forced_in_ui_language),
                                        body = stringResource(R.string.subtitle_forced_in_ui_language_hint),
                                        checked = uiState.forcedSubtitlesInUiLanguage,
                                        onCheckedChange = viewModel::onForcedSubtitlesInUiLanguageChanged,
                                    )
                                    SubtitleLanguageChoice(uiState.subtitleLanguageKey, false, viewModel::onSubtitleLanguageChanged)
                                    Text(stringResource(R.string.subtitle_auto_hint), style = MaterialTheme.typography.bodySmall)
                                    SubtitleLanguageChoice(uiState.subtitleFallbackLanguageKey, true, viewModel::onSubtitleFallbackChanged)
                                }
                            }
                            item {
                                com.ambient.tv.ui.player.OpenSubtitlesSettingsGroup { title, subtitle, body ->
                                    SettingsGroup(title = title, subtitle = subtitle) { body() }
                                }
                            }
                        }

                        SettingsCategory.NEXT_UP -> {
                            item {
                                SettingsGroup(title = stringResource(R.string.settings_intro_skip), subtitle = stringResource(R.string.settings_intro_skip_hint)) {
                                    SettingsSelect(options = listOf(
                                        SettingsSelectOption(text = stringResource(R.string.intro_skip_off), selected = uiState.introSkipMode == "off", onClick = { viewModel.onIntroSkipModeChanged("off") }),
                                        SettingsSelectOption(text = stringResource(R.string.intro_skip_button), selected = uiState.introSkipMode == "button", onClick = { viewModel.onIntroSkipModeChanged("button") }),
                                        SettingsSelectOption(text = stringResource(R.string.intro_skip_auto), selected = uiState.introSkipMode == "auto", onClick = { viewModel.onIntroSkipModeChanged("auto") }),
                                    ))
                                }
                            }
                            item {
                                SettingsGroup(title = stringResource(R.string.settings_credits_skip), subtitle = stringResource(R.string.settings_credits_skip_hint)) {
                                    SettingsSelect(options = listOf(
                                        SettingsSelectOption(text = stringResource(R.string.credits_skip_off), selected = uiState.creditsSkipMode == "off", onClick = { viewModel.onCreditsSkipModeChanged("off") }),
                                        SettingsSelectOption(text = stringResource(R.string.credits_skip_button), selected = uiState.creditsSkipMode == "button", onClick = { viewModel.onCreditsSkipModeChanged("button") }),
                                        SettingsSelectOption(text = stringResource(R.string.credits_skip_auto), selected = uiState.creditsSkipMode == "auto", onClick = { viewModel.onCreditsSkipModeChanged("auto") }),
                                    ))
                                }
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_next_up_title),
                                    subtitle = MediaSystem.renamedAll(stringResource(R.string.settings_next_up_subtitle)),
                                ) {
                                    TvSettingRow(
                                        title = stringResource(R.string.settings_next_up_enabled),
                                        body = MediaSystem.renamedAll(stringResource(R.string.settings_next_up_enabled_hint)),
                                        checked = uiState.nextUpEnabled,
                                        onCheckedChange = viewModel::onNextUpEnabledChanged,
                                    )
                                    if (uiState.nextUpEnabled) {
                                        TvSettingRow(
                                            title = stringResource(R.string.settings_next_up_overlay),
                                            body = stringResource(R.string.settings_next_up_overlay_hint),
                                            checked = uiState.nextUpOverlayEnabled,
                                            onCheckedChange = viewModel::onNextUpOverlayEnabledChanged,
                                        )
                                        Text(stringResource(R.string.settings_next_up_lead_time), style = MaterialTheme.typography.titleSmall)
                                        SettingsSelect(options = buildList {
                                            listOf(10, 20, 30, 60).forEach { seconds ->
                                                add(SettingsSelectOption(
                                                    text = stringResource(R.string.settings_next_up_seconds, seconds),
                                                    selected = uiState.nextUpLeadSeconds == seconds,
                                                    onClick = { viewModel.onNextUpLeadSecondsChanged(seconds) },
                                                ))
                                            }
                                        })
                                        TvSettingRow(
                                            title = stringResource(R.string.settings_next_up_autoplay),
                                            body = MediaSystem.renamedAll(stringResource(R.string.settings_next_up_autoplay_hint)),
                                            checked = uiState.nextUpAutoplayEpisodes,
                                            onCheckedChange = viewModel::onNextUpAutoplayEpisodesChanged,
                                        )
                                        TvSettingRow(
                                            title = stringResource(R.string.settings_next_up_autoplay_collections),
                                            body = stringResource(R.string.settings_next_up_autoplay_collections_hint),
                                            checked = uiState.nextUpAutoplayCollections,
                                            onCheckedChange = viewModel::onNextUpAutoplayCollectionsChanged,
                                        )
                                        if (uiState.nextUpAutoplayEpisodes || uiState.nextUpAutoplayCollections) {
                                            Text(stringResource(R.string.settings_next_up_still_watching), style = MaterialTheme.typography.titleSmall)
                                            SettingsSelect(options = buildList {
                                                listOf(0, 2, 3, 5).forEach { advances ->
                                                    add(SettingsSelectOption(
                                                        text = if (advances == 0) stringResource(R.string.settings_next_up_off)
                                                        else stringResource(R.string.settings_next_up_advances, advances),
                                                        selected = uiState.nextUpStillWatchingEpisodes == advances,
                                                        onClick = { viewModel.onNextUpStillWatchingEpisodesChanged(advances) },
                                                    ))
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                        }

                        SettingsCategory.SEEK -> {
                            item { TvSettingRow(stringResource(R.string.settings_auto_display_version), stringResource(R.string.settings_auto_display_version_hint), uiState.autoSelectDisplayVersion, viewModel::onAutoSelectDisplayVersionToggled) }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_seek_jump),
                                    subtitle = stringResource(R.string.settings_seek_jump_hint),
                                ) {
                                    SettingsSelect(options = buildList {
                                        listOf(15, 30, 60, 90, 120).forEach { seconds ->
                                            add(SettingsSelectOption(
                                                text = stringResource(R.string.settings_seek_jump_seconds, seconds),
                                                selected = uiState.bigSeekJumpSeconds == seconds,
                                                onClick = { viewModel.onBigSeekJumpChanged(seconds) },
                                            ))
                                        }
                                    })
                                }
                            }
                        }

                        SettingsCategory.PHOTOS -> {
                            item { PhotoSettingsSection(uiState, viewModel) }
                        }

                        SettingsCategory.VISUALIZER -> {
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_audio_visualizer),
                                    subtitle = stringResource(R.string.settings_audio_visualizer_hint),
                                ) {
                                    SettingsSelect(options = buildList {
                                        listOf("projectm", "spectrum", "off").forEach { mode ->
                                            val unavailable = mode == "projectm" && !com.ambient.tv.ui.common.projectm.ProjectMAvailability.isAvailable
                                            add(SettingsSelectOption(
                                                text = when (mode) {
                                                    "projectm" -> stringResource(R.string.settings_audio_visualizer_projectm)
                                                    "spectrum" -> stringResource(R.string.settings_audio_visualizer_spectrum)
                                                    else -> stringResource(R.string.settings_audio_visualizer_off)
                                                },
                                                selected = uiState.audioVisualizerModeKey == mode,
                                                onClick = { if (!unavailable) viewModel.onAudioVisualizerModeChanged(mode) },
                                                enabled = !unavailable,
                                            ))
                                        }
                                    })
                                    if (!com.ambient.tv.ui.common.projectm.ProjectMAvailability.isAvailable) {
                                        Text(stringResource(R.string.settings_projectm_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (uiState.audioVisualizerModeKey == "projectm") {
                                item {
                                    SettingsGroup(title = stringResource(R.string.settings_projectm_title), subtitle = stringResource(R.string.settings_projectm_hint)) {
                                        TvSettingRow(
                                            title = stringResource(R.string.settings_projectm_show_preset_name),
                                            body = stringResource(R.string.settings_projectm_show_preset_name_hint),
                                            checked = uiState.projectmShowPresetName,
                                            onCheckedChange = viewModel::onProjectmShowPresetNameChanged,
                                        )
                                        Text(stringResource(R.string.settings_projectm_interval), style = MaterialTheme.typography.titleSmall)
                                        SettingsSelect(options = buildList {
                                            listOf(15, 30, 60, 120, 300).forEach { seconds ->
                                                add(SettingsSelectOption(
                                                    text = stringResource(R.string.settings_projectm_seconds, seconds),
                                                    selected = uiState.projectmPresetIntervalSeconds == seconds,
                                                    onClick = { viewModel.onProjectmPresetIntervalChanged(seconds) },
                                                ))
                                            }
                                        })
                                        Text(stringResource(R.string.settings_projectm_transition), style = MaterialTheme.typography.titleSmall)
                                        SettingsSelect(options = buildList {
                                            listOf(0, 1, 2, 5).forEach { seconds ->
                                                add(SettingsSelectOption(
                                                    text = stringResource(R.string.settings_projectm_seconds, seconds),
                                                    selected = uiState.projectmTransitionSeconds == seconds,
                                                    onClick = { viewModel.onProjectmTransitionChanged(seconds) },
                                                ))
                                            }
                                        })
                                        Text(stringResource(R.string.settings_projectm_quality), style = MaterialTheme.typography.titleSmall)
                                        SettingsSelect(options = buildList {
                                            listOf("economy", "balanced", "high").forEach { quality ->
                                                add(SettingsSelectOption(
                                                    text = when (quality) {
                                                        "economy" -> stringResource(R.string.settings_projectm_quality_economy)
                                                        "high" -> stringResource(R.string.settings_projectm_quality_high)
                                                        else -> stringResource(R.string.settings_projectm_quality_balanced)
                                                    },
                                                    selected = uiState.projectmQualityKey == quality,
                                                    onClick = { viewModel.onProjectmQualityChanged(quality) },
                                                ))
                                            }
                                        })
                                        Text(stringResource(R.string.settings_projectm_resolution), style = MaterialTheme.typography.titleSmall)
                                        val projectmDisplayLines = androidx.compose.ui.platform.LocalView.current.display?.mode
                                            ?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
                                        SettingsSelect(options = buildList {
                                            com.ambient.tv.ui.common.projectm.ProjectMRenderResolution.options(projectmDisplayLines).forEach { key ->
                                                add(SettingsSelectOption(
                                                    text = com.ambient.tv.ui.common.projectm.projectMResolutionLabel(key),
                                                    selected = com.ambient.tv.ui.common.projectm.ProjectMRenderResolution
                                                        .displayedKey(uiState.projectmResolutionKey, projectmDisplayLines) == key,
                                                    onClick = { viewModel.onProjectmResolutionChanged(key) },
                                                ))
                                            }
                                        })
                                        val projectmHdrSupported = com.ambient.tv.ui.common.projectm.rememberProjectMHdrSupported(uiState.uiHdrModeKey)
                                        Text(
                                            stringResource(if (projectmHdrSupported) R.string.settings_projectm_resolution_hint
                                                else R.string.settings_projectm_resolution_sdr_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (projectmHdrSupported && com.ambient.tv.ui.common.projectm.ProjectMRenderResolution.hdr4kChoiceVisible(android.os.Build.VERSION.SDK_INT, projectmDisplayLines)) {
                                            TvSettingRow(
                                                title = stringResource(R.string.settings_projectm_hdr_4k),
                                                body = stringResource(R.string.settings_projectm_hdr_4k_hint),
                                                checked = uiState.projectmHdr4kAllowed,
                                                onCheckedChange = viewModel::onProjectmHdr4kChanged,
                                            )
                                        }
                                        SettingsSelect(options = buildList {
                                            listOf("all", "favorites").forEach { category ->
                                                add(SettingsSelectOption(
                                                    text = if (category == "favorites") stringResource(R.string.settings_projectm_favorites_only) else stringResource(R.string.settings_projectm_all_presets),
                                                    selected = uiState.projectmPresetCategoryKey == category,
                                                    onClick = { viewModel.onProjectmCategoryChanged(category) },
                                                ))
                                            }
                                        })
                                    }
                                }
                            }
                        }

                        SettingsCategory.IMAGE -> {
                            item { SettingsImageDefaults(uiState, viewModel) }
                        }
                        SettingsCategory.AUDIO -> {
                            // One global volume for Plex, Jellyfin and Emby theme music.
                            if (uiState.plexThemeMusicEnabled || uiState.jellyfinThemeMusicEnabled || uiState.embyThemeMusicEnabled) themeMusicVolumeItems(uiState, viewModel)
                            item { SettingsAudioDefaults(uiState, viewModel) }
                            item {
                                SettingsGroup(title = stringResource(R.string.settings_audio_tracks_language)) {
                                    AudioLanguageChoice(uiState.audioLanguageKey, viewModel::onAudioLanguageChanged)
                                }
                            }
                        }

                        SettingsCategory.HOME -> {
                            // Shown once the cross-system row is in the editor list.
                            if (uiState.homeRows.any { it.system == HomeRowDefinition.SYSTEM_ALL }) item {
                                var included by remember { mutableStateOf(viewModel.allContinueSystems()) }
                                SettingsGroup(
                                    title = stringResource(R.string.settings_home_all_continue_systems),
                                    subtitle = stringResource(R.string.settings_home_all_continue_systems_hint),
                                ) {
                                    listOf(SettingsCategory.PLEX to HomeRowDefinition.SYSTEM_PLEX,
                                        SettingsCategory.JELLYFIN to HomeRowDefinition.SYSTEM_JELLYFIN,
                                        SettingsCategory.EMBY to HomeRowDefinition.SYSTEM_EMBY,
                                    ).filter { (category, _) -> sourceConfigured(category, uiState) }.forEach { (category, system) ->
                                        TvSettingRow(
                                            title = settingsCategoryTitle(category),
                                            checked = system in included,
                                            onCheckedChange = { on ->
                                                included = if (on) included + system else included - system
                                                viewModel.setAllContinueSystem(system, on)
                                            },
                                        )
                                    }
                                }
                            }
                            if (viewModel.launcherIntegrationSupported) item {
                                var enabled by remember { mutableStateOf(viewModel.launcherIntegrationEnabled()) }
                                TvSettingRow(
                                    title = stringResource(R.string.settings_launcher_rows),
                                    body = stringResource(R.string.settings_launcher_rows_hint),
                                    checked = enabled,
                                    onCheckedChange = { enabled = it; viewModel.setLauncherIntegrationEnabled(it) },
                                )
                            }
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_home_editor_title),
                                    subtitle = stringResource(R.string.settings_home_editor_hint),
                                ) {
                                    uiState.homeRows.forEachIndexed { index, row ->
                                        HomeRowEditorItem(
                                            row = row,
                                            canMoveUp = index > 0,
                                            canMoveDown = index < uiState.homeRows.lastIndex,
                                            onUpdate = viewModel::updateHomeRow,
                                            onMove = viewModel::moveHomeRow,
                                            onRemove = {
                                                focusContent()
                                                viewModel.removeHomeRow(row.id)
                                            },
                                        )
                                    }
                                    if (uiState.homeRows.isEmpty()) Text(stringResource(R.string.settings_home_editor_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            item {
                                SettingsGroup(title = stringResource(R.string.settings_home_add_row), subtitle = stringResource(R.string.settings_home_add_row_hint)) {
                                    HomeRowAddButtons(uiState, viewModel::addHomeRow, viewModel::pickHomeGenre, viewModel::closeHomeGenrePicker)
                                }
                            }
                            item {
                                // LazyColumn items stack children in a Box; without a Column the button covers the hint.
                                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.settings_recent_tv_hint), style = MaterialTheme.typography.bodySmall)
                                    TvActionButton(stringResource(R.string.settings_recent_tv_clear), viewModel::clearRecentTvChannels)
                                }
                            }
                        }

                        SettingsCategory.LIBRARY -> {
                            item { SettingsSubheader(stringResource(R.string.settings_assets_title)) }
                            item {
                                TvSettingRow(
                                    title = stringResource(R.string.settings_high_quality_assets),
                                    body = stringResource(R.string.settings_high_quality_assets_hint),
                                    checked = uiState.highQualityAssetsEnabled,
                                    onCheckedChange = viewModel::onHighQualityAssetsChanged,
                                )
                            }
                            item { SettingsSubheader(stringResource(R.string.settings_layout_title)) }
                            item { com.ambient.tv.ui.common.RightPanelValueSlider(title = stringResource(R.string.settings_grid_columns), value = uiState.libraryGridColumns, valueRange = 4..12, onValueChange = viewModel::onLibraryGridColumnsChanged) }
                            item { com.ambient.tv.ui.common.RightPanelValueSlider(title = stringResource(R.string.settings_list_rows), value = uiState.libraryListRows, valueRange = 4..10, onValueChange = viewModel::onLibraryListRowsChanged) }
                        }

                        SettingsCategory.ADVANCED -> {
                            // Hub hides this page; fromKey("advanced") and search open Spotify instead.
                            item {
                                SettingsGroup(
                                    title = stringResource(R.string.settings_advanced_title),
                                    subtitle = stringResource(R.string.settings_advanced_developer_pointer),
                                ) {
                                    Text(
                                        stringResource(R.string.settings_advanced_developer_pointer_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.settings_advanced_open_spotify_developer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        SettingsCategory.BACKUP -> {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TvActionButton(
                                            text = if (uiState.isExportingSettings && !exportWithCredentials) stringResource(R.string.settings_exporting) else stringResource(R.string.settings_export_safe),
                                            onClick = { askSafeExportPassphrase = true },
                                            enabled = !uiState.isExportingSettings && !uiState.isImportingSettings,
                                            loading = uiState.isExportingSettings && !exportWithCredentials,
                                            modifier = Modifier.weight(1f),
                                            fillWidth = true,
                                        )
                                        TvActionButton(
                                            text = stringResource(R.string.settings_export_with_credentials),
                                            onClick = { askExportPassphrase = true },
                                            enabled = !uiState.isExportingSettings && !uiState.isImportingSettings,
                                            loading = uiState.isExportingSettings && exportWithCredentials,
                                            modifier = Modifier.weight(1f),
                                            fillWidth = true,
                                        )
                                    }
                                    TvActionButton(
                                        text = if (uiState.isImportingSettings) stringResource(R.string.settings_importing) else stringResource(R.string.settings_import),
                                        onClick = { requestExit { launchBackupPicker { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) } } },
                                        enabled = !uiState.isExportingSettings && !uiState.isImportingSettings,
                                        loading = uiState.isImportingSettings,
                                        modifier = Modifier.fillMaxWidth(),
                                        fillWidth = true,
                                    )
                                }
                            }
                            item { Text(stringResource(R.string.settings_export_saved_hint), style = MaterialTheme.typography.bodySmall) }
                            item { Text(stringResource(R.string.settings_import_draft_hint), style = MaterialTheme.typography.bodySmall) }
                            item { SettingsSubheader(stringResource(R.string.settings_backup_title)) }
                            item { Text(stringResource(R.string.settings_backup_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }

                        SettingsCategory.DIAGNOSTICS -> {
                            item {
                                var enabled by remember { mutableStateOf(viewModel.diagnosticLogEnabled()) }
                                var sizeBytes by remember { mutableStateOf(viewModel.diagnosticLogSizeBytes()) }
                                SettingsGroup(
                                    title = stringResource(R.string.diagnostics_log_title),
                                    subtitle = stringResource(R.string.diagnostics_log_hint),
                                ) {
                                    TvSettingRow(
                                        title = stringResource(R.string.diagnostics_log_enabled),
                                        body = stringResource(R.string.diagnostics_log_enabled_hint),
                                        checked = enabled,
                                        onCheckedChange = {
                                            enabled = it
                                            viewModel.setDiagnosticLogEnabled(it)
                                            sizeBytes = viewModel.diagnosticLogSizeBytes()
                                        },
                                    )
                                    Text(
                                        stringResource(R.string.diagnostics_log_size, android.text.format.Formatter.formatShortFileSize(LocalContext.current, sizeBytes)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TvActionButton(
                                            text = stringResource(R.string.diagnostics_export),
                                            onClick = {
                                                // Android TV often has no document picker: save to the app folder instead.
                                                val launched = runCatching { diagnosticExportLauncher.launch(viewModel.diagnosticZipName()) }.isSuccess
                                                if (!launched) viewModel.exportDiagnosticLog(null)
                                            },
                                            modifier = Modifier.weight(1f),
                                            fillWidth = true,
                                        )
                                        TvActionButton(
                                            text = stringResource(R.string.diagnostics_clear),
                                            onClick = { viewModel.clearDiagnosticLog(); sizeBytes = 0L },
                                            modifier = Modifier.weight(1f),
                                            fillWidth = true,
                                        )
                                    }
                                }
                            }
                            item {
                                Text(
                                    stringResource(R.string.diagnostics_privacy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        SettingsCategory.ABOUT -> {
                            item { AboutAppCard() }
                            item { SettingsSubheader(stringResource(R.string.settings_about_playback)) }
                            items(aboutPlaybackComponents(), key = { "about-playback-${it.name}" }) { component ->
                                AboutComponentCard(component)
                            }
                            item { SettingsSubheader(stringResource(R.string.settings_about_services)) }
                            items(aboutServiceComponents(), key = { "about-service-${it.name}" }) { component ->
                                AboutComponentCard(component)
                            }
                        }
                    }
                }
                if (listOverflow) {
                    OverlayLazyScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), contentFocus = scrollbarContentFocus)
                }
            }
        }
    }

    playbackOverview?.let { snapshot ->
        PlaybackOverviewDialog(snapshot, onDismiss = { playbackOverview = null })
    }
    liveProfiles?.let { snapshot ->
        LiveTvProfileDialog(snapshot, save = viewModel::saveLiveResolutionProfile,
            onDismiss = { liveProfiles = null })
    }

    accentPickerRole?.let { role ->
        val playback = role == AccentColorRole.PLAYBACK
        AccentColorPickerDialog(
            value = if (playback) com.ambient.tv.ui.theme.normalizedCustomPlaybackAccentHex(
                uiState.customPlaybackAccentHex, uiState.customAccentHex,
            ) else uiState.customAccentHex,
            onValueChange = if (playback) viewModel::onCustomPlaybackAccentHexChanged else viewModel::onCustomAccentHexChanged,
            onDismiss = { accentPickerRole = null },
            title = stringResource(if (playback) R.string.settings_playback_accent else R.string.settings_primary_accent),
        )
    }

    if (showExitConfirmation) {
        AppDialog(
            onDismiss = { showExitConfirmation = false },
            title = stringResource(R.string.settings_exit_app_confirm_title),
            hint = stringResource(R.string.settings_exit_app_confirm_hint),
            scrollable = false,
            footer = {
                TvActionButton(
                    text = stringResource(R.string.settings_link_cancel),
                    onClick = { showExitConfirmation = false },
                )
                TvActionButton(
                    text = stringResource(R.string.settings_exit_app_action),
                    icon = Icons.Filled.PowerSettingsNew,
                    onClick = onExitApp,
                )
            },
        )
    }
}



private data class AboutComponent(
    val badge: String,
    val name: String,
    val version: String,
    val roleRes: Int,
    val source: String,
    val ref: String? = null,
)

private fun String.shortRef(): String = takeIf { it.isNotBlank() }?.take(9).orEmpty()

private fun aboutPlaybackComponents() = listOf(
    AboutComponent("VLC", "libVLC", BuildConfig.LIBVLC_VERSION, R.string.settings_about_role_video_engine, "VideoLAN / Maven Central"),
    AboutComponent("mpv", "mpv-android", BuildConfig.MPV_ANDROID_VERSION, R.string.settings_about_role_video_engine, "mpv-android / GitHub", BuildConfig.MPV_ANDROID_REF.shortRef()),
    AboutComponent("M3", "AndroidX Media3", BuildConfig.MEDIA3_VERSION, R.string.settings_about_role_android_player, "AndroidX / Google"),
    AboutComponent("pM", "projectM", BuildConfig.PROJECTM_VERSION, R.string.settings_about_role_visualizer, "projectM-visualizer / GitHub"),
    AboutComponent("SVP", "Open-SVPFlow", BuildConfig.SVPFLOW_VERSION, R.string.settings_about_role_motion, "Z1xus/open-svpflow / GitHub", BuildConfig.SVPFLOW_REF.shortRef()),
)

private fun aboutServiceComponents() = listOf(
    AboutComponent("ST", "SmartTube", BuildConfig.SMARTTUBE_VERSION, R.string.settings_about_role_youtube, "yuliskov / GitHub", BuildConfig.SMARTTUBE_REF.shortRef()),
    AboutComponent("MSC", "MediaServiceCore", BuildConfig.MEDIA_SERVICE_CORE_REF.shortRef(), R.string.settings_about_role_youtube_core, "yuliskov / GitHub", BuildConfig.MEDIA_SERVICE_CORE_REF.shortRef()),
    AboutComponent("SM", "SharedModules", BuildConfig.SHARED_MODULES_REF.shortRef(), R.string.settings_about_role_shared, "yuliskov / GitHub", BuildConfig.SHARED_MODULES_REF.shortRef()),
    AboutComponent("SP", "Spotify Rusty", BuildConfig.SPOTIFY_RUSTY_VERSION, R.string.settings_about_role_spotify, "SerafiniJose/rusty / GitHub"),
)

@Composable
private fun AboutAppCard() {
    var focused by remember { mutableStateOf(false) }
    SettingsGroup {
        DeviceActionRow(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .border(
                    if (focused) 2.dp else 0.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(10.dp),
                ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BrandMark(size = 46.dp, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BrandWordmark(height = 26.dp)
                Text(
                    stringResource(R.string.settings_about_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.settings_about_app_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutComponentCard(component: AboutComponent) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    DeviceActionRow(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f), shape)
            .border(
                if (focused) 2.dp else 1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.90f else 0.07f),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(component.badge, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DeviceActionRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(component.name, style = MaterialTheme.typography.titleMedium)
                Text(component.version, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(component.roleRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_about_source, component.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                component.ref?.takeIf { it.isNotBlank() && it != component.version }?.let { ref ->
                    Text(stringResource(R.string.settings_about_commit, ref), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LanguageChoiceRow(
    selected: String,
    onSelected: (String) -> Unit,
) {
    SettingsSelect(options = buildList {
        run {
            add(SettingsSelectOption(
                text = stringResource(R.string.settings_language_auto),
                selected = selected == "auto",
                onClick = { onSelected("auto") },
            ))
        }
        run {
            add(SettingsSelectOption(
                text = stringResource(R.string.settings_language_german),
                selected = selected == "de",
                onClick = { onSelected("de") },
            ))
        }
        run {
            add(SettingsSelectOption(
                text = stringResource(R.string.settings_language_english),
                selected = selected == "en",
                onClick = { onSelected("en") },
            ))
        }
        if (selected !in setOf("auto", "de", "en")) {
            run {
                add(SettingsSelectOption(
                    text = selected.uppercase(),
                    selected = true,
                    onClick = { onSelected(selected) },
                ))
            }
        }
    })
}

@Composable
private fun SettingsPaneHeader(category: SettingsCategory) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(settingsCategoryTitle(category), style = MaterialTheme.typography.headlineSmall)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .size(width = 52.dp, height = 2.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
        )
    }
}

/** Automatic wake can only be switched while a usable MAC address is entered for that system. */
@Composable
internal fun AutoWakeRow(title: String, hint: String?, macAddress: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val macValid = remember(macAddress) { isUsableWakeMac(macAddress) }
    TvSettingRow(
        title,
        if (macValid) hint else stringResource(R.string.settings_auto_wake_needs_mac),
        checked && macValid,
        onCheckedChange,
        enabled = macValid,
    )
}

internal fun isUsableWakeMac(macAddress: String): Boolean =
    runCatching { com.ambient.tv.data.files.WakeOnLan.packet(macAddress.trim()) }.isSuccess

@Composable
internal fun SettingsSubheader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
internal fun SettingsGroup(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** Add-row chips for the rows a media server ranks itself; the genre chip opens the genre picker. */
private fun rankedHomeSources(context: android.content.Context, system: String): List<Pair<String, Pair<String, String>>> {
    val mediaSystem = MediaSystem.fromKey(system)
    val name = mediaSystem?.customName ?: mediaSystem?.let { context.getString(it.label) } ?: system
    return listOfNotNull(
        HomeRowDefinition.CONTENT_TOP_UNWATCHED,
        HomeRowDefinition.CONTENT_BECAUSE_WATCHED,
        HomeRowDefinition.CONTENT_WATCHED,
        HomeRowDefinition.CONTENT_HUBS.takeIf { system == HomeRowDefinition.SYSTEM_PLEX },
    ).map { key -> HomeRowDefinition(system = system, contentKey = key).displayTitle(context) to (system to key) } +
        (context.getString(R.string.settings_home_source_top_genre, name) to (system to HomeRowDefinition.CONTENT_TOP_GENRE))
}

@Composable
private fun homeRowSystemGroupTitle(system: String): String {
    MediaSystem.fromKey(system)?.let { return systemName(it) }
    return when (system) {
        HomeRowDefinition.SYSTEM_PHOTOS -> stringResource(R.string.settings_photos_title)
        HomeRowDefinition.SYSTEM_ALL -> stringResource(R.string.settings_home_group_all)
        else -> system
    }
}

/** Whether this add-chip should be offered: system ready, and not already in the editor list.
 *  The "Top by genre …" opener stays visible so further genres can still be picked. */
private fun homeRowAddChipVisible(system: String, contentKey: String, state: SettingsUiState): Boolean {
    if (!homeRowSourceAddable(system, contentKey, state)) return false
    if (contentKey == HomeRowDefinition.CONTENT_TOP_GENRE) return true
    return !homeRowAlreadyPresent(system, contentKey, state)
}

@Composable
private fun HomeRowAddButtons(
    state: SettingsUiState,
    onAdd: (String, String) -> Unit,
    onPickGenre: (String) -> Unit,
    onCloseGenrePicker: () -> Unit,
) {
    val context = LocalContext.current
    val mediaServers = listOf(HomeRowDefinition.SYSTEM_PLEX, HomeRowDefinition.SYSTEM_JELLYFIN, HomeRowDefinition.SYSTEM_EMBY)
    val base = listOf(
        stringResource(R.string.settings_home_source_all_continue) to (HomeRowDefinition.SYSTEM_ALL to "continue"),
        stringResource(R.string.settings_home_source_plex_continue) to (HomeRowDefinition.SYSTEM_PLEX to "continue"),
        stringResource(R.string.settings_home_source_plex_recent) to (HomeRowDefinition.SYSTEM_PLEX to "recent"),
        stringResource(R.string.settings_home_source_plex_collections) to (HomeRowDefinition.SYSTEM_PLEX to "collections"),
        stringResource(R.string.settings_home_source_plex_libraries) to (HomeRowDefinition.SYSTEM_PLEX to "libraries"),
        stringResource(R.string.settings_home_source_jellyfin_continue) to (HomeRowDefinition.SYSTEM_JELLYFIN to "continue"),
        stringResource(R.string.settings_home_source_jellyfin_recent) to (HomeRowDefinition.SYSTEM_JELLYFIN to "recent"),
        stringResource(R.string.settings_home_source_jellyfin_collections) to (HomeRowDefinition.SYSTEM_JELLYFIN to "collections"),
        stringResource(R.string.settings_home_source_jellyfin_libraries) to (HomeRowDefinition.SYSTEM_JELLYFIN to "libraries"),
        stringResource(R.string.settings_home_source_emby_continue) to (HomeRowDefinition.SYSTEM_EMBY to "continue"),
        stringResource(R.string.settings_home_source_emby_recent) to (HomeRowDefinition.SYSTEM_EMBY to "recent"),
        stringResource(R.string.settings_home_source_emby_collections) to (HomeRowDefinition.SYSTEM_EMBY to "collections"),
        stringResource(R.string.settings_home_source_emby_libraries) to (HomeRowDefinition.SYSTEM_EMBY to "libraries"),
        *listOf("plex", "jellyfin", "emby").flatMap { system -> listOf("live", "recent_channels").map { key ->
            HomeRowDefinition(system = system, contentKey = key).displayTitle(context) to (system to key)
        } }.toTypedArray(),
        stringResource(R.string.settings_home_source_tvh_recent) to (HomeRowDefinition.SYSTEM_TVHEADEND to "recent_channels"),
        stringResource(R.string.settings_home_source_tvh_live) to (HomeRowDefinition.SYSTEM_TVHEADEND to "live"),
        stringResource(R.string.settings_home_source_tvh_guide) to (HomeRowDefinition.SYSTEM_TVHEADEND to "guide"),
        stringResource(R.string.settings_home_source_tvh_recordings) to (HomeRowDefinition.SYSTEM_TVHEADEND to "recordings"),
        stringResource(R.string.settings_home_source_tvh_timers) to (HomeRowDefinition.SYSTEM_TVHEADEND to "timers"),
        stringResource(R.string.settings_home_source_youtube_home) to (HomeRowDefinition.SYSTEM_YOUTUBE to "home"),
        stringResource(R.string.settings_home_source_youtube_subscriptions) to (HomeRowDefinition.SYSTEM_YOUTUBE to "subscriptions"),
        stringResource(R.string.settings_home_source_youtube_history) to (HomeRowDefinition.SYSTEM_YOUTUBE to "history"),
        stringResource(R.string.settings_home_source_youtube_playlists) to (HomeRowDefinition.SYSTEM_YOUTUBE to "playlists"),
        stringResource(R.string.nav_youtube_shorts) to (HomeRowDefinition.SYSTEM_YOUTUBE to "shorts"),
        stringResource(R.string.nav_youtube_music) to (HomeRowDefinition.SYSTEM_YOUTUBE to "music"),
        stringResource(R.string.nav_youtube_news) to (HomeRowDefinition.SYSTEM_YOUTUBE to "news"),
        stringResource(R.string.nav_youtube_gaming) to (HomeRowDefinition.SYSTEM_YOUTUBE to "gaming"),
        stringResource(R.string.nav_youtube_live) to (HomeRowDefinition.SYSTEM_YOUTUBE to "live"),
        stringResource(R.string.nav_youtube_kids) to (HomeRowDefinition.SYSTEM_YOUTUBE to "kids"),
        stringResource(R.string.settings_home_source_spotify_home) to (HomeRowDefinition.SYSTEM_SPOTIFY to "home"),
        stringResource(R.string.settings_home_source_spotify_liked) to (HomeRowDefinition.SYSTEM_SPOTIFY to "liked"),
        stringResource(R.string.settings_home_source_spotify_playlists) to (HomeRowDefinition.SYSTEM_SPOTIFY to "playlists"),
        stringResource(R.string.settings_home_source_spotify_albums) to (HomeRowDefinition.SYSTEM_SPOTIFY to "albums"),
        stringResource(R.string.settings_home_source_spotify_artists) to (HomeRowDefinition.SYSTEM_SPOTIFY to "artists"),
        stringResource(R.string.settings_home_source_spotify_now_playing) to (HomeRowDefinition.SYSTEM_SPOTIFY to "now_playing"),
        stringResource(R.string.settings_home_source_photos_recent) to (HomeRowDefinition.SYSTEM_PHOTOS to "recent"),
        stringResource(R.string.trailers_cinema) to (HomeRowDefinition.SYSTEM_TRAILERS to "CINEMA"),
        stringResource(R.string.trailers_upcoming) to (HomeRowDefinition.SYSTEM_TRAILERS to "UPCOMING"),
        stringResource(R.string.trailers_movies) to (HomeRowDefinition.SYSTEM_TRAILERS to "MOVIES"),
        stringResource(R.string.trailers_series) to (HomeRowDefinition.SYSTEM_TRAILERS to "SERIES"),
    ).filter { (_, source) -> homeRowAddChipVisible(source.first, source.second, state) }
        .map { (label, source) -> (MediaSystem.fromKey(source.first)?.renamed(label) ?: label) to source }
    val options = buildList {
        mediaServers.forEach { system ->
            addAll(base.filter { it.second.first == system })
            if (homeRowSourceAddable(system, HomeRowDefinition.CONTENT_WATCHED, state)) {
                addAll(
                    rankedHomeSources(context, system).filter { (_, source) ->
                        homeRowAddChipVisible(source.first, source.second, state)
                    },
                )
            }
        }
        addAll(base.filter { it.second.first !in mediaServers })
    }
    if (options.isEmpty()) {
        Text(
            stringResource(R.string.settings_home_add_row_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // Short per-system groups instead of one mega horizontal row.
        val systemOrder = listOf(HomeRowDefinition.SYSTEM_ALL) + mediaServers + listOf(
            HomeRowDefinition.SYSTEM_TVHEADEND,
            HomeRowDefinition.SYSTEM_YOUTUBE,
            HomeRowDefinition.SYSTEM_SPOTIFY,
            HomeRowDefinition.SYSTEM_PHOTOS,
            HomeRowDefinition.SYSTEM_TRAILERS,
        )
        val grouped = options.groupBy { it.second.first }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            systemOrder.filter { it in grouped }.forEach { system ->
                val chips = grouped.getValue(system)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = homeRowSystemGroupTitle(system),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chips) { (label, source) ->
                            val genreChip = source.second == HomeRowDefinition.CONTENT_TOP_GENRE
                            com.ambient.tv.ui.common.TvChip(
                                text = label,
                                selected = genreChip && state.homeGenrePicker?.system == source.first,
                                onClick = {
                                    if (genreChip) onPickGenre(source.first) else onAdd(source.first, source.second)
                                },
                            )
                        }
                    }
                }
            }
            // The engine is asked for the available feeds in the background. Without this the
            // SmartTube group is simply missing for a moment, which reads like a missing feature.
            if (state.youtubeEnabled && state.youtubeAvailable && !state.youtubeAuthKnown) {
                Text(
                    stringResource(R.string.settings_home_source_youtube_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    state.homeGenrePicker?.let { picker ->
        val mediaSystem = MediaSystem.fromKey(picker.system)
        Text(
            stringResource(R.string.settings_home_genre_pick, mediaSystem?.let { systemName(it) } ?: picker.system),
            style = MaterialTheme.typography.titleSmall,
        )
        val availableGenres = picker.genres.filter { genre ->
            !homeRowAlreadyPresent(picker.system, HomeRowDefinition.topGenre(genre), state)
        }
        when {
            picker.loading -> Text(stringResource(R.string.settings_home_genre_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            picker.genres.isEmpty() -> Text(stringResource(R.string.settings_home_genre_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            availableGenres.isEmpty() -> Text(
                stringResource(R.string.settings_home_genre_all_added),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> SettingsSelect(options = availableGenres.map { genre ->
                SettingsSelectOption(
                    text = genre,
                    selected = false,
                    onClick = { onAdd(picker.system, HomeRowDefinition.topGenre(genre)) },
                )
            })
        }
        TvActionButton(text = stringResource(R.string.settings_home_genre_done), onClick = onCloseGenrePicker)
    }
}

@Composable
private fun HomeRowEditorItem(
    row: HomeRowDefinition,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (HomeRowDefinition) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val system = MediaSystem.fromKey(row.system)
    val sourceName = system?.customName ?: system?.brand ?: homeRowSystemGroupTitle(row.system)
    val sourceTitle = row.copy(title = "").displayTitle(context).removePrefix("$sourceName: ")
    SettingsGroup(title = row.displayTitle(context), subtitle = "$sourceName · $sourceTitle") {
        TvSettingRow(title = stringResource(R.string.settings_home_visible), checked = row.enabled, onCheckedChange = { onUpdate(row.copy(enabled = it)) })
        TvTextField(
            value = row.title,
            onValueChange = { onUpdate(row.copy(title = it.take(80))) },
            label = stringResource(R.string.settings_home_custom_title),
            modifier = Modifier.fillMaxWidth(),
        )
        // Live channel rows use a compact logo/program card. Do not offer ignored poster/list controls.
        if (row.contentKey !in setOf("live", "recent_channels")) {
            Text(stringResource(R.string.settings_home_display))
            SettingsSelect(options = HomeRowDefinition.DISPLAY_MODES.map { mode ->
                SettingsSelectOption(
                    text = when (mode) {
                        HomeRowDefinition.DISPLAY_POSTER -> stringResource(R.string.settings_home_display_poster)
                        HomeRowDefinition.DISPLAY_LIST -> stringResource(R.string.settings_home_display_list)
                        else -> stringResource(R.string.settings_home_display_landscape)
                    },
                    selected = row.displayMode == mode,
                    onClick = { onUpdate(row.copy(displayMode = mode)) },
                )
            })
        }
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvActionButton(text = stringResource(R.string.settings_home_move_up), onClick = { onMove(row.id, -1) }, enabled = canMoveUp)
            TvActionButton(text = stringResource(R.string.settings_home_move_down), onClick = { onMove(row.id, 1) }, enabled = canMoveDown)
            TvActionButton(text = stringResource(R.string.settings_home_remove), onClick = onRemove)
        }
    }
}

@Composable
private fun accentLabel(key: String): String = when (key) {
    "gold" -> stringResource(R.string.accent_gold)
    "orange" -> stringResource(R.string.accent_orange)
    "blue" -> stringResource(R.string.accent_blue)
    "teal" -> stringResource(R.string.accent_teal)
    "green" -> stringResource(R.string.accent_green)
    "purple" -> stringResource(R.string.accent_purple)
    "pink" -> stringResource(R.string.accent_pink)
    "red" -> stringResource(R.string.accent_red)
    "artwork" -> stringResource(R.string.accent_artwork)
    "custom" -> stringResource(R.string.accent_custom)
    else -> key
}


@Composable
private fun themeModeLabel(key: String): String = when (key) {
    "oled" -> stringResource(R.string.theme_oled)
    "dim" -> stringResource(R.string.theme_dim)
    else -> stringResource(R.string.theme_dark)
}

@Composable
private fun PerformanceModeSelect(
    selected: String,
    onSelected: (String) -> Unit,
) {
    SettingsSelect(options = listOf("auto", "balanced", "maximum", "low").map { mode ->
        SettingsSelectOption(
            text = performanceModeLabel(mode),
            selected = selected == mode,
            onClick = { onSelected(mode) },
        )
    })
}

@Composable
private fun performanceModeLabel(key: String): String = when (key) {
    "maximum" -> stringResource(R.string.settings_performance_maximum)
    "balanced" -> stringResource(R.string.settings_performance_balanced)
    "low" -> stringResource(R.string.settings_performance_low)
    else -> stringResource(R.string.settings_performance_auto)
}

@Composable
private fun homeHeroStyleLabel(key: String): String = when (key) {
    HomeHeroStyle.SWEEP -> stringResource(R.string.settings_home_hero_sweep)
    HomeHeroStyle.ARTWORK_COLOR -> stringResource(R.string.settings_home_hero_artwork_color)
    HomeHeroStyle.DEPTH -> stringResource(R.string.settings_home_hero_depth)
    HomeHeroStyle.AURORA -> stringResource(R.string.settings_home_hero_aurora)
    HomeHeroStyle.CINEMA -> stringResource(R.string.settings_home_hero_cinema)
    HomeHeroStyle.PRISM -> stringResource(R.string.settings_home_hero_prism)
    else -> stringResource(R.string.settings_home_hero_static)
}

@Composable
private fun homeHeroSpreadLabel(key: String): String = when (key) {
    HomeHeroStyle.FOCUSED -> stringResource(R.string.settings_home_hero_spread_focused)
    HomeHeroStyle.WIDE -> stringResource(R.string.settings_home_hero_spread_wide)
    else -> stringResource(R.string.settings_home_hero_spread_soft)
}

@Composable
private fun AppearancePreview(uiScale: Float, themeModeKey: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.84f), RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.settings_live_preview), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_preview_summary, (uiScale * 100).roundToInt(), themeModeLabel(themeModeKey)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.ambient.tv.ui.common.MetaBadge(text = "4K", emphasized = true)
        }
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp, 34.dp).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
            Column {
                Text(stringResource(R.string.settings_preview_title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.settings_preview_body), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun playbackLanguageNames(): List<Pair<String, String>> {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return remember(locale) { com.ambient.tv.data.local.LanguagePreferences.playbackLanguageChoices(locale) }
}

/** Every ISO 639-1 language in the UI locale, plus Automatic and Original language. */
@Composable
private fun AudioLanguageChoice(selected: String, onSelected: (String) -> Unit) {
    val names = playbackLanguageNames()
    val options = listOf(
        "auto" to stringResource(R.string.settings_language_auto),
        com.ambient.tv.data.local.LanguagePreferences.ORIGINAL to stringResource(R.string.settings_language_original),
    ) + names + listOfNotNull(selected.takeIf { key -> key != "auto" && key != "original" && names.none { it.first == key } }?.let { it to it.uppercase() })
    com.ambient.tv.ui.common.PlayerDropUpSelector(
        label = "",
        value = options.first { it.first == selected }.second,
        options = options, selectedKey = selected, onSelected = onSelected, dropUp = false,
    )
}

@Composable
private fun SubtitleLanguageChoice(selected: String, fallback: Boolean, onSelected: (String) -> Unit) {
    val names = playbackLanguageNames()
    val first = if (fallback) "none" to stringResource(R.string.subtitle_no_fallback) else "auto" to stringResource(R.string.settings_language_auto)
    val options = listOf(first) + names + listOfNotNull(selected.takeIf { key -> key != first.first && names.none { it.first == key } }?.let { it to it.uppercase() })
    com.ambient.tv.ui.common.PlayerDropUpSelector(
        label = stringResource(if (fallback) R.string.subtitle_fallback else R.string.subtitle_language),
        value = options.first { it.first == selected }.second,
        options = options, selectedKey = selected, onSelected = onSelected,
    )
}

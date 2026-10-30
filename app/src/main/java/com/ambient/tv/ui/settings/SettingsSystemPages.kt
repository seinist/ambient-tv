package com.ambient.tv.ui.settings

import com.ambient.tv.ui.mobile.DeviceActionRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.systemHomeRowTypes
import com.ambient.tv.domain.model.DiscoveredPlexServer
import com.ambient.tv.domain.model.EmbyServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsGroupHeader
import com.ambient.tv.ui.common.SettingsItemIcon
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.librarySectionIcon
import com.ambient.tv.ui.common.liveTvMenuIcon
import com.ambient.tv.ui.common.spotifyMenuIcon
import com.ambient.tv.ui.common.youtubeFeedIcon
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.rememberPendingAction
import com.ambient.tv.ui.common.systemHomeRowTypeTitle
import com.ambient.tv.ui.common.systemName
import com.ambient.tv.ui.common.systemString
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.player.ImageChoice
import com.ambient.tv.ui.player.ImageHint
import com.ambient.tv.vpn.WireGuardProfile
import com.ambient.tv.vpn.WireGuardRouteState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.theme.appControlSurface
import kotlinx.coroutines.delay

/** System & account settings pages extracted from [SettingsScreen]. Shared chrome stays there. */
internal fun LazyListScope.settingsSystemCategoryItems(
    category: SettingsCategory,
    handheldUi: Boolean,
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    focusContent: () -> Unit,
    requestExit: (() -> Unit) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDlna: () -> Unit,
) {
    when (category) {
        SettingsCategory.DLNA -> {
            item { Text(stringResource(R.string.dlna_explanation), style = MaterialTheme.typography.bodyMedium) }
            item { SettingsNavigationRow(stringResource(R.string.dlna_servers), onClick = { requestExit(onOpenDlna) }) }
            if (!handheldUi) item { SettingsPageLink(SettingsCategory.RECEIVER, "dlna-receiver") }
        }
        SettingsCategory.FILES -> {
            item { TvSettingRow(stringResource(R.string.files_enabled), stringResource(R.string.files_enabled_hint), uiState.filesEnabled, viewModel::onFilesEnabledChanged) }
            if (uiState.filesEnabled) item { SystemNameField(MediaSystem.FILES, uiState, viewModel) }
            item(key = "files-sources-section") { SettingsSubheader(stringResource(R.string.settings_system_sources_network)) }
            item { SettingsNavigationRow(stringResource(R.string.files_manage), onClick = { requestExit { viewModel.enableFilesForManagement(); onOpenFiles() } }) }
            item { SettingsPageLink(SettingsCategory.WAKE, "files-network", R.string.settings_network_link) }
            item { Text(stringResource(R.string.files_backup_hint), style = MaterialTheme.typography.bodySmall) }
            if (uiState.filesEnabled) item {
                SettingsGroup(title = stringResource(R.string.settings_submenu_items), subtitle = stringResource(R.string.settings_submenu_items_hint)) {
                    SubmenuSettingsRow(MediaSystem.FILES, uiState, viewModel::onSidebarSettingsShortcutChanged)
                }
            }
        }
        SettingsCategory.WIREGUARD -> {
            item(key = "wireguard-tunnel-section") {
                SettingsSubheader(stringResource(if (uiState.wireGuardSummary.isBlank()) R.string.settings_system_tunnel_import else R.string.settings_system_tunnel_status))
            }
            item(key = "wireguard-tunnel") { WireGuardRouteSection(uiState, viewModel) }
            wireGuardSystemItems(uiState, viewModel)
        }
        SettingsCategory.PLEX -> {
            item { TvSettingRow(systemString(MediaSystem.PLEX, R.string.settings_plex_enable), systemString(MediaSystem.PLEX, R.string.settings_plex_enable_hint), uiState.plexEnabled, viewModel::onPlexEnabledChanged) }
            if (uiState.plexEnabled) {
                item { SystemNameField(MediaSystem.PLEX, uiState, viewModel) }
                item(key = "plex-connection-section") { SettingsSubheader(stringResource(R.string.settings_system_server_account)) }
                if (uiState.savedServers.isNotEmpty()) {
                    item { ConnectionListCaption(stringResource(R.string.settings_saved_servers)) }
                    items(uiState.savedServers, key = { it.id }) { server ->
                        SavedServerRow(
                            server = server,
                            active = server.id == uiState.activeServerId,
                            onSelect = { viewModel.selectSavedServer(server) },
                            onRemove = {
                                // Move focus to a stable target before this keyed row disappears.
                                focusContent()
                                viewModel.removeSavedServer(server)
                            },
                        )
                    }
                }
                item {
                    TvActionButton(
                        text = stringResource(R.string.action_server_search),
                        onClick = viewModel::discoverServers,
                        loading = uiState.isDiscoveringServers,
                    )
                }
                if (uiState.discoveredServers.isNotEmpty()) {
                    item { ConnectionListCaption(stringResource(R.string.settings_discovered_servers)) }
                    itemsIndexed(uiState.discoveredServers, key = { _, it -> "${it.host}:${it.port}" }) { index, server ->
                        DiscoveredServerButton(
                            server = server,
                            onClick = { viewModel.selectDiscoveredServer(server) },
                            modifier = Modifier.focusFirstDiscovered(index, "plex", uiState, viewModel),
                        )
                    }
                }
                item {
                    SettingsGroup(title = stringResource(R.string.settings_manual_connection), card = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvTextField(value = uiState.host, onValueChange = viewModel::onHostChanged, label = stringResource(R.string.settings_host_label), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.port, onValueChange = viewModel::onPortChanged, label = stringResource(R.string.settings_port_label), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.token, onValueChange = viewModel::onTokenChanged, label = stringResource(R.string.settings_token_label), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    SettingsGroup {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (uiState.isLinkingAccount) {
                                Text(stringResource(R.string.settings_link_instructions))
                                uiState.linkCode?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
                                    ?: CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                val qrUrl = uiState.linkQrUrl
                                val linkCode = uiState.linkCode
                                if (!qrUrl.isNullOrBlank() && !linkCode.isNullOrBlank()) {
                                    var dismissedQr by remember(qrUrl) { mutableStateOf(false) }
                                    if (!dismissedQr) {
                                        com.ambient.tv.ui.common.QrCodeDialog(
                                            title = stringResource(R.string.action_sign_in),
                                            hint = stringResource(R.string.settings_link_instructions),
                                            payload = qrUrl,
                                            code = linkCode,
                                            onDismiss = { dismissedQr = true },
                                            footer = {
                                                TvActionButton(
                                                    text = stringResource(R.string.action_close),
                                                    onClick = { dismissedQr = true },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            // Keep this action in the same composition slot when linking starts or
                            // stops. Replacing the entire subtree discarded DPAD focus.
                            TvActionButton(
                                text = stringResource(
                                    when {
                                        uiState.isLinkingAccount -> R.string.settings_link_cancel
                                        uiState.plexSignedIn -> R.string.action_sign_out
                                        else -> R.string.action_sign_in
                                    },
                                ),
                                onClick = when {
                                    uiState.isLinkingAccount -> viewModel::cancelAccountLinking
                                    uiState.plexSignedIn -> viewModel::signOutPlex
                                    else -> viewModel::startAccountLinking
                                },
                            )
                        }
                    }
                }
                item(key = "plex-network-section") { SettingsSubheader(stringResource(R.string.settings_system_connection_start)) }
                item { TvSettingRow(stringResource(R.string.settings_plex_https), stringResource(R.string.settings_plex_https_hint), uiState.plexUseHttps, viewModel::onPlexHttpsChanged) }
                // Connection test right below address and sign-in, before Wake-on-LAN.
                item {
                    SettingsActionRow {
                        TvActionButton(
                            text = if (uiState.isTestingConnection) stringResource(R.string.settings_test_connection_progress) else stringResource(R.string.settings_reconnect_server),
                            onClick = viewModel::testConnection,
                            loading = uiState.isTestingConnection,
                        )
                    }
                }
                item { SettingsPageLink(SettingsCategory.WAKE, "plex-network", R.string.settings_network_link) }
                item { TvSettingRow(stringResource(R.string.settings_choose_server_startup), stringResource(R.string.settings_choose_server_startup_hint), uiState.chooseServerOnStartup, viewModel::onChooseServerOnStartupToggled) }
                uiState.plexDirectSuggestion?.let { suggestion ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.settings_plex_direct_hint, suggestion.substringBeforeLast(':')),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SettingsActionRow {
                                TvActionButton(text = stringResource(R.string.settings_plex_direct_use), onClick = viewModel::usePlexDirectSuggestion)
                                TvActionButton(text = stringResource(R.string.files_cancel), onClick = viewModel::dismissPlexDirectSuggestion)
                            }
                        }
                    }
                }
                item { TvSettingRow(stringResource(R.string.settings_local_only), stringResource(R.string.settings_local_only_hint), uiState.localNetworkOnly, viewModel::onLocalNetworkOnlyToggled) }
                if (!uiState.localNetworkOnly) {
                    item {
                        // A remote server without HTTPS is the case that actually leaks the token.
                        val remoteCleartext = !uiState.plexUseHttps && uiState.host.isNotBlank() &&
                            !com.ambient.tv.network.isHomeNetworkHost(uiState.host)
                        Text(
                            text = stringResource(
                                if (remoteCleartext) R.string.settings_plex_cleartext_remote_warning
                                else R.string.settings_local_only_cleartext_warning,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item(key = "plex-library-section") { SettingsSubheader(stringResource(R.string.settings_system_library_display)) }
                item {
                    SettingsSelect(
                        label = stringResource(R.string.settings_plex_refresh_interval),
                        description = systemString(MediaSystem.PLEX, R.string.settings_plex_refresh_hint),
                        options = listOf(0, 30, 60, 120).map { seconds ->
                            SettingsSelectOption(
                                text = if (seconds == 0) stringResource(R.string.settings_refresh_off) else stringResource(R.string.settings_refresh_seconds, seconds),
                                selected = uiState.plexAutoRefreshIntervalSeconds == seconds,
                                onClick = { viewModel.onPlexAutoRefreshIntervalChanged(seconds) },
                            )
                        },
                    )
                }
                item(key = "plex:customization") {
                    SystemCustomizationTabs(
                        showStartPageTab = uiState.plexConfigured,
                        submenuContent = {
                            SettingsGroup(title = stringResource(R.string.settings_submenu_items), subtitle = stringResource(R.string.settings_submenu_items_hint)) {
                                uiState.plexLibrarySections.forEach { section ->
                                    TvSettingRow(
                                        title = section.title,
                                        checked = section.key !in uiState.plexHiddenSectionKeys,
                                        onCheckedChange = { visible -> viewModel.onPlexSectionVisibilityChanged(section.key, visible) },
                                        leadingContent = { SettingsItemIcon(librarySectionIcon(section.type)) },
                                    )
                                }
                                SubmenuSettingsRow(MediaSystem.PLEX, uiState, viewModel::onSidebarSettingsShortcutChanged)
                            }
                        },
                        startPageContent = {
                            SystemHomeRowsGroup(HomeRowDefinition.SYSTEM_PLEX, uiState, viewModel::onSystemHomeRowChanged)
                        },
                    )
                }
                item { TvSettingRow(stringResource(R.string.settings_theme_music), stringResource(R.string.settings_theme_music_hint), uiState.plexThemeMusicEnabled, viewModel::onPlexThemeMusicToggled) }
            }
        }

        SettingsCategory.JELLYFIN -> {
            item { TvSettingRow(systemString(MediaSystem.JELLYFIN, R.string.settings_jellyfin_enable), systemString(MediaSystem.JELLYFIN, R.string.settings_jellyfin_enable_hint), uiState.jellyfinEnabled, viewModel::onJellyfinEnabledChanged) }
            if (uiState.jellyfinEnabled) {
                item { SystemNameField(MediaSystem.JELLYFIN, uiState, viewModel) }
                item(key = "jellyfin-connection-section") { SettingsSubheader(stringResource(R.string.settings_system_server_account)) }
                if (uiState.savedJellyfinServers.isNotEmpty()) {
                    item { ConnectionListCaption(stringResource(R.string.settings_saved_servers)) }
                    items(uiState.savedJellyfinServers, key = { "jellyfin-${it.id}" }) { server ->
                        SavedJellyfinServerRow(
                            server = server,
                            active = server.id == uiState.activeJellyfinServerId,
                            onSelect = { viewModel.selectJellyfinServer(server) },
                            onRemove = {
                                focusContent()
                                viewModel.removeJellyfinServer(server)
                            },
                        )
                    }
                }
                if (uiState.jellyfinConfigured) {
                    item { Text(stringResource(R.string.settings_jellyfin_configured, uiState.jellyfinUsername), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item {
                    TvActionButton(
                        text = stringResource(R.string.action_server_search),
                        onClick = viewModel::discoverJellyfinServers,
                        loading = uiState.isDiscoveringJellyfin,
                    )
                }
                if (uiState.discoveredJellyfinServers.isNotEmpty()) {
                    item { ConnectionListCaption(stringResource(R.string.settings_discovered_servers)) }
                    itemsIndexed(uiState.discoveredJellyfinServers, key = { _, it -> "discovered-jellyfin-${it.serverId ?: "${it.host}:${it.port}"}" }) { index, server ->
                        ServerSurface(
                            title = server.name,
                            subtitle = "${if (server.useHttps) "https" else "http"}://${server.host}:${server.port}${server.basePath}",
                            onClick = { viewModel.selectDiscoveredJellyfinServer(server) },
                            modifier = Modifier.fillMaxWidth().focusFirstDiscovered(index, "jellyfin", uiState, viewModel),
                        )
                    }
                }
                item {
                    SettingsGroup(title = stringResource(R.string.settings_manual_connection), card = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvTextField(value = uiState.jellyfinHost, onValueChange = viewModel::onJellyfinHostChanged, label = stringResource(R.string.settings_host_label), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.jellyfinPort, onValueChange = viewModel::onJellyfinPortChanged, label = stringResource(R.string.settings_port_label), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.jellyfinBasePath, onValueChange = viewModel::onJellyfinBasePathChanged, label = stringResource(R.string.settings_jellyfin_base_path), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvSettingRow(stringResource(R.string.settings_jellyfin_https), stringResource(R.string.settings_jellyfin_https_hint), uiState.jellyfinUseHttps, viewModel::onJellyfinHttpsChanged)
                            TvTextField(value = uiState.jellyfinUsername, onValueChange = viewModel::onJellyfinUsernameChanged, label = stringResource(R.string.settings_jellyfin_username), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.jellyfinPassword, onValueChange = viewModel::onJellyfinPasswordChanged, label = stringResource(R.string.settings_jellyfin_password), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item(key = "jellyfin-sign-in") {
                    SettingsActionRow {
                        TvActionButton(
                            text = when {
                                uiState.isSigningInJellyfin -> stringResource(R.string.action_signing_in)
                                uiState.jellyfinConfigured -> stringResource(R.string.action_sign_out)
                                else -> stringResource(R.string.action_sign_in)
                            },
                            onClick = if (uiState.jellyfinConfigured) viewModel::signOutJellyfin else viewModel::signInJellyfin,
                            loading = uiState.isSigningInJellyfin,
                        )
                        TvActionButton(
                            text = if (uiState.isTestingJellyfin) stringResource(R.string.settings_test_connection_progress) else stringResource(R.string.settings_reconnect_server),
                            onClick = viewModel::testJellyfinConnection,
                            enabled = uiState.jellyfinConfigured, loading = uiState.isTestingJellyfin,
                        )
                    }
                }
                item { SettingsPageLink(SettingsCategory.WAKE, "jellyfin-network", R.string.settings_network_link) }
                item(key = "jellyfin-library-section") { SettingsSubheader(stringResource(R.string.settings_system_library_display)) }
                item(key = "jellyfin:customization") {
                    SystemCustomizationTabs(
                        showStartPageTab = uiState.jellyfinConfigured,
                        submenuContent = {
                            SettingsGroup(title = stringResource(R.string.settings_submenu_items), subtitle = stringResource(R.string.settings_submenu_items_hint)) {
                                uiState.jellyfinLibrarySections.forEach { section ->
                                    TvSettingRow(
                                        title = section.title,
                                        checked = section.key !in uiState.jellyfinHiddenSectionKeys,
                                        onCheckedChange = { visible -> viewModel.onJellyfinSectionVisibilityChanged(section.key, visible) },
                                        leadingContent = { SettingsItemIcon(librarySectionIcon(section.type)) },
                                    )
                                }
                                SubmenuSettingsRow(MediaSystem.JELLYFIN, uiState, viewModel::onSidebarSettingsShortcutChanged)
                            }
                        },
                        startPageContent = {
                            SystemHomeRowsGroup(HomeRowDefinition.SYSTEM_JELLYFIN, uiState, viewModel::onSystemHomeRowChanged)
                        },
                    )
                }
                item { TvSettingRow(stringResource(R.string.settings_theme_music), stringResource(R.string.settings_theme_music_hint), uiState.jellyfinThemeMusicEnabled, viewModel::onJellyfinThemeMusicToggled) }
            }
        }

        SettingsCategory.EMBY -> {
            item { TvSettingRow(systemString(MediaSystem.EMBY, R.string.settings_emby_enable), systemString(MediaSystem.EMBY, R.string.settings_emby_enable_hint), uiState.embyEnabled, viewModel::onEmbyEnabledChanged) }
            if (uiState.embyEnabled) {
                item { SystemNameField(MediaSystem.EMBY, uiState, viewModel) }
                item(key = "emby-connection-section") { SettingsSubheader(stringResource(R.string.settings_system_server_account)) }
                if (uiState.savedEmbyServers.isNotEmpty()) {
                    item { ConnectionListCaption(stringResource(R.string.settings_saved_servers)) }
                    items(uiState.savedEmbyServers, key = { "emby-${it.id}" }) { server ->
                        SavedEmbyServerRow(
                            server = server,
                            active = server.id == uiState.activeEmbyServerId,
                            onSelect = { viewModel.selectEmbyServer(server) },
                            onRemove = {
                                focusContent()
                                viewModel.removeEmbyServer(server)
                            },
                        )
                    }
                }
                if (uiState.embyConfigured) {
                    item { Text(stringResource(R.string.settings_emby_configured, uiState.embyUsername), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item {
                    TvActionButton(
                        text = stringResource(R.string.action_server_search),
                        onClick = viewModel::discoverEmbyServers,
                        loading = uiState.isDiscoveringEmby,
                    )
                }
                if (uiState.discoveredEmbyServers.isNotEmpty()) {
                    item { ConnectionListCaption(stringResource(R.string.settings_discovered_servers)) }
                    itemsIndexed(uiState.discoveredEmbyServers, key = { _, it -> "discovered-emby-${it.serverId ?: "${it.host}:${it.port}"}" }) { index, server ->
                        ServerSurface(
                            title = server.name,
                            subtitle = "${if (server.useHttps) "https" else "http"}://${server.host}:${server.port}${server.basePath}",
                            onClick = { viewModel.selectDiscoveredEmbyServer(server) },
                            modifier = Modifier.fillMaxWidth().focusFirstDiscovered(index, "emby", uiState, viewModel),
                        )
                    }
                }
                item {
                    SettingsGroup(title = stringResource(R.string.settings_manual_connection), card = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvTextField(value = uiState.embyHost, onValueChange = viewModel::onEmbyHostChanged, label = stringResource(R.string.settings_host_label), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.embyPort, onValueChange = viewModel::onEmbyPortChanged, label = stringResource(R.string.settings_port_label), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.embyBasePath, onValueChange = viewModel::onEmbyBasePathChanged, label = stringResource(R.string.settings_emby_base_path), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvSettingRow(stringResource(R.string.settings_emby_https), stringResource(R.string.settings_emby_https_hint), uiState.embyUseHttps, viewModel::onEmbyHttpsChanged)
                            TvTextField(value = uiState.embyUsername, onValueChange = viewModel::onEmbyUsernameChanged, label = stringResource(R.string.settings_emby_username), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.embyPassword, onValueChange = viewModel::onEmbyPasswordChanged, label = stringResource(R.string.settings_emby_password), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item(key = "emby-sign-in") {
                    SettingsActionRow {
                        TvActionButton(
                            text = when {
                                uiState.isSigningInEmby -> stringResource(R.string.action_signing_in)
                                uiState.embyConfigured -> stringResource(R.string.action_sign_out)
                                else -> stringResource(R.string.action_sign_in)
                            },
                            onClick = if (uiState.embyConfigured) viewModel::signOutEmby else viewModel::signInEmby,
                            loading = uiState.isSigningInEmby,
                        )
                        TvActionButton(
                            text = if (uiState.isTestingEmby) stringResource(R.string.settings_test_connection_progress) else stringResource(R.string.settings_reconnect_server),
                            onClick = viewModel::testEmbyConnection,
                            enabled = uiState.embyConfigured, loading = uiState.isTestingEmby,
                        )
                    }
                }
                item { SettingsPageLink(SettingsCategory.WAKE, "emby-network", R.string.settings_network_link) }
                item(key = "emby-library-section") { SettingsSubheader(stringResource(R.string.settings_system_library_display)) }
                item(key = "emby:customization") {
                    SystemCustomizationTabs(
                        showStartPageTab = uiState.embyConfigured,
                        submenuContent = {
                            SettingsGroup(title = stringResource(R.string.settings_submenu_items), subtitle = stringResource(R.string.settings_submenu_items_hint)) {
                                uiState.embyLibrarySections.forEach { section ->
                                    TvSettingRow(
                                        title = section.title,
                                        checked = section.key !in uiState.embyHiddenSectionKeys,
                                        onCheckedChange = { visible -> viewModel.onEmbySectionVisibilityChanged(section.key, visible) },
                                        leadingContent = { SettingsItemIcon(librarySectionIcon(section.type)) },
                                    )
                                }
                                SubmenuSettingsRow(MediaSystem.EMBY, uiState, viewModel::onSidebarSettingsShortcutChanged)
                            }
                        },
                        startPageContent = {
                            SystemHomeRowsGroup(HomeRowDefinition.SYSTEM_EMBY, uiState, viewModel::onSystemHomeRowChanged)
                        },
                    )
                }
                item { TvSettingRow(stringResource(R.string.settings_theme_music), stringResource(R.string.settings_theme_music_hint), uiState.embyThemeMusicEnabled, viewModel::onEmbyThemeMusicToggled) }
            }
        }

        SettingsCategory.TVHEADEND -> {
            item { TvSettingRow(systemString(MediaSystem.TVHEADEND, R.string.settings_tvh_enable), stringResource(R.string.settings_tvh_enable_hint), uiState.tvheadendEnabled, viewModel::onTvheadendEnabledChanged) }
            if (uiState.tvheadendEnabled) {
                item { SystemNameField(MediaSystem.TVHEADEND, uiState, viewModel) }
                item(key = "tvheadend-connection-section") { SettingsSubheader(stringResource(R.string.settings_system_server_access)) }
                item {
                    SettingsGroup(card = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvTextField(value = uiState.tvheadendHost, onValueChange = viewModel::onTvheadendHostChanged, label = stringResource(R.string.settings_tvh_host), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.tvheadendPort, onValueChange = viewModel::onTvheadendPortChanged, label = stringResource(R.string.settings_tvh_port), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.tvheadendUsername, onValueChange = viewModel::onTvheadendUsernameChanged, label = stringResource(R.string.settings_tvh_username), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                            TvTextField(value = uiState.tvheadendPassword, onValueChange = viewModel::onTvheadendPasswordChanged, label = stringResource(R.string.settings_tvh_password), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                            TvSettingRow(stringResource(R.string.settings_tvh_https), stringResource(R.string.settings_tvh_https_hint), uiState.tvheadendUseHttps, viewModel::onTvheadendHttpsChanged)
                        }
                    }
                }
                item {
                    TvActionButton(
                        text = if (uiState.isTestingTvheadend) stringResource(R.string.settings_test_connection_progress) else stringResource(R.string.settings_reconnect_server),
                        onClick = viewModel::testTvheadendConnection,
                        loading = uiState.isTestingTvheadend,
                    )
                }
                item { SettingsPageLink(SettingsCategory.WAKE, "tvheadend-network", R.string.settings_network_link) }
                item { Text(stringResource(R.string.settings_tvh_permissions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item(key = "tvheadend-menu-section") { SettingsSubheader(stringResource(R.string.settings_system_menu_home)) }
                item {
                    SettingsGroup(title = stringResource(R.string.settings_submenu_items), subtitle = stringResource(R.string.settings_submenu_items_hint)) {
                        listOf(
                            "live" to stringResource(R.string.tvh_live),
                            "guide" to stringResource(R.string.tvh_guide),
                            "recordings" to stringResource(R.string.tvh_recordings),
                            "timers" to stringResource(R.string.tvh_timers),
                        ).forEach { (key, label) ->
                            TvSettingRow(title = label, checked = key in uiState.tvheadendSubmenuKeys,
                                onCheckedChange = { enabled -> viewModel.onTvheadendSubmenuChanged(key, enabled) },
                                leadingContent = { SettingsItemIcon(liveTvMenuIcon(key)) })
                        }
                        SubmenuSettingsRow(MediaSystem.TVHEADEND, uiState, viewModel::onSidebarSettingsShortcutChanged)
                    }
                }
                item { TvSettingRow(stringResource(R.string.settings_tvh_home), stringResource(R.string.settings_tvh_home_hint), uiState.tvheadendShowOnHome, viewModel::onTvheadendShowOnHomeChanged) }
                item(key = "tvheadend-playback-section") { SettingsSubheader(stringResource(R.string.settings_system_live_playback)) }
                item {
                    TvTextField(value = uiState.tvheadendStreamProfile, onValueChange = viewModel::onTvheadendStreamProfileChanged, label = stringResource(R.string.settings_tvh_stream_profile), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                }
                item {
                    SettingsSelect(
                        label = systemString(MediaSystem.TVHEADEND, R.string.settings_tvh_playback_backend),
                        description = stringResource(R.string.settings_tvh_playback_backend_hint),
                        options = listOf("media3", "vlc", "mpv").map { backend ->
                            val mpvUnavailable = backend == "mpv" && !com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable
                            SettingsSelectOption(
                                text = when (backend) {
                                    "media3" -> stringResource(R.string.player_backend_media3)
                                    "vlc" -> stringResource(R.string.player_backend_vlc)
                                    "mpv" -> stringResource(R.string.player_backend_mpv)
                                    else -> stringResource(R.string.player_backend_auto)
                                },
                                selected = uiState.tvheadendPlaybackBackendKey == backend,
                                onClick = { if (!mpvUnavailable) viewModel.onTvheadendPlaybackBackendChanged(backend) },
                                enabled = !mpvUnavailable,
                            )
                        },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (uiState.tvheadendPlaybackBackendKey !in setOf("mpv", "vlc")) {
                            ConnectionListCaption(stringResource(R.string.tvh_deinterlace_state))
                            ImageHint(stringResource(R.string.tvh_deinterlace_media3_hint))
                        } else {
                            ImageChoice(R.string.tvh_deinterlace_state, uiState.tvheadendDeinterlaceModeKey, listOf(
                                "off" to stringResource(R.string.player_off), "auto" to stringResource(R.string.tvh_deinterlace_auto), "on" to stringResource(R.string.player_on),
                            ), viewModel::onTvheadendDeinterlaceModeChanged)
                            com.ambient.tv.ui.player.DeinterlaceMethodChoice(
                                backend = uiState.tvheadendPlaybackBackendKey,
                                key = if (uiState.tvheadendPlaybackBackendKey == "mpv") uiState.tvheadendMpvDeinterlaceFilterKey else uiState.tvheadendDeinterlaceFilterKey,
                                onSelected = viewModel::onTvheadendDeinterlaceFilterChanged,
                            )
                        }
                    }
                }
                item { SettingsPageLink(SettingsCategory.LIVE_TV, "tvheadend-live-tv") }
                item { SettingsPageLink(SettingsCategory.IMAGE, "tvh-gpu-output", R.string.settings_gpu_output_link) }
            }
        }

        SettingsCategory.RADIO -> {
            item {
                TvSettingRow(
                    systemString(MediaSystem.RADIO, R.string.settings_radio_enable),
                    systemString(MediaSystem.RADIO, R.string.settings_radio_enable_hint),
                    uiState.radioEnabled,
                    viewModel::onRadioEnabledChanged,
                )
            }
            if (uiState.radioEnabled) {
                item { SystemNameField(MediaSystem.RADIO, uiState, viewModel) }
                item(key = "radio-provider") {
                    Text(
                        stringResource(R.string.radio_provider_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "radio-menu") {
                    SettingsGroup(subtitle = stringResource(R.string.settings_system_menu_display)) {
                        SubmenuSettingsRow(MediaSystem.RADIO, uiState, viewModel::onSidebarSettingsShortcutChanged)
                    }
                }
            }
        }

        SettingsCategory.SPOTIFY -> {
            item { TvSettingRow(systemString(MediaSystem.SPOTIFY, R.string.settings_spotify_enable), systemString(MediaSystem.SPOTIFY, R.string.settings_spotify_enable_hint), uiState.spotifyEnabled, viewModel::onSpotifyEnabledChanged) }

            if (uiState.spotifyEnabled) {
                item { SystemNameField(MediaSystem.SPOTIFY, uiState, viewModel) }
            }
            item(key = "spotify-account-section") { SettingsSubheader(stringResource(R.string.settings_system_account_setup)) }
            item(key = "spotify:account") {
                SettingsGroup(subtitle = stringResource(R.string.settings_spotify_web_subtitle), card = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (uiState.spotifyClientId.isNotBlank()) {
                                stringResource(R.string.settings_spotify_client_override_active)
                            } else {
                                stringResource(R.string.settings_spotify_client_missing_build)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.spotifyClientId.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            when {
                                uiState.spotifyWebAuthorized -> stringResource(R.string.settings_spotify_status_signed_in, uiState.spotifyAccountName ?: "Spotify")
                                uiState.spotifyAuthBusy -> stringResource(R.string.settings_spotify_status_busy)
                                else -> stringResource(R.string.settings_spotify_status_signed_out)
                            },
                            color = if (uiState.spotifyWebAuthorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Required: no client ID is built in (see README "Spotify setup").
                        TvTextField(
                            value = uiState.spotifyClientId,
                            onValueChange = viewModel::onSpotifyClientIdChanged,
                            label = stringResource(R.string.settings_spotify_client_id),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.settings_spotify_client_override_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.settings_spotify_redirect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        com.ambient.tv.BuildConfig.SPOTIFY_QR_REDIRECT_URI.takeIf(String::isNotBlank)?.let { qrRedirect ->
                            Text(stringResource(R.string.settings_spotify_qr_redirect, qrRedirect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        uiState.spotifyAuthError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        SettingsActionRow {
                            TvActionButton(
                                text = if (uiState.spotifyWebAuthorized) stringResource(R.string.action_sign_out) else stringResource(R.string.action_sign_in),
                                onClick = if (uiState.spotifyWebAuthorized) viewModel::logoutSpotify else viewModel::loginSpotify,
                                loading = uiState.spotifyAuthBusy,
                            )
                        }
                    }
                }
            }
            item(key = "spotify-playback-section") { SettingsSubheader(stringResource(R.string.settings_system_device_playback)) }
            item { TvTextField(value = uiState.spotifyDeviceName, onValueChange = viewModel::onSpotifyDeviceNameChanged, label = stringResource(R.string.settings_spotify_device_name), modifier = Modifier.fillMaxWidth()) }
            item {
                if (!com.ambient.tv.ui.mobile.LocalHandheldUi.current) Text(
                    text = stringResource(R.string.settings_spotify_boot_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingsSelect(
                    label = stringResource(R.string.settings_spotify_audio_quality),
                    description = stringResource(R.string.settings_spotify_audio_quality_subtitle),
                    options = listOf(96, 160, 320).map { bitrate ->
                        SettingsSelectOption(text = stringResource(R.string.unit_kbps, bitrate), selected = uiState.spotifyBitrateKbps == bitrate, onClick = { viewModel.onSpotifyBitrateChanged(bitrate) })
                    },
                )
            }
            item(key = "spotify-display-section") { SettingsSubheader(stringResource(R.string.settings_system_menu_display)) }
            if (uiState.spotifyEnabled) {
                item {
                    SettingsGroup(title = stringResource(R.string.settings_submenu_items), subtitle = stringResource(R.string.settings_submenu_items_hint)) {
                        listOf(
                            "home" to stringResource(R.string.nav_spotify_home),
                            "liked" to stringResource(R.string.nav_spotify_liked),
                            "playlists" to stringResource(R.string.nav_spotify_playlists),
                            "albums" to stringResource(R.string.nav_spotify_albums),
                            "artists" to stringResource(R.string.nav_spotify_artists),
                            "now_playing" to stringResource(R.string.nav_spotify_now_playing),
                        ).forEach { (key, label) ->
                            TvSettingRow(title = label, checked = key in uiState.spotifySubmenuKeys,
                                onCheckedChange = { enabled -> viewModel.onSpotifySubmenuChanged(key, enabled) },
                                leadingContent = { SettingsItemIcon(spotifyMenuIcon(key)) })
                        }
                        SubmenuSettingsRow(MediaSystem.SPOTIFY, uiState, viewModel::onSidebarSettingsShortcutChanged)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsSelect(
                        label = stringResource(R.string.settings_spotify_visualization),
                        description = stringResource(R.string.settings_spotify_visualization_subtitle),
                        options = listOf("cover", "spectrum", "projectm", "off").map { mode ->
                            val label = when (mode) {
                                "spectrum" -> stringResource(R.string.spotify_visual_spectrum)
                                "projectm" -> "projectM"
                                "off" -> stringResource(R.string.settings_spotify_visual_off)
                                else -> stringResource(R.string.settings_spotify_visual_cover)
                            }
                            SettingsSelectOption(
                                text = label,
                                selected = uiState.spotifyVisualizerModeKey == mode,
                                enabled = mode != "projectm" || com.ambient.tv.ui.common.projectm.ProjectMAvailability.isAvailable,
                                onClick = { viewModel.onSpotifyVisualizerChanged(mode) },
                            )
                        },
                    )
                    Text(stringResource(R.string.settings_spotify_visualizer_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsPageLink(SettingsCategory.VISUALIZER, "spotify-visualizer")
                }
            }
            item { Text(stringResource(R.string.settings_spotify_native_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        SettingsCategory.YOUTUBE -> {
            item {
                TvSettingRow(
                    systemString(MediaSystem.YOUTUBE, R.string.settings_youtube_enable),
                    systemString(MediaSystem.YOUTUBE, R.string.settings_youtube_enable_hint),
                    uiState.youtubeEnabled,
                    viewModel::onYoutubeEnabledChanged,
                )
            }
            if (uiState.youtubeEnabled) item { SystemNameField(MediaSystem.YOUTUBE, uiState, viewModel) }
            item(key = "youtube-account-section") { SettingsSubheader(stringResource(R.string.settings_system_account_devices)) }
            item {
                SettingsGroup(
                    title = stringResource(R.string.setup_youtube_login),
                    subtitle = stringResource(R.string.settings_youtube_group_subtitle),
                ) {
                    // Successful sign-in used to replace the focused button with Text and leave
                    // Compose without a focus owner. Retain the action node in a disabled state.
                    TvActionButton(
                        text = when {
                            uiState.youtubeSignedIn && uiState.youtubeAuthBusy -> stringResource(R.string.action_signing_out)
                            uiState.youtubeSignedIn -> stringResource(R.string.action_sign_out)
                            uiState.youtubeAuthBusy -> stringResource(R.string.action_signing_in)
                            else -> stringResource(R.string.action_sign_in)
                        },
                        onClick = if (uiState.youtubeSignedIn) viewModel::signOutYouTube else viewModel::requestYouTubeSignIn,
                        enabled = uiState.youtubeAvailable, loading = uiState.youtubeAuthBusy,
                    )
                    uiState.youtubeSignInCode?.let { code ->
                        // Keep the pending code visible after the modal is closed, but use the
                        // exact same QR/browser prompt as the SmartTube browse screen.
                        Text(
                            stringResource(
                                if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) R.string.youtube_activate_handheld
                                else R.string.setup_youtube_code_instructions,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(code, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        var dismissedQr by remember(code) { mutableStateOf(false) }
                        if (!dismissedQr) {
                            com.ambient.tv.ui.youtube.YouTubeSignInPrompt(
                                code = code,
                                onDismiss = { dismissedQr = true },
                            )
                        }
                    }
                    uiState.youtubeAuthErrorRes?.let { res ->
                        Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    uiState.youtubeAuthError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (!uiState.youtubeAvailable) {
                        Text(stringResource(R.string.setup_youtube_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!handheldUi && uiState.youtubeEnabled && uiState.youtubeAvailable) {
                item { YouTubeTvLinkSection(viewModel) }
            }
            if (uiState.youtubeEnabled) {
                item(key = "youtube-playback-section") { SettingsSubheader(stringResource(R.string.settings_system_playback)) }
                item(key = "youtube-playback-behaviour") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvSettingRow(
                            title = stringResource(R.string.settings_youtube_sponsorblock_title),
                            body = stringResource(R.string.settings_youtube_sponsorblock_hint),
                            checked = uiState.youtubeSponsorBlockEnabled,
                            onCheckedChange = viewModel::onYoutubeSponsorBlockChanged,
                        )
                        TvSettingRow(
                            title = stringResource(R.string.settings_youtube_autoplay_next),
                            body = stringResource(R.string.settings_youtube_autoplay_next_hint),
                            checked = uiState.youtubeAutoplayNextEnabled,
                            onCheckedChange = viewModel::onYoutubeAutoplayNextChanged,
                        )
                    }
                }
                item(key = "youtube-menu-section") { SettingsSubheader(stringResource(R.string.settings_system_menu_start)) }
                item(key = "youtube-feeds") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConnectionListCaption(stringResource(R.string.settings_youtube_feeds))
                        val youtubeFeedLabels = listOf(
                            "home" to stringResource(R.string.nav_youtube_home),
                            "subscriptions" to stringResource(R.string.nav_youtube_subscriptions),
                            "channels" to stringResource(R.string.nav_youtube_channels),
                            "history" to stringResource(R.string.nav_youtube_history),
                            "playlists" to stringResource(R.string.nav_youtube_playlists),
                            "shorts" to stringResource(R.string.nav_youtube_shorts),
                            "music" to stringResource(R.string.nav_youtube_music),
                            "news" to stringResource(R.string.nav_youtube_news),
                            "gaming" to stringResource(R.string.nav_youtube_gaming),
                            "live" to stringResource(R.string.nav_youtube_live),
                            "kids" to stringResource(R.string.nav_youtube_kids),
                        )
                        youtubeFeedLabels.filter { (key, _) -> key in uiState.youtubeSupportedFeedKeys }.forEach { (key, label) ->
                            TvSettingRow(
                                title = label,
                                checked = key in uiState.youtubeFeedKeys,
                                onCheckedChange = { enabled -> viewModel.onYoutubeFeedChanged(key, enabled) },
                                leadingContent = { SettingsItemIcon(youtubeFeedIcon(key)) },
                            )
                        }
                        SettingsSelect(
                            label = stringResource(R.string.settings_youtube_start_feed),
                            options = youtubeFeedLabels
                                .filter { (key, _) -> key in uiState.youtubeFeedKeys && key in uiState.youtubeSupportedFeedKeys }
                                .map { (key, label) ->
                                    SettingsSelectOption(
                                        text = label,
                                        selected = uiState.youtubeStartFeedKey == key,
                                        onClick = { viewModel.onYoutubeStartFeedChanged(key) },
                                    )
                                },
                        )
                        SubmenuSettingsRow(MediaSystem.YOUTUBE, uiState, viewModel::onSidebarSettingsShortcutChanged)
                    }
                }
                item(key = "youtube-quality-section") { SettingsSubheader(stringResource(R.string.settings_system_video_quality)) }
                item(key = "youtube-video-preset") {
                    val youtubeContext = LocalContext.current
                    val devicePreset = remember {
                        com.ambient.tv.youtube.YouTubeDeviceVideo.videoTarget(youtubeContext, com.ambient.tv.youtube.YOUTUBE_PRESET_AUTO, 0)
                    }
                    SettingsSelect(
                        label = stringResource(R.string.settings_youtube_video_preset),
                        description = stringResource(R.string.settings_youtube_quality_hint),
                        options = buildList {
                            add(SettingsSelectOption(
                                text = stringResource(R.string.settings_youtube_video_preset_auto, devicePreset.label),
                                selected = uiState.youtubeVideoPreset == com.ambient.tv.youtube.YOUTUBE_PRESET_AUTO,
                                onClick = { viewModel.onYoutubeVideoPresetChanged(com.ambient.tv.youtube.YOUTUBE_PRESET_AUTO) },
                            ))
                            com.ambient.tv.youtube.YOUTUBE_VIDEO_PRESETS.forEach { preset ->
                                add(SettingsSelectOption(
                                    text = preset.label,
                                    selected = uiState.youtubeVideoPreset == preset.key,
                                    onClick = { viewModel.onYoutubeVideoPresetChanged(preset.key) },
                                ))
                            }
                        },
                    )
                }
                item(key = "youtube-resolution") {
                    SettingsSelect(
                        label = stringResource(R.string.settings_youtube_max_resolution),
                        description = stringResource(R.string.settings_youtube_max_resolution_display_hint),
                        options = listOf(0, 720, 1080, 1440, 2160).map { height ->
                            SettingsSelectOption(
                                text = if (height == 0) stringResource(R.string.settings_youtube_max_resolution_display) else "${height}p",
                                selected = uiState.youtubeMaxVideoHeight == height,
                                onClick = { viewModel.onYoutubeMaxVideoHeightChanged(height) },
                            )
                        },
                    )
                }
                item(key = "youtube-network-section") { SettingsSubheader(stringResource(R.string.settings_system_network_buffer)) }
                item(key = "youtube-network-engine") {
                    SettingsSelect(
                        label = stringResource(R.string.settings_youtube_network_engine),
                        description = stringResource(R.string.settings_youtube_network_engine_hint),
                        options = com.ambient.tv.ui.player.MEDIA3_NETWORK_ENGINES.map { engine ->
                            SettingsSelectOption(
                                text = when (engine) {
                                    com.ambient.tv.ui.player.MEDIA3_NETWORK_CRONET -> stringResource(R.string.settings_youtube_network_engine_play)
                                    com.ambient.tv.ui.player.MEDIA3_NETWORK_DEFAULT -> stringResource(R.string.settings_youtube_network_engine_default)
                                    else -> stringResource(R.string.settings_youtube_network_engine_classic)
                                },
                                selected = uiState.youtubeNetworkEngine == engine,
                                onClick = { viewModel.onYoutubeNetworkEngineChanged(engine) },
                            )
                        },
                    )
                }
                item(key = "youtube-buffer") {
                    SettingsSelect(
                        label = stringResource(R.string.settings_youtube_buffer),
                        description = stringResource(R.string.settings_youtube_buffer_hint),
                        options = com.ambient.tv.ui.player.MEDIA3_BUFFER_KEYS.map { key ->
                            SettingsSelectOption(
                                text = stringResource(when (key) {
                                    "low" -> R.string.settings_youtube_buffer_low
                                    "high" -> R.string.settings_youtube_buffer_high
                                    else -> R.string.settings_youtube_buffer_medium
                                }),
                                selected = uiState.youtubeBufferKey == key,
                                onClick = { viewModel.onYoutubeBufferChanged(key) },
                            )
                        },
                    )
                }
            }
        }
        else -> Unit
    }
}



/** A list caption stays subordinate to the page's main section headers. */
@Composable
private fun ConnectionListCaption(title: String) {
    SettingsGroupHeader(title)
}

/** Renames a system throughout the app; an empty field restores the provider's own name. */
@Composable
internal fun SystemNameField(system: MediaSystem, uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val provider = system.brand ?: stringResource(system.label)
    SettingsGroup(
        title = stringResource(R.string.settings_system_name),
        subtitle = stringResource(R.string.settings_system_name_hint, provider),
    ) {
        TvTextField(
            value = uiState.systemNames[system.key].orEmpty(),
            onValueChange = { viewModel.onSystemNameChanged(system.key, it) },
            label = stringResource(R.string.settings_system_name_label, provider),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}


@Composable
internal fun WireGuardRouteSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    var draft by remember { mutableStateOf("") }
    // Imported: drop the pasted text (it holds the private key) once the tunnel is stored.
    LaunchedEffect(uiState.wireGuardSummary) { if (uiState.wireGuardSummary.isNotBlank()) draft = "" }
    val importFocus = remember { FocusRequester() }
    val testFocus = remember { FocusRequester() }
    var previousImported by remember { mutableStateOf(uiState.wireGuardSummary.isNotBlank()) }
    LaunchedEffect(uiState.wireGuardSummary.isNotBlank()) {
        val imported = uiState.wireGuardSummary.isNotBlank()
        if (imported != previousImported) {
            previousImported = imported
            androidx.compose.runtime.withFrameNanos { }
            runCatching { (if (imported) testFocus else importFocus).requestFocus() }
        }
    }
    var filePicker by remember { mutableStateOf(false) }
    var qrScanner by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        // Focus starts on Cancel so a repeated OK press can't delete the tunnel by accident.
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(80); runCatching { cancelFocus.requestFocus() } }
        AppDialog(
            onDismiss = { confirmRemove = false },
            title = stringResource(R.string.wg_remove_title),
            hint = MediaSystem.renamedAll(stringResource(R.string.wg_remove_hint)),
            captureInitialFocus = false,
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), { confirmRemove = false }, Modifier.focusRequester(cancelFocus))
                TvActionButton(stringResource(R.string.wg_remove), { confirmRemove = false; viewModel.removeWireGuardConfig() })
            },
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importWireGuardUri(uri)
    }
    val hasDocumentPicker = remember {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        // Android TV registers a stub that only shows "no app can do this"; it is not a picker.
        context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.packageName != "com.android.tv.frameworkpackagestubs" }
    }
    if (filePicker) {
        WireGuardFileDialog(
            viewModel = viewModel,
            onSystemPicker = if (hasDocumentPicker) {
                {
                    filePicker = false
                    // .conf has no registered MIME type, so the picker cannot filter reliably.
                    runCatching { documentPicker.launch(arrayOf("*/*")) }
                }
            } else null,
            onDismiss = { filePicker = false },
        )
    }
    val hasQrCamera = remember(context) {
        runCatching {
            context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                ?.cameraIdList?.isNotEmpty() == true
        }.getOrDefault(false)
    }
    if (qrScanner) {
        AppDialog(
            onDismiss = { qrScanner = false },
            title = stringResource(R.string.wg_scan_qr),
            footer = {
                TvActionButton(
                    text = stringResource(R.string.action_close),
                    onClick = { qrScanner = false },
                )
            },
        ) {
            com.ambient.tv.companion.QrScanner(
                hint = stringResource(R.string.wg_scan_qr_hint),
                onDecoded = { config ->
                    qrScanner = false
                    viewModel.importWireGuardConfig(config)
                },
            )
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.startWireGuard() else viewModel.onWireGuardConsentDenied()
    }
    val status = when (val route = uiState.wireGuard.status) {
        WireGuardRouteState.Idle -> stringResource(R.string.wg_status_idle)
        WireGuardRouteState.Connecting -> stringResource(R.string.wg_status_connecting)
        WireGuardRouteState.Up -> stringResource(R.string.wg_status_up)
        is WireGuardRouteState.Failed -> stringResource(R.string.wg_status_failed, route.message)
    }
    SettingsGroup(subtitle = stringResource(R.string.wg_hint), card = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (uiState.wireGuardSummary.isBlank()) {
                Text(
                    stringResource(R.string.wg_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The tunnel from the loaded file: its endpoint and routes, instead of the input.
                val profile = remember(uiState.wireGuardSummary) { viewModel.wireGuardProfile() }
                profile?.let { WireGuardTunnelDetails(it) }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (uiState.wireGuardSummary.isBlank()) {
                // Import: paste or load a file. Hidden once a tunnel is stored; remove it to import another.
                TvTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = stringResource(R.string.wg_paste),
                    singleLine = false,
                    requireExplicitEditing = true,
                    modifier = Modifier.focusRequester(importFocus).fillMaxWidth().heightIn(min = 96.dp, max = 180.dp),
                )
                SettingsActionRow {
                    TvActionButton(text = stringResource(R.string.wg_load_file), onClick = { filePicker = true })
                    if (hasQrCamera) {
                        TvActionButton(text = stringResource(R.string.wg_scan_qr), onClick = { qrScanner = true })
                    }
                    TvActionButton(
                        text = stringResource(R.string.wg_save),
                        // The pasted text stays until the import succeeds (the field hides then), so a
                        // rejected config can be corrected instead of pasted again.
                        onClick = { viewModel.importWireGuardConfig(draft) },
                        enabled = draft.isNotBlank(),
                    )
                }
            } else {
                TvActionButton(
                    text = stringResource(if (uiState.wireGuard.testing) R.string.wg_testing else R.string.wg_test),
                    // A handshake test only: no VPN consent, no tunnel.
                    onClick = viewModel::testWireGuard,
                    loading = uiState.wireGuard.testing,
                    modifier = Modifier.focusRequester(testFocus),
                )
                uiState.wireGuard.testMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.wireGuard.testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                SettingsActionRow {
                    TvActionButton(
                        text = stringResource(R.string.wg_connect),
                        onClick = {
                            val intent = viewModel.wireGuardPrepareIntent()
                            if (intent != null) consent.launch(intent) else viewModel.startWireGuard()
                        },
                        enabled = viewModel.wireGuardInUse() &&
                            (uiState.wireGuard.status is WireGuardRouteState.Idle || uiState.wireGuard.status is WireGuardRouteState.Failed),
                    )
                    TvActionButton(text = stringResource(R.string.wg_disconnect), onClick = viewModel::stopWireGuard,
                        enabled = uiState.wireGuard.status is WireGuardRouteState.Up || uiState.wireGuard.status is WireGuardRouteState.Connecting,
                    )
                    TvActionButton(text = stringResource(R.string.wg_remove), onClick = { confirmRemove = true })
                }
            }
        }
    }
}


/** Endpoint, tunnel address, DNS and routes of the stored tunnel; no key material. */
@Composable
private fun WireGuardTunnelDetails(profile: WireGuardProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.wg_detail_endpoint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (profile.endpointHost.contains(':')) "[${profile.endpointHost}]:${profile.endpointPort}" else "${profile.endpointHost}:${profile.endpointPort}",
            style = MaterialTheme.typography.titleMedium,
        )
        listOf(
            stringResource(R.string.wg_detail_address) to "${profile.address}/${profile.prefix}",
            stringResource(R.string.wg_detail_dns) to profile.dns.ifBlank { "–" },
            stringResource(R.string.wg_detail_allowed) to profile.allowedIps.joinToString(", "),
            stringResource(R.string.wg_detail_keepalive) to
                if (profile.keepaliveSeconds > 0) stringResource(R.string.wg_detail_seconds, profile.keepaliveSeconds) else stringResource(R.string.wg_detail_off),
            stringResource(R.string.wg_detail_mtu) to profile.mtu.toString(),
        ).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.38f))
                Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.62f))
            }
        }
    }
}

@Composable
internal fun WireGuardSystemSwitch(
    title: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    viewModel: SettingsViewModel,
) {
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) onEnabled(true) else viewModel.onWireGuardConsentDenied()
    }
    TvSettingRow(title, stringResource(R.string.wg_system_hint), enabled, onCheckedChange = { value ->
        if (!value) onEnabled(false)
        else {
            val intent = viewModel.wireGuardPrepareIntent()
            if (intent != null) consent.launch(intent) else onEnabled(true)
        }
    })
}


private enum class SystemCustomizationTab { Submenu, StartPage }

/**
 * Chip tabs for system customization: submenu entries vs this system's start page.
 * The start-page tab is only offered when the system is configured.
 * When that tab is hidden, the submenu content is shown without tab chrome.
 */
@Composable
internal fun SystemCustomizationTabs(
    showStartPageTab: Boolean,
    submenuContent: @Composable () -> Unit,
    startPageContent: @Composable () -> Unit,
) {
    val searchLanding = LocalSettingsSearchLanding.current
    var selected by remember {
        mutableStateOf(
            if (showStartPageTab && searchLanding != null && settingsSearchPrefersStartPageTab(searchLanding)) {
                SystemCustomizationTab.StartPage
            } else {
                SystemCustomizationTab.Submenu
            },
        )
    }
    LaunchedEffect(showStartPageTab, searchLanding) {
        if (!showStartPageTab) {
            selected = SystemCustomizationTab.Submenu
        } else if (searchLanding != null) {
            selected = if (settingsSearchPrefersStartPageTab(searchLanding)) {
                SystemCustomizationTab.StartPage
            } else {
                SystemCustomizationTab.Submenu
            }
        }
    }
    if (!showStartPageTab) {
        submenuContent()
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TvChip(
                    text = stringResource(R.string.settings_submenu_items),
                    selected = selected == SystemCustomizationTab.Submenu,
                    onClick = { selected = SystemCustomizationTab.Submenu },
                )
            }
            item {
                TvChip(
                    text = stringResource(R.string.settings_system_home_title),
                    selected = selected == SystemCustomizationTab.StartPage,
                    onClick = { selected = SystemCustomizationTab.StartPage },
                )
            }
        }
        when (selected) {
            SystemCustomizationTab.Submenu -> submenuContent()
            SystemCustomizationTab.StartPage -> startPageContent()
        }
    }
}


/** Which rows a media server's own start page shows; Home itself is laid out in the Home editor. */
@Composable
internal fun SystemHomeRowsGroup(system: String, state: SettingsUiState, onChange: (String, String, Boolean) -> Unit) {
    val context = LocalContext.current
    val mediaSystem = MediaSystem.fromKey(system)
    SettingsGroup(title = stringResource(R.string.settings_system_home_title), subtitle = stringResource(R.string.settings_system_home_hint)) {
        systemHomeRowTypes(system).forEach { type ->
            TvSettingRow(
                title = systemHomeRowTypeTitle(context, type),
                body = if (type == HomeRowDefinition.CONTENT_TOP_GENRE) stringResource(R.string.settings_system_home_genres_hint) else null,
                checked = "$system.$type" !in state.systemHomeHiddenRows,
                onCheckedChange = { visible -> onChange(system, type, visible) },
                leadingContent = mediaSystem?.let { source -> { SystemLogo(source, size = 20.dp) } },
            )
        }
    }
}


/** The sidebar submenu's shortcut to this page, listed after the system's other submenu entries. */
@Composable
internal fun SubmenuSettingsRow(system: MediaSystem, state: SettingsUiState, onChange: (String, Boolean) -> Unit) {
    TvSettingRow(
        title = stringResource(R.string.nav_settings),
        body = stringResource(R.string.settings_submenu_settings_hint),
        checked = system.key !in state.sidebarSettingsShortcutHidden,
        onCheckedChange = { visible -> onChange(system.key, visible) },
        // The actual system submenu uses its provider mark for this shortcut too.
        leadingContent = { SystemLogo(system, size = 20.dp) },
    )
}


@Composable
internal fun SavedServerRow(server: ServerProfile, active: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ServerSurface(
            title = (if (active) "●  " else "") + server.name,
            subtitle = "${server.host}:${server.port}" + if (server.macAddress != null) "  ·  ${stringResource(R.string.server_wol_badge)}" else "",
            selected = active,
            onClick = onSelect,
            modifier = Modifier.weight(1f),
        )
        TvActionButton(text = stringResource(R.string.settings_remove_server), onClick = onRemove)
    }
}



/** Global theme-music volume; shown once, on the Audio page. */
internal fun LazyListScope.themeMusicVolumeItems(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    item {
        SettingsSelect(
            label = stringResource(R.string.settings_theme_music_volume),
            description = stringResource(R.string.settings_theme_music_volume_hint),
            options = listOf(20, 40, 60, 80).map { percent ->
                SettingsSelectOption(
                    text = "$percent%",
                    selected = uiState.themeMusicVolumePercent == percent,
                    onClick = { viewModel.onThemeMusicVolumePercentChanged(percent) },
                )
            },
        )
    }
}


@Composable
internal fun SavedEmbyServerRow(server: EmbyServerProfile, active: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ServerSurface(
            title = (if (active) "●  " else "") + server.name,
            subtitle = "${if (server.useHttps) "https" else "http"}://${server.host}:${server.port}${server.basePath}" +
                if (server.macAddress != null) "  ·  ${stringResource(R.string.server_wol_badge)}" else "",
            selected = active,
            onClick = onSelect,
            modifier = Modifier.weight(1f),
        )
        TvActionButton(text = stringResource(R.string.settings_remove_server), onClick = onRemove)
    }
}


@Composable
internal fun SavedJellyfinServerRow(server: JellyfinServerProfile, active: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ServerSurface(
            title = (if (active) "●  " else "") + server.name,
            subtitle = "${if (server.useHttps) "https" else "http"}://${server.host}:${server.port}${server.basePath}" +
                if (server.macAddress != null) "  ·  ${stringResource(R.string.server_wol_badge)}" else "",
            selected = active,
            onClick = onSelect,
            modifier = Modifier.weight(1f),
        )
        TvActionButton(text = stringResource(R.string.settings_remove_server), onClick = onRemove)
    }
}


@Composable
internal fun DiscoveredServerButton(server: DiscoveredPlexServer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ServerSurface(
        title = server.name,
        subtitle = "${server.host}:${server.port}" + (server.version?.let { " · $it" } ?: ""),
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Moves DPAD focus onto the first result of a server search that just finished, once. */
@Composable
private fun Modifier.focusFirstDiscovered(index: Int, system: String, uiState: SettingsUiState, viewModel: SettingsViewModel): Modifier {
    if (index != 0) return this
    val requester = remember { FocusRequester() }
    val pending = uiState.discoveredFocusSystem == system
    LaunchedEffect(pending) {
        if (!pending) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        viewModel.consumeDiscoveredFocus(system)
    }
    return focusRequester(requester)
}


@Composable
internal fun ServerSurface(
    title: String,
    subtitle: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiSounds = com.ambient.tv.ui.sound.LocalUiSoundPlayer.current
    val activate = com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = onClick)
    var focused by remember { mutableStateOf(false) }
    val serverShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = activate,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape = serverShape),
        modifier = modifier.activateOnTvKey(onClick = activate).onFocusChanged { focused = it.isFocused; if (it.isFocused) uiSounds?.playFocus() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .appControlSurface(focused, selected, shape = serverShape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** YouTube "Watch on TV": switch, link code for the phone and unlinking. */
@Composable
private fun YouTubeTvLinkSection(viewModel: SettingsViewModel) {
    var enabled by remember { mutableStateOf(viewModel.youtubeTvLinkEnabled()) }
    var showCode by remember { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.youtube_link_title), subtitle = stringResource(R.string.youtube_link_hint)) {
        TvSettingRow(
            title = stringResource(R.string.youtube_link_enabled),
            body = stringResource(R.string.youtube_link_enabled_hint),
            checked = enabled,
            onCheckedChange = { enabled = it; viewModel.setYouTubeTvLinkEnabled(it) },
        )
        if (enabled) {
            TvActionButton(stringResource(R.string.youtube_link_show_code), { showCode = true })
            TvActionButton(stringResource(R.string.youtube_link_reset), viewModel::unlinkYouTubeDevices)
        }
    }
    if (showCode) {
        // null while loading, "" when no code could be fetched.
        var code by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { code = viewModel.youtubeLinkCode().orEmpty() }
        AppDialog(
            onDismiss = { showCode = false },
            title = stringResource(R.string.youtube_link_code_title),
            footer = { TvActionButton(stringResource(R.string.settings_home_genre_done), { showCode = false }, selected = true) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.youtube_link_code_instructions), style = MaterialTheme.typography.bodyLarge)
                when (val value = code) {
                    null -> Text(stringResource(R.string.youtube_link_code_loading), style = MaterialTheme.typography.bodyMedium)
                    "" -> Text(stringResource(R.string.youtube_link_code_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    else -> Text(value.replace('-', ' '), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

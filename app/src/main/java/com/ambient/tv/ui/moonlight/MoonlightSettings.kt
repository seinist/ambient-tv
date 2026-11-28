package com.ambient.tv.ui.moonlight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.settings.LocalSettingsNestedBack
import com.ambient.tv.moonlight.MoonlightStreamProfile
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ambient.tv.ui.common.AppDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.SettingsSubheader

/** Feature-gated host setup; pairing and catalogs require native protocol, never a fake Play. */
@Composable
fun MoonlightSettingsSection(
    nameField: @Composable () -> Unit,
    model: MoonlightSettingsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    var page by rememberSaveable { mutableStateOf("root") }
    var profileHostId by rememberSaveable { mutableStateOf("") }
    val parentBack = LocalSettingsNestedBack.current
    fun back() { page = "root" }
    SideEffect { parentBack?.onBack = if (page == "root") null else { { back() } } }
    DisposableEffect(parentBack) { onDispose { parentBack?.onBack = null } }
    var forgetHost by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var hostname by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("47989") }
    var name by rememberSaveable { mutableStateOf("") }
    var mac by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page == "root" || !state.enabled) {
            TvSettingRow(stringResource(R.string.moonlight_enable), stringResource(R.string.moonlight_settings_hint),
                state.enabled, model::setEnabled)
            if (!state.enabled) return@Column
            nameField()
            com.ambient.tv.ui.common.LocalNetworkPermissionSetting()
            Text(stringResource(if (state.nativeAvailable) R.string.moonlight_native_notice else R.string.moonlight_experimental_notice),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsNavigationRow(title = stringResource(R.string.moonlight_hosts), onClick = { page = "hosts" })
            SettingsNavigationRow(title = stringResource(R.string.moonlight_quality_title), onClick = { page = "quality" })
            SettingsNavigationRow(title = stringResource(R.string.moonlight_display_title), onClick = { page = "display" })
            return@Column
        }
        TvActionButton(stringResource(R.string.back), { back() })
        if (page == "quality" || page == "display") {
            val selected = state.hosts.firstOrNull { it.id == profileHostId }?.id.orEmpty()
            val options = listOf("" to stringResource(R.string.moonlight_profile_global)) +
                state.hosts.map { it.id to it.name.ifBlank { it.host } }
            PlayerDropUpSelector(label = stringResource(R.string.moonlight_profile_scope),
                value = options.first { it.first == selected }.second, options = options, selectedKey = selected,
                dropUp = false, selectorKey = "moonlight-profile-scope", onSelected = { profileHostId = it })
            val overridden = selected in state.hostProfiles
            if (selected.isNotEmpty()) TvSettingRow(
                stringResource(R.string.moonlight_profile_override), stringResource(R.string.moonlight_profile_override_hint),
                overridden, { enabled -> model.updateHostProfile(selected, if (enabled) state.streamProfile else null) })
            if (selected.isEmpty() || overridden) {
                val profile = state.hostProfiles[selected] ?: state.streamProfile
                val update: (MoonlightStreamProfile) -> Unit = {
                    if (selected.isEmpty()) model.updateProfile(it) else model.updateHostProfile(selected, it)
                }
                if (page == "quality") MoonlightStreamSettingsSection(profile, update, includeDisplay = false)
                else MoonlightDisplaySettingsSection(profile, update)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return@Column
        }
        SettingsSubheader(stringResource(R.string.moonlight_hosts))
        state.hosts.forEach { host ->
            SettingsGroup(card = true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(host.name.ifBlank { host.host }, style = MaterialTheme.typography.titleMedium)
                    Text(com.ambient.tv.moonlight.MoonlightHostAddress.displayHost(host.host) + ":" + host.port,
                        style = MaterialTheme.typography.bodySmall)
                    state.status[host.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.nativeAvailable) {
                        val paired = host.id in state.pairedIds
                        Text(stringResource(if (paired) R.string.moonlight_paired_local else R.string.moonlight_not_paired),
                            style = MaterialTheme.typography.bodySmall)
                        if (state.pairingHostId == host.id && state.pairingPin != null) {
                            Text(stringResource(R.string.moonlight_pair_pin, state.pairingPin!!),
                                style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.moonlight_pair_help),
                                style = MaterialTheme.typography.bodySmall)
                            if (state.pairingBusy) Text(stringResource(R.string.moonlight_pair_waiting),
                                style = MaterialTheme.typography.bodySmall)
                            TvActionButton(stringResource(R.string.files_cancel), model::cancelPairing)
                        }
                        if (paired) {
                            state.appLists[host.id]?.let { apps ->
                                Text(stringResource(R.string.moonlight_app_count, apps.size),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.moonlight_catalog_preview),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    SettingsActionRow {
                        TvActionButton(stringResource(R.string.moonlight_check), { model.checkHost(host.id) },
                            loading = state.checkingId == host.id,
                            enabled = !state.pairingBusy && !state.resetBusy)
                        if (state.nativeAvailable) {
                            if (host.id !in state.pairedIds) {
                                TvActionButton(stringResource(R.string.moonlight_pair), { model.startPairing(host.id) },
                                    enabled = !state.pairingBusy, loading = state.pairingHostId == host.id)
                            } else {
                                TvActionButton(stringResource(R.string.moonlight_fetch_apps), { model.loadApplications(host.id) },
                                    loading = state.loadingAppsId == host.id,
                                    enabled = state.loadingAppsId == null && !state.pairingBusy && !state.resetBusy)
                                TvActionButton(stringResource(R.string.moonlight_forget_pairing), { forgetHost = host.id },
                                    enabled = !state.pairingBusy && !state.resetBusy && state.loadingAppsId == null)
                            }
                        }
                        TvActionButton(stringResource(R.string.moonlight_remove), { model.removeHost(host.id) },
                            enabled = !state.pairingBusy && !state.resetBusy)
                    }
                }
            }
        }

        SettingsGroup(card = true) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvTextField(hostname, { hostname = it }, stringResource(R.string.moonlight_address),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                TvTextField(port, { port = it }, stringResource(R.string.moonlight_port),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                TvTextField(name, { name = it }, stringResource(R.string.moonlight_name),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                TvTextField(mac, { mac = it }, stringResource(R.string.moonlight_mac),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                TvActionButton(stringResource(R.string.moonlight_add), {
                    val count = state.hosts.size
                    model.addHost(hostname, port, name, mac)
                    // The ViewModel reports validation errors without clearing the form.
                    if (model.state.value.hosts.size > count) {
                        hostname = ""; port = "47989"; name = ""; mac = ""
                    }
                }, enabled = hostname.isNotBlank())
            }
        }

        SettingsActionRow {
            TvActionButton(stringResource(R.string.moonlight_scan), model::discover, loading = state.scanning)
        }
        state.discovered.filterNot { candidate -> state.hosts.any { it.host == candidate.host && it.port == candidate.port } }
            .forEach { found ->
                TvActionButton(found.name.ifBlank { found.host } + " · " + found.host, {
                    model.addHost(found.host, found.port.toString(), found.name)
                })
            }
        if (state.nativeAvailable) {
            TvActionButton(stringResource(R.string.moonlight_reset_identity), { confirmReset = true },
                enabled = !state.pairingBusy && !state.resetBusy && state.loadingAppsId == null, loading = state.resetBusy)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    forgetHost?.let { id ->
        AppDialog(onDismiss = { forgetHost = null },
            title = stringResource(R.string.moonlight_forget_pairing),
            hint = stringResource(R.string.moonlight_forget_hint),
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), { forgetHost = null })
                TvActionButton(stringResource(R.string.moonlight_forget_pairing), {
                    forgetHost = null; model.forgetPairing(id)
                })
            }, content = null)
    }
    if (confirmReset) AppDialog(onDismiss = { confirmReset = false },
        title = stringResource(R.string.moonlight_reset_identity),
        hint = stringResource(R.string.moonlight_reset_hint),
        footer = {
            TvActionButton(stringResource(R.string.files_cancel), { confirmReset = false })
            TvActionButton(stringResource(R.string.moonlight_reset_identity), {
                confirmReset = false; model.resetClientIdentity()
            })
        }, content = null)
}

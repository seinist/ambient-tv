package com.ambient.tv.ui.moonlight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

/** Feature-gated setup for Moonlight hosts; intentionally no nonfunctional Play or Pair button. */
@Composable
fun MoonlightSettingsSection(
    nameField: @Composable () -> Unit,
    model: MoonlightSettingsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    var hostname by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("47989") }
    var name by rememberSaveable { mutableStateOf("") }
    var mac by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvSettingRow(stringResource(R.string.moonlight_enable), stringResource(R.string.moonlight_settings_hint),
            state.enabled, model::setEnabled)
        if (!state.enabled) return@Column
        nameField()
        Text(stringResource(R.string.moonlight_experimental_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsSubheader(stringResource(R.string.moonlight_hosts))
        state.hosts.forEach { host ->
            SettingsGroup(card = true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(host.name.ifBlank { host.host }, style = MaterialTheme.typography.titleMedium)
                    Text(com.ambient.tv.moonlight.MoonlightHostAddress.displayHost(host.host) + ":" + host.port,
                        style = MaterialTheme.typography.bodySmall)
                    state.status[host.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    SettingsActionRow {
                        TvActionButton(stringResource(R.string.moonlight_check), { model.checkHost(host.id) },
                            loading = state.checkingId == host.id)
                        TvActionButton(stringResource(R.string.moonlight_remove), { model.removeHost(host.id) })
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
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

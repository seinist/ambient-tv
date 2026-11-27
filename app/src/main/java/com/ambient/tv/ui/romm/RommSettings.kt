package com.ambient.tv.ui.romm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.QrCodeDialog
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.systemString
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.SettingsSubheader

/**
 * Settings › RomM. Sign-in is a QR code on RomM 5 (approved on a phone), a pairing code or client
 * token on RomM 4.8+, or user name and password on any version. The name field and the menu
 * options are the ones every system page has ([nameField]).
 */
@Composable
// Keep value-based password masking while the field uses the existing editing API.
@Suppress("DEPRECATION")
fun RommSettingsSection(
    nameField: @Composable () -> Unit,
    model: RommSettingsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvSettingRow(
            systemString(MediaSystem.ROMM, R.string.romm_enable),
            stringResource(R.string.romm_enable_hint),
            state.enabled,
            model::setEnabled,
        )
        if (!state.enabled) return@Column
        nameField()
        SettingsSubheader(stringResource(R.string.settings_system_server_access))
        SettingsGroup(card = true) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.signedIn) {
                    // The sign-in belongs to this address: another server needs signing out first.
                    Text(state.url, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.romm_url_signed_in_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TvTextField(
                        value = state.url, onValueChange = model::setUrl,
                        label = stringResource(R.string.romm_url),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.romm_url_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.signedIn) {
            Text(stringResource(R.string.romm_signed_in_as, state.username.ifBlank { "–" },
                state.serverVersion.ifBlank { "?" }), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.romm_library_summary, state.platformCount, state.gameCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsActionRow {
                TvActionButton(stringResource(R.string.settings_reconnect_server), model::testConnection,
                    loading = state.busy && state.pairing == null)
                TvActionButton(stringResource(R.string.romm_reload_library), model::refreshLibrary,
                    icon = Icons.Filled.Refresh, enabled = !state.busy)
                TvActionButton(stringResource(R.string.romm_sign_out), model::signOut,
                    icon = Icons.AutoMirrored.Filled.Logout, enabled = !state.busy)
            }
        } else {
            SettingsActionRow {
                TvActionButton(stringResource(R.string.romm_pair), model::startPairing,
                    icon = Icons.Filled.QrCode2, loading = state.busy && state.pairing == null, prominent = true)
            }
            Text(stringResource(R.string.romm_pair_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsGroup(title = stringResource(R.string.romm_other_sign_in), collapsible = true) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvTextField(value = code, onValueChange = { code = it },
                        label = stringResource(R.string.romm_code_or_token),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth())
                    TvActionButton(stringResource(R.string.romm_sign_in_code), { model.signInWithCode(code) },
                        enabled = code.isNotBlank() && !state.busy)
                    TvTextField(value = username, onValueChange = { username = it },
                        label = stringResource(R.string.settings_tvh_username),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth())
                    TvTextField(value = password, onValueChange = { password = it },
                        label = stringResource(R.string.settings_tvh_password),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth())
                    TvActionButton(stringResource(R.string.romm_sign_in_password), {
                        model.signInWithPassword(username, password)
                        password = ""
                    }, enabled = username.isNotBlank() && password.isNotEmpty() && !state.busy)
                }
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        SettingsSubheader(stringResource(R.string.romm_data_title))
        Text(stringResource(R.string.romm_data_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.pairing?.let { pairing ->
        QrCodeDialog(
            title = stringResource(R.string.romm_pair_title),
            hint = stringResource(R.string.romm_pair_dialog_hint),
            payload = pairing.address,
            code = pairing.code,
            onDismiss = model::cancelPairing,
        ) {
            TvActionButton(stringResource(R.string.files_cancel), onClick = model::cancelPairing)
        }
    }
}

package com.ambient.tv.trailers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.settings.SettingsGroup
import com.ambient.tv.ui.settings.SettingsSelect
import com.ambient.tv.ui.settings.SettingsSelectOption
import com.ambient.tv.ui.settings.SettingsSubheader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@Composable
fun TrailerSettings(onConnected: () -> Unit = {}) {
    val context = LocalContext.current
    val dependencies = remember { EntryPointAccessors.fromApplication(context.applicationContext, TrailerDependencies::class.java) }
    val repository = dependencies.trailers()
    val preferences = dependencies.preferences()
    val version by repository.configuration.collectAsState()
    val configured = remember(version) { repository.configured }
    // Credentials never enter saved instance state or appear as pre-filled text.
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    var enabled by remember { mutableStateOf(preferences.trailersEnabled) }
    var language by remember { mutableStateOf(preferences.trailerLanguage) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SystemWordmark(MediaSystem.TRAILERS, stringResource(R.string.trailers_title), height = 24.dp)
        SettingsSubheader(stringResource(R.string.settings_section_general_connection))
        SettingsGroup(card = true) {
            Text(stringResource(R.string.trailers_key_help))
            if (configured) Text(stringResource(R.string.trailers_connected), color = MaterialTheme.colorScheme.primary)
            TvTextField(value = input, onValueChange = { input = it; failure = null }, label = stringResource(R.string.trailers_api_key),
                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            failure?.let { Text(stringResource(it)) }
            SettingsActionRow {
                TvActionButton(text = stringResource(R.string.trailers_connect), enabled = input.isNotBlank() && !busy, loading = busy, onClick = {
                    val pending = input
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            repository.connect(pending).onSuccess {
                                input = ""; enabled = true; preferences.trailersEnabled = true; onConnected()
                            }.onFailure {
                                failure = if (it is IllegalArgumentException || it is TrailerRequestFailed && it.status in setOf(401, 403))
                                    R.string.trailers_key_invalid else R.string.trailers_failed
                            }
                        } finally { busy = false }
                    }
                })
                if (configured) TvActionButton(text = stringResource(R.string.trailers_disconnect), enabled = !busy,
                    onClick = { repository.disconnect(); input = ""; enabled = false; preferences.trailersEnabled = false; onConnected() })
            }
        }
        SettingsSubheader(stringResource(R.string.settings_section_general_trailer_behavior))
        TvSettingRow(stringResource(R.string.trailers_enable), checked = enabled, onCheckedChange = {
            enabled = it; preferences.trailersEnabled = it; onConnected()
        })
        SettingsSelect(label = stringResource(R.string.trailers_language), options =
            listOf("auto" to stringResource(R.string.trailers_auto), "de" to "Deutsch", "en" to "English").map { (key, label) ->
                SettingsSelectOption(text = label, selected = language == key, onClick = {
                    language = key; preferences.trailerLanguage = key; onConnected()
                })
            },
        )
    }
}

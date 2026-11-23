package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.games.metadata.GameMetadataCredentials
import com.ambient.tv.games.metadata.GameMetadataOptions
import com.ambient.tv.games.metadata.GameMetadataProviderIds
import com.ambient.tv.games.metadata.GameMetadataSettings
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GameMetadataSettingsViewModel @Inject constructor(
    val settings: GameMetadataSettings,
) : ViewModel() {
    fun save(options: GameMetadataOptions, credentials: GameMetadataCredentials): Boolean = try {
        credentials.validated()
        settings.saveCredentials(credentials)
        settings.update(options)
        true
    } catch (_: IllegalArgumentException) { false }
}

/** Uses the same keyboard, TV focus and handheld dialog controls as the media settings. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GameMetadataSettingsPanel(
    onDismiss: () -> Unit,
    model: GameMetadataSettingsViewModel = hiltViewModel(),
) {
    var options by remember { mutableStateOf(model.settings.options()) }
    // Deliberately not rememberSaveable: account secrets do not belong in saved instance state.
    var credentials by remember { mutableStateOf(model.settings.editableCredentials()) }
    var invalid by remember { mutableStateOf(false) }
    val builtInScreenScraper = model.settings.screenScraperBuiltIn
    val builtInGamesDb = model.settings.theGamesDbBuiltIn
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.game_metadata_settings_title),
        footer = {
            TvActionButton(stringResource(R.string.settings_link_cancel), onDismiss)
            TvActionButton(stringResource(R.string.item_edit_save), onClick = {
                if (model.save(options, credentials)) onDismiss() else invalid = true
            }, prominent = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.game_metadata_settings_provider), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("none" to R.string.game_metadata_settings_off,
                    GameMetadataProviderIds.LIBRETRO to R.string.game_metadata_settings_libretro,
                    GameMetadataProviderIds.SCREENSCRAPER to R.string.game_metadata_settings_screenscraper,
                    GameMetadataProviderIds.THEGAMESDB to R.string.game_metadata_settings_thegamesdb).forEach { (id, label) ->
                    TvChip(stringResource(label), selected = options.provider == id,
                        onClick = { options = options.copy(provider = id) })
                }
            }
            Text(stringResource(when (options.provider) {
                GameMetadataProviderIds.SCREENSCRAPER -> R.string.game_metadata_settings_ss_hint
                GameMetadataProviderIds.THEGAMESDB -> R.string.game_metadata_settings_tgdb_hint
                "none" -> R.string.game_metadata_settings_off_hint
                else -> R.string.game_metadata_settings_libretro_hint
            }), style = MaterialTheme.typography.bodySmall)
            TvChip(stringResource(R.string.game_metadata_settings_automatic), selected = options.automatic,
                enabled = options.enabled, onClick = { options = options.copy(automatic = !options.automatic) })
            if (options.enabled) Text(stringResource(R.string.game_metadata_settings_automatic_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.game_metadata_settings_region), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("eu" to R.string.game_metadata_region_eu, "de" to R.string.game_metadata_region_de,
                    "us" to R.string.game_metadata_region_us, "jp" to R.string.game_metadata_region_jp,
                    "wor" to R.string.game_metadata_region_world).forEach { (id, label) ->
                    TvChip(stringResource(label), selected = options.region == id,
                        onClick = { options = options.copy(region = id) })
                }
            }
            Text(stringResource(R.string.game_metadata_settings_language), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameMetadataOptions.LANGUAGES.forEach { language ->
                    val name = if (language == "auto") stringResource(R.string.game_metadata_language_auto)
                        else java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.forLanguageTag(language))
                    TvChip(name, selected = options.language == language,
                        onClick = { options = options.copy(language = language) })
                }
            }
            when (options.provider) {
                GameMetadataProviderIds.SCREENSCRAPER -> {
                    if (builtInScreenScraper) Text(stringResource(R.string.game_metadata_settings_builtin), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(stringResource(R.string.game_metadata_settings_ss_setup_hint), style = MaterialTheme.typography.bodySmall)
                        TvTextField(credentials.screenScraperDevId.orEmpty(), {
                            credentials = credentials.copy(screenScraperDevId = it.trim().takeIf(String::isNotEmpty)); invalid = false
                        }, stringResource(R.string.game_metadata_settings_ss_dev_id), Modifier.fillMaxWidth())
                        TvTextField(credentials.screenScraperDevPassword.orEmpty(), {
                            credentials = credentials.copy(screenScraperDevPassword = it.takeIf(String::isNotEmpty)); invalid = false
                        }, stringResource(R.string.game_metadata_settings_ss_dev_password), Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation())
                    }
                    Text(stringResource(R.string.game_metadata_settings_ss_account_hint), style = MaterialTheme.typography.bodySmall)
                    TvTextField(credentials.screenScraperUser.orEmpty(), {
                        credentials = credentials.copy(screenScraperUser = it.trim().takeIf(String::isNotEmpty)); invalid = false
                    }, stringResource(R.string.game_metadata_settings_ss_user), Modifier.fillMaxWidth())
                    TvTextField(credentials.screenScraperPassword.orEmpty(), {
                        credentials = credentials.copy(screenScraperPassword = it.takeIf(String::isNotEmpty)); invalid = false
                    }, stringResource(R.string.game_metadata_settings_ss_password), Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation())
                }
                GameMetadataProviderIds.THEGAMESDB -> {
                    if (builtInGamesDb) Text(stringResource(R.string.game_metadata_settings_builtin), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(stringResource(R.string.game_metadata_settings_tgdb_setup_hint), style = MaterialTheme.typography.bodySmall)
                        TvTextField(credentials.theGamesDbApiKey.orEmpty(), {
                            credentials = credentials.copy(theGamesDbApiKey = it.trim().takeIf(String::isNotEmpty)); invalid = false
                        }, stringResource(R.string.game_metadata_settings_tgdb_key), Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation())
                    }
                }
            }
            if (invalid) Text(stringResource(R.string.game_metadata_settings_invalid), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (options.provider in setOf(GameMetadataProviderIds.SCREENSCRAPER, GameMetadataProviderIds.THEGAMESDB))
                Text(stringResource(R.string.game_metadata_settings_credentials_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

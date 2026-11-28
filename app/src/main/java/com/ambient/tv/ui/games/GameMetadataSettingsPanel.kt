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
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.settings.SettingsSelect
import com.ambient.tv.ui.settings.SettingsSelectOption
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
fun GameMetadataSettingsPanel(
    onDismiss: () -> Unit,
    model: GameMetadataSettingsViewModel = hiltViewModel(),
) {
    GameMetadataSettingsDialog(
        initialOptions = model.settings.options(),
        initialCredentials = model.settings.editableCredentials(),
        builtInScreenScraper = model.settings.screenScraperBuiltIn,
        builtInGamesDb = model.settings.theGamesDbBuiltIn,
        onDismiss = onDismiss,
        onSave = model::save,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
// Keep value-based password masking while the field uses the existing editing API.
@Suppress("DEPRECATION")
internal fun GameMetadataSettingsDialog(
    initialOptions: GameMetadataOptions,
    initialCredentials: GameMetadataCredentials,
    builtInScreenScraper: Boolean,
    builtInGamesDb: Boolean,
    onDismiss: () -> Unit,
    onSave: (GameMetadataOptions, GameMetadataCredentials) -> Boolean,
) {
    var options by remember { mutableStateOf(initialOptions) }
    // Deliberately not rememberSaveable: account secrets do not belong in saved instance state.
    var credentials by remember { mutableStateOf(initialCredentials) }
    var invalid by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.game_metadata_settings_title),
        footer = {
            TvActionButton(stringResource(R.string.settings_link_cancel), onDismiss)
            TvActionButton(stringResource(R.string.item_edit_save), onClick = {
                if (onSave(options, credentials)) onDismiss() else invalid = true
            }, prominent = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.game_metadata_scope_all), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.game_metadata_settings_provider), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("none" to R.string.game_metadata_settings_off,
                    GameMetadataProviderIds.LIBRETRO to R.string.game_metadata_settings_libretro,
                    GameMetadataProviderIds.SCREENSCRAPER to R.string.game_metadata_settings_screenscraper,
                    GameMetadataProviderIds.THEGAMESDB to R.string.game_metadata_settings_thegamesdb).forEach { (id, label) ->
                    TvChip(stringResource(label), selected = options.provider == id,
                        onClick = { options = options.copy(provider = id); accountOpen = false })
                }
            }
            Text(stringResource(when (options.provider) {
                GameMetadataProviderIds.SCREENSCRAPER -> R.string.game_metadata_settings_ss_hint
                GameMetadataProviderIds.THEGAMESDB -> R.string.game_metadata_settings_tgdb_hint
                "none" -> R.string.game_metadata_settings_off_hint
                else -> R.string.game_metadata_settings_libretro_hint
            }), style = MaterialTheme.typography.bodySmall)
            TvSettingRow(
                title = stringResource(R.string.game_metadata_settings_automatic),
                body = stringResource(R.string.game_metadata_settings_automatic_hint),
                checked = options.automatic,
                enabled = options.enabled,
                onCheckedChange = { options = options.copy(automatic = it) },
            )
            if (options.enabled) {
                SettingsSelect(
                    label = stringResource(R.string.game_metadata_settings_region),
                    options = listOf("eu" to R.string.game_metadata_region_eu,
                        "de" to R.string.game_metadata_region_de,
                        "us" to R.string.game_metadata_region_us,
                        "jp" to R.string.game_metadata_region_jp,
                        "wor" to R.string.game_metadata_region_world).map { (id, label) ->
                        SettingsSelectOption(stringResource(label), options.region == id,
                            onClick = { options = options.copy(region = id) })
                    },
                )
                SettingsSelect(
                    label = stringResource(R.string.game_metadata_settings_language),
                    options = GameMetadataOptions.LANGUAGES.map { language ->
                        val name = if (language == "auto") stringResource(R.string.game_metadata_language_auto)
                            else java.util.Locale.forLanguageTag(language)
                                .getDisplayLanguage(java.util.Locale.forLanguageTag(language))
                        SettingsSelectOption(name, options.language == language,
                            onClick = { options = options.copy(language = language) })
                    },
                )
            }
            if (options.provider == GameMetadataProviderIds.SCREENSCRAPER ||
                options.provider == GameMetadataProviderIds.THEGAMESDB) {
                val builtIn = if (options.provider == GameMetadataProviderIds.SCREENSCRAPER)
                    builtInScreenScraper else builtInGamesDb
                if (builtIn) Text(stringResource(R.string.game_metadata_settings_builtin),
                    style = MaterialTheme.typography.bodySmall)
                SettingsNavigationRow(
                    title = stringResource(R.string.game_metadata_account_settings),
                    summary = stringResource(R.string.game_metadata_account_settings_hint),
                    onClick = { accountOpen = !accountOpen },
                )
                if (accountOpen) {
                    when (options.provider) {
                        GameMetadataProviderIds.SCREENSCRAPER -> {
                            if (!builtInScreenScraper) {
                                Text(stringResource(R.string.game_metadata_settings_ss_setup_hint),
                                    style = MaterialTheme.typography.bodySmall)
                                TvTextField(credentials.screenScraperDevId.orEmpty(), {
                                    credentials = credentials.copy(screenScraperDevId = it.trim().takeIf(String::isNotEmpty)); invalid = false
                                }, stringResource(R.string.game_metadata_settings_ss_dev_id), Modifier.fillMaxWidth())
                                TvTextField(credentials.screenScraperDevPassword.orEmpty(), {
                                    credentials = credentials.copy(screenScraperDevPassword = it.takeIf(String::isNotEmpty)); invalid = false
                                }, stringResource(R.string.game_metadata_settings_ss_dev_password), Modifier.fillMaxWidth(),
                                    visualTransformation = PasswordVisualTransformation())
                            }
                            Text(stringResource(R.string.game_metadata_settings_ss_account_hint),
                                style = MaterialTheme.typography.bodySmall)
                            TvTextField(credentials.screenScraperUser.orEmpty(), {
                                credentials = credentials.copy(screenScraperUser = it.trim().takeIf(String::isNotEmpty)); invalid = false
                            }, stringResource(R.string.game_metadata_settings_ss_user), Modifier.fillMaxWidth())
                            TvTextField(credentials.screenScraperPassword.orEmpty(), {
                                credentials = credentials.copy(screenScraperPassword = it.takeIf(String::isNotEmpty)); invalid = false
                            }, stringResource(R.string.game_metadata_settings_ss_password), Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation())
                        }
                        GameMetadataProviderIds.THEGAMESDB -> if (!builtInGamesDb) {
                            Text(stringResource(R.string.game_metadata_settings_tgdb_setup_hint),
                                style = MaterialTheme.typography.bodySmall)
                            TvTextField(credentials.theGamesDbApiKey.orEmpty(), {
                                credentials = credentials.copy(theGamesDbApiKey = it.trim().takeIf(String::isNotEmpty)); invalid = false
                            }, stringResource(R.string.game_metadata_settings_tgdb_key), Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation())
                        }
                    }
                    Text(stringResource(R.string.game_metadata_settings_credentials_hint),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (invalid) Text(stringResource(R.string.game_metadata_settings_invalid), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

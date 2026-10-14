package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvSettingRow

@Composable
internal fun PhotoSettingsSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.ambient.tv.ui.common.RightPanelValueSlider(
            title = stringResource(R.string.settings_photos_interval),
            value = uiState.photoSlideshowIntervalSeconds,
            valueRange = 3..60,
            valueLabel = stringResource(R.string.settings_photos_interval_value, uiState.photoSlideshowIntervalSeconds),
            onValueChange = viewModel::onPhotoSlideshowIntervalChanged,
        )
        SettingsGroup(title = stringResource(R.string.settings_photos_transition)) {
            SettingsSelect(options = buildList {
                listOf("fade" to R.string.settings_photos_transition_fade, "slide" to R.string.settings_photos_transition_slide, "none" to R.string.settings_photos_transition_none).forEach { (key, label) ->
                    add(SettingsSelectOption(
                        stringResource(label),
                        selected = uiState.photoTransitionKey == key,
                        onClick = { viewModel.onPhotoTransitionChanged(key) },
                    ))
                }
            })
        }
        TvSettingRow(
            stringResource(R.string.settings_photos_ken_burns),
            stringResource(R.string.settings_photos_ken_burns_hint),
            uiState.photoKenBurns,
            viewModel::onPhotoKenBurnsChanged,
        )
        TvSettingRow(
            stringResource(R.string.photos_shuffle),
            stringResource(R.string.settings_photos_shuffle_hint),
            uiState.photoShuffle,
            viewModel::onPhotoShuffleChanged,
        )
        TvSettingRow(
            stringResource(R.string.photos_subfolders),
            stringResource(R.string.photos_subfolders_hint),
            uiState.photoIncludeSubfolders,
            viewModel::onPhotoIncludeSubfoldersChanged,
        )
        TvSettingRow(
            stringResource(R.string.photos_info),
            stringResource(R.string.settings_photos_info_hint),
            uiState.photoShowInfo,
            viewModel::onPhotoShowInfoChanged,
        )
        SettingsGroup(title = stringResource(R.string.settings_photos_sort)) {
            SettingsSelect(options = buildList {
                com.ambient.tv.data.photos.PhotoSort.entries.forEach { sort ->
                    add(SettingsSelectOption(
                        stringResource(with(com.ambient.tv.ui.photos.PhotoSortLabels) { sort.labelRes() }),
                        selected = uiState.photoSortKey == sort.key,
                        onClick = { viewModel.onPhotoSortChanged(sort.key) },
                    ))
                }
            })
        }
    }
}

@Composable
internal fun ScreensaverPhotoSourcePicker(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val libraries by viewModel.photoLibraries.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadPhotoLibraries() }
    SettingsGroup(
        title = stringResource(R.string.settings_screensaver_photo_album),
        subtitle = stringResource(R.string.settings_screensaver_photo_album_hint),
    ) {
        SettingsSelect(options = buildList {
            run {
                add(SettingsSelectOption(
                    stringResource(R.string.settings_screensaver_photo_recent),
                    selected = uiState.screensaverPhotoAlbumKey.isBlank(),
                    onClick = { viewModel.onScreensaverPhotoAlbumChanged("", "") },
                ))
            }
            libraries.forEach { library ->
                add(SettingsSelectOption(
                    library.title,
                    selected = uiState.screensaverPhotoAlbumKey == library.key,
                    onClick = { viewModel.onScreensaverPhotoAlbumChanged(library.key, library.title) },
                ))
            }
        })
    }
}

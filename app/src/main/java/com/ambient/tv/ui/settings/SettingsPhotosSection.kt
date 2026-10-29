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
import com.ambient.tv.data.photos.PhotoSort
import com.ambient.tv.ui.common.SortOrderToggle
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.photos.photoSortOrderOptions

@Composable
internal fun PhotoSettingsSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSubheader(stringResource(R.string.settings_section_general_slideshow))
        com.ambient.tv.ui.common.RightPanelValueSlider(
            title = stringResource(R.string.settings_photos_interval),
            value = uiState.photoSlideshowIntervalSeconds,
            valueRange = 3..60,
            valueLabel = stringResource(R.string.settings_photos_interval_value, uiState.photoSlideshowIntervalSeconds),
            onValueChange = viewModel::onPhotoSlideshowIntervalChanged,
        )
        SettingsSelect(label = stringResource(R.string.settings_photos_transition), options = buildList {
            listOf("fade" to R.string.settings_photos_transition_fade, "slide" to R.string.settings_photos_transition_slide, "none" to R.string.settings_photos_transition_none).forEach { (key, label) ->
                add(SettingsSelectOption(
                    stringResource(label),
                    selected = uiState.photoTransitionKey == key,
                    onClick = { viewModel.onPhotoTransitionChanged(key) },
                ))
            }
        })
        TvSettingRow(
            stringResource(R.string.settings_photos_ken_burns),
            stringResource(R.string.settings_photos_ken_burns_hint),
            uiState.photoKenBurns,
            viewModel::onPhotoKenBurnsChanged,
        )
        SettingsSubheader(stringResource(R.string.settings_section_general_photo_order))
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
        SettingsGroup {
            val sort = PhotoSort.fromKey(uiState.photoSortKey)
            SettingsSelect(label = stringResource(R.string.settings_photos_sort), options = listOf(
                SettingsSelectOption(
                    stringResource(R.string.photos_sort_date),
                    selected = sort.byDate,
                    onClick = { if (!sort.byDate) viewModel.onPhotoSortChanged(PhotoSort.of(byDate = true, descending = true).key) },
                ),
                SettingsSelectOption(
                    stringResource(R.string.photos_sort_name),
                    selected = !sort.byDate,
                    onClick = { if (sort.byDate) viewModel.onPhotoSortChanged(PhotoSort.of(byDate = false, descending = false).key) },
                ),
            ))
            SortOrderToggle(
                orders = photoSortOrderOptions(sort),
                selectedKey = if (sort.descending) "DESCENDING" else "ASCENDING",
                onSelect = { viewModel.onPhotoSortChanged(PhotoSort.of(sort.byDate, it == "DESCENDING").key) },
                showTitle = true,
            )
        }
        SettingsSubheader(stringResource(R.string.settings_section_general_overlays))
        TvSettingRow(
            stringResource(R.string.photos_info),
            stringResource(R.string.settings_photos_info_hint),
            uiState.photoShowInfo,
            viewModel::onPhotoShowInfoChanged,
        )
    }
}

@Composable
internal fun ScreensaverPhotoSourcePicker(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val libraries by viewModel.photoLibraries.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadPhotoLibraries() }
    SettingsSelect(
        label = stringResource(R.string.settings_screensaver_photo_album),
        description = stringResource(R.string.settings_screensaver_photo_album_hint),
        options = buildList {
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
        },
    )
}

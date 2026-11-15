package com.ambient.tv.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.mobile.isTelevision

@Composable
internal fun UiRenderResolutionSettings(selected: String, onSelected: (String) -> Unit) {
    val television = isTelevision(LocalContext.current)
    if (!television) return
    if (!UiRenderResolutionPolicy.isSupported(television, Build.VERSION.SDK_INT)) {
        SettingsGroup(
            title = stringResource(R.string.settings_ui_render_resolution),
            subtitle = stringResource(R.string.settings_ui_render_resolution_unavailable),
        ) {
            Text(stringResource(R.string.settings_ui_render_resolution_system), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val key = UiRenderResolutionPolicy.normalize(selected)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSelect(
            label = stringResource(R.string.settings_ui_render_resolution),
            description = stringResource(when (key) {
                UiRenderResolutionPolicy.FULL_HD -> R.string.settings_ui_render_resolution_1080_hint
                UiRenderResolutionPolicy.PANEL -> R.string.settings_ui_render_resolution_panel_hint
                else -> R.string.settings_ui_render_resolution_system_hint
            }),
            options = listOf(
                UiRenderResolutionPolicy.SYSTEM to R.string.settings_ui_render_resolution_system,
                UiRenderResolutionPolicy.FULL_HD to R.string.settings_ui_render_resolution_1080,
                UiRenderResolutionPolicy.PANEL to R.string.settings_ui_render_resolution_panel,
            ).map { (value, label) ->
                SettingsSelectOption(stringResource(label), selected = key == value, onClick = { onSelected(value) })
            },
        )
        SettingsHint(stringResource(R.string.settings_ui_render_resolution_restart))
        if (key != UiRenderResolutionPolicy.SYSTEM) {
            SettingsHint(stringResource(R.string.settings_ui_render_resolution_hdr_hint))
        }
    }
}

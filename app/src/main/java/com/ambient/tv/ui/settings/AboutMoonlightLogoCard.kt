package com.ambient.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.TvActionButton

@Composable
internal fun AboutMoonlightLogoCard() {
    var showNotice by remember { mutableStateOf(false) }
    SettingsGroup(
        title = stringResource(R.string.moonlight_logo_title),
        subtitle = "GNU GPL v3 (GPL-3.0)",
        card = true,
    ) {
        Text(
            stringResource(R.string.moonlight_logo_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsActionRow {
            TvActionButton(
                text = stringResource(R.string.settings_about_game_icons_details),
                onClick = { showNotice = true },
            )
        }
    }
    if (showNotice) {
        val resources = LocalContext.current.resources
        val notice = remember(resources) {
            resources.openRawResource(R.raw.moonlight_logo_notice).bufferedReader().use { it.readText() }
        }
        AppDialog(
            onDismiss = { showNotice = false },
            title = stringResource(R.string.moonlight_logo_title),
            fillHeight = true,
        ) {
            Text(notice, style = MaterialTheme.typography.bodySmall)
        }
    }
}

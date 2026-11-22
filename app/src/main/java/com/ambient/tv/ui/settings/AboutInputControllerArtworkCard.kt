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
internal fun AboutInputControllerArtworkCard() {
    var showNotice by remember { mutableStateOf(false) }
    SettingsGroup(
        title = stringResource(R.string.input_controller_artwork_title),
        subtitle = "Controllercons 2.1 · Duotone",
        card = true,
    ) {
        Text(
            stringResource(R.string.input_controller_artwork_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "SIL Open Font License 1.1 (OFL-1.1)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsActionRow {
            TvActionButton(
                text = stringResource(R.string.input_controller_artwork_details),
                onClick = { showNotice = true },
            )
        }
    }
    if (showNotice) {
        val resources = LocalContext.current.resources
        val notice = remember(resources) {
            resources.openRawResource(R.raw.input_controller_artwork_notice).bufferedReader().use { it.readText() }
        }
        val license = remember(resources) {
            resources.openRawResource(R.raw.input_controller_artwork_ofl_1_1).bufferedReader().use { it.readText() }
        }
        AppDialog(
            onDismiss = { showNotice = false },
            title = stringResource(R.string.input_controller_artwork_title),
            hint = stringResource(R.string.input_controller_artwork_modifications),
            fillHeight = true,
        ) {
            Text(notice, style = MaterialTheme.typography.bodySmall)
            Text(license, style = MaterialTheme.typography.bodySmall)
        }
    }
}

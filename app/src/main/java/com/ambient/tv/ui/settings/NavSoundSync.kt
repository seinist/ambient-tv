package com.ambient.tv.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.companion.*
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.ui.common.TvActionButton

@Composable
internal fun NavSoundSync(bank: String, volume: Float, enabled: Boolean) {
    if (!com.ambient.tv.ui.mobile.LocalHandheldUi.current) return
    val vm: CompanionViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val state by vm.manager.state.collectAsState()
    if (state.paired.isEmpty()) return
    var snapshot by remember { mutableStateOf<SettingsBackup?>(null) }
    TvActionButton(stringResource(R.string.nav_sync_title), {
        snapshot = SettingsBackup(exportedAtEpochMs = System.currentTimeMillis(),
            navSoundBank = com.ambient.tv.ui.sound.NavSoundBank.decode(bank).encode(),
            navSoundsVolume = volume, navSoundsEnabled = enabled)
    }, fillWidth = true)
    snapshot?.let { CompanionSyncDialog(vm, navSnapshot = it, onDismiss = { snapshot = null }) }
}

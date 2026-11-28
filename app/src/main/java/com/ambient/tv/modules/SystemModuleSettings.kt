package com.ambient.tv.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SystemModuleSettingsCard() {
    var open by remember { mutableStateOf(false) }
    SettingsNavigationRow(title = stringResource(R.string.module_settings_title), onClick = { open = true })
    if (open) ModuleDialog { open = false }
}

@Composable
private fun ModuleDialog(dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val states by SystemModuleAvailability.states.collectAsState()
    val modules = remember { SystemModuleHost.supported.sortedBy { it.id } }
    var selected by remember { mutableStateOf(modules.first()) }
    var installed by remember { mutableStateOf<SystemModuleBuild?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var transfer by remember { mutableStateOf<SystemModuleDownload?>(null) }
    val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(SystemModuleDownload.SEARCHING) }
    val progress by (transfer?.progress ?: idle).collectAsState()
    DisposableEffect(transfer) {
        val active = transfer
        onDispose { active?.cancel() }
    }

    LaunchedEffect(selected) {
        busy = true
        installed = null
        notice = null
        try {
            installed = withContext(Dispatchers.IO) { SystemModuleHost.selected(selected) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice = R.string.module_settings_error
        } finally {
            busy = false
        }
    }

    fun action(download: SystemModuleDownload? = null, block: (SystemModule) -> Boolean) {
        if (busy) return
        val module = selected
        transfer = download
        busy = true
        notice = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block(module) }
                installed = withContext(Dispatchers.IO) { SystemModuleHost.selected(module) }
                notice = if (!result) R.string.module_settings_no_candidate else R.string.module_settings_restart
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = if (download?.wasCancelled() == true) R.string.module_settings_cancelled else R.string.module_settings_error
            } finally {
                transfer = null
                busy = false
            }
        }
    }

    AppDialog(onDismiss = { if (!busy) dismiss() else transfer?.cancel() }, title = stringResource(R.string.module_settings_title), content = {
        Text(stringResource(R.string.module_settings_description))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modules.forEach { module ->
                val label = when (module) {
                    SystemModule.SHADERS -> stringResource(R.string.module_settings_shaders)
                    SystemModule.PROJECTM -> "ProjectM"
                    SystemModule.MPV -> "MPV"
                    SystemModule.VLC -> "VLC"
                    SystemModule.SPOTIFY -> "Spotify (Rusty)"
                    SystemModule.MONOGRAM -> "Monogram"
                    else -> module.id
                }
                TvActionButton(
                    text = if (module == selected) "✓ $label" else label,
                    onClick = { selected = module }, enabled = !busy,
                )
            }
        }
        Text(installed?.let { stringResource(R.string.module_settings_installed, it.version) }
            ?: stringResource(R.string.module_settings_no_override))
        val status = states.getValue(selected)
        val stateLabel = when (status.runtime) {
            ModuleRuntimeState.MISSING -> R.string.module_state_missing
            ModuleRuntimeState.BUNDLED_UNVERIFIED -> R.string.module_state_bundled
            ModuleRuntimeState.EXTERNAL_UNVERIFIED -> R.string.module_state_external
            ModuleRuntimeState.ACTIVE_BUNDLED -> R.string.module_state_active_bundled
            ModuleRuntimeState.ACTIVE_EXTERNAL -> R.string.module_state_active_external
            ModuleRuntimeState.FAILED -> R.string.module_state_failed
        }
        Text(stringResource(stateLabel), style = MaterialTheme.typography.bodyMedium)
        if (status.restartRequired) Text(stringResource(R.string.module_settings_restart))
        if (selected == SystemModule.SPOTIFY) Text(stringResource(R.string.module_settings_spotify_experimental), style = MaterialTheme.typography.bodySmall)
        if (!SystemModuleHost.downloadsEnabled) Text(stringResource(R.string.module_settings_no_key))
        notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        TvActionButton(text = stringResource(R.string.module_settings_install),
            enabled = !busy && SystemModuleHost.downloadsEnabled, loading = busy,
            onClick = {
                val download = SystemModuleDownload()
                action(download) { SystemModuleHost.installLatest(it, download) != null }
            })
        if (transfer != null) {
            Text(when (progress) {
                SystemModuleDownload.SEARCHING -> stringResource(R.string.module_settings_searching)
                SystemModuleDownload.VERIFYING -> stringResource(R.string.module_settings_verifying)
                SystemModuleDownload.COMMITTING -> stringResource(R.string.module_settings_committing)
                SystemModuleDownload.CANCELLING -> stringResource(R.string.module_settings_cancelling)
                else -> stringResource(R.string.module_settings_progress, progress)
            }, style = MaterialTheme.typography.bodyMedium)
            TvActionButton(text = stringResource(R.string.module_settings_cancel),
                enabled = progress != SystemModuleDownload.COMMITTING && progress != SystemModuleDownload.CANCELLING,
                onClick = { transfer?.cancel() })
        }
        TvActionButton(text = stringResource(R.string.module_settings_rollback), enabled = !busy && SystemModuleHost.downloadsEnabled,
            onClick = { action { SystemModuleHost.rollback(it) != null } })
        TvActionButton(text = stringResource(R.string.module_settings_remove), enabled = !busy && SystemModuleHost.downloadsEnabled && (installed != null || status.runtime == ModuleRuntimeState.FAILED),
            onClick = {
                action { module ->
                    SystemModuleHost.remove(module)
                    true
                }
            })
    })
}

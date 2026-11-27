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
    val modules = remember { SystemModuleHost.supported.sortedBy { it.id } }
    var selected by remember { mutableStateOf(modules.first()) }
    var installed by remember { mutableStateOf<SystemModuleBuild?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<Int?>(null) }

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

    fun action(block: (SystemModule) -> SystemModuleBuild?) {
        if (busy) return
        val module = selected
        busy = true
        notice = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block(module) }
                installed = withContext(Dispatchers.IO) { SystemModuleHost.selected(module) }
                notice = if (result == null) R.string.module_settings_no_candidate else R.string.module_settings_restart
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = R.string.module_settings_error
            } finally {
                busy = false
            }
        }
    }

    AppDialog(onDismiss = { if (!busy) dismiss() }, title = stringResource(R.string.module_settings_title), content = {
        Text(stringResource(R.string.module_settings_description))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modules.forEach { module ->
                val label = when (module) {
                    SystemModule.SHADERS -> stringResource(R.string.module_settings_shaders)
                    SystemModule.PROJECTM -> "ProjectM"
                    SystemModule.MPV -> "MPV"
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
        if (!SystemModuleHost.downloadsEnabled) Text(stringResource(R.string.module_settings_no_key))
        notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        TvActionButton(text = stringResource(R.string.module_settings_install),
            enabled = !busy && SystemModuleHost.downloadsEnabled, loading = busy,
            onClick = { action(SystemModuleHost::installLatest) })
        TvActionButton(text = stringResource(R.string.module_settings_rollback), enabled = !busy,
            onClick = { action(SystemModuleHost::rollback) })
        TvActionButton(text = stringResource(R.string.module_settings_remove), enabled = !busy && installed != null,
            onClick = {
                action { module ->
                    val old = installed
                    SystemModuleHost.remove(module)
                    old
                }
            })
    })
}

package com.ambient.tv.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(val updater: AppUpdater) : ViewModel()

@Composable
fun UpdateSettingsCard(viewModel: UpdateViewModel = hiltViewModel()) {
    val updater = viewModel.updater
    val state by updater.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvSettingRow(
            title = stringResource(R.string.update_automatic), body = stringResource(R.string.update_automatic_hint),
            checked = state.automatic, onCheckedChange = updater::setAutomatic,
        )
        TvActionButton(text = stringResource(R.string.update_title), onClick = { open = true },
            modifier = Modifier.fillMaxWidth(), fillWidth = true)
    }
    if (open) UpdateDialog(updater) { open = false }
}

@Composable
fun UpdateHomeCard(viewModel: UpdateViewModel = hiltViewModel()) {
    val updater = viewModel.updater
    val state by updater.state.collectAsState()
    val offer = state.offer
    var open by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(offer, state.automatic, state.skipped, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { updater.announceOnHome() }
    }
    if (offer != null && state.skipped != offer.manifest.versionCode) {
        TvActionButton(
            text = stringResource(R.string.update_home, offer.displayVersion),
            onClick = { open = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            fillWidth = true,
        )
    }
    if (open) UpdateDialog(updater) { open = false }
}

@Composable
private fun UpdateDialog(updater: AppUpdater, dismiss: () -> Unit) {
    val state by updater.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Dismissal is not evidence of successful installation. Keep the verified APK for retry.
    }
    fun install() {
        scope.launch {
            val intent = updater.installIntent() ?: return@launch
            runCatching { installer.launch(intent) }.onFailure { updater.reportInstallError() }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install() else updater.reportInstallError()
    }
    AppDialog(onDismiss = dismiss, title = stringResource(R.string.update_title), content = {
        Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium)
        if (state.checking) Text(stringResource(R.string.update_checking))
        if (state.checked && state.offer == null && state.error == null) Text(stringResource(R.string.update_current))
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        val offer = state.offer
        if (offer != null) {
            Text(stringResource(R.string.update_details, offer.displayVersion,
                android.text.format.Formatter.formatShortFileSize(context, offer.apk.size)),
                style = MaterialTheme.typography.titleMedium)
        }
        TvActionButton(text = stringResource(R.string.update_check), onClick = { updater.check() },
            enabled = !state.checking && !state.downloading && !state.preparingInstall, loading = state.checking)
        if (offer != null) {
            when {
                state.downloading -> {
                    Text(stringResource(R.string.update_progress, state.progress))
                    TvActionButton(text = stringResource(R.string.update_cancel), onClick = updater::cancelDownload)
                }
                state.ready -> TvActionButton(
                    text = stringResource(R.string.update_install), loading = state.preparingInstall,
                    enabled = !state.preparingInstall && !state.checking,
                    onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) install()
                        else runCatching {
                            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")))
                        }.onFailure { updater.reportInstallError() }
                    },
                )
                else -> TvActionButton(text = stringResource(R.string.update_download), onClick = updater::download,
                    enabled = !state.checking)
            }
            if (!state.downloading && !state.preparingInstall) {
                TvActionButton(text = stringResource(R.string.update_skip), onClick = { updater.skip(); dismiss() })
            }
        }
        Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall)
        if (offer != null && offer.notes.isNotBlank()) Text(offer.notes, style = MaterialTheme.typography.bodySmall)
    })
}

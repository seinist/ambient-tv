package com.ambient.tv.ui.settings

import com.ambient.tv.livetv.initializeTimeshiftStorage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.iptv.IptvSource
import com.ambient.tv.data.iptv.IptvStore
import com.ambient.tv.livetv.LiveTvRegistry
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IptvTestResult(val sourceId: String, val ok: Boolean, val channels: Int = 0, val guide: Boolean = false, val error: String? = null)

@HiltViewModel
class IptvSettingsViewModel @Inject constructor(
    private val store: IptvStore,
    private val preferences: com.ambient.tv.data.local.AppPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val registry: LiveTvRegistry,
) : ViewModel() {
    private val _sources = MutableStateFlow(store.sources())
    val sources = _sources.asStateFlow()
    private val _testing = MutableStateFlow<String?>(null)
    val testing = _testing.asStateFlow()
    private val _result = MutableStateFlow<IptvTestResult?>(null)
    val result = _result.asStateFlow()

    /** Validation errors come back as false so the dialog can stay open. */
    fun save(source: IptvSource): Boolean = runCatching { store.save(source); preferences.initializeTimeshiftStorage(context) }.isSuccess.also {
        _sources.value = store.sources()
        if (it) viewModelScope.launch { registry.configured().firstOrNull { repo -> repo.source.id == "iptv:${source.id}" }?.invalidateAll() }
    }

    fun remove(id: String) { store.remove(id); _sources.value = store.sources() }

    /** False when the form is not valid (shown as such, nothing is loaded). */
    fun test(source: IptvSource): Boolean {
        val valid = runCatching { source.validated() }.getOrElse { _result.value = null; return false }
        _testing.value = valid.id; _result.value = null
        viewModelScope.launch {
            val outcome = suspendResult { registry.testIptv(valid) }
            _result.value = outcome.fold(
                onSuccess = { (channels, guide) -> IptvTestResult(valid.id, channels > 0, channels, guide) },
                onFailure = { IptvTestResult(valid.id, false, error = it.message) },
            )
            _testing.value = null
        }
        return true
    }

    fun clearResult() { _result.value = null }
}

/** Settings › Systems › IPTV: the playlists, each with its optional guide. */
@Composable
internal fun IptvSettingsSection(viewModel: IptvSettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val sources by viewModel.sources.collectAsState()
    var editing by remember { mutableStateOf<IptvSource?>(null) }
    SettingsGroup(title = stringResource(R.string.iptv_title), subtitle = stringResource(R.string.iptv_settings_hint)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sources.forEach { source ->
                TvActionButton(
                    source.name + if (source.enabled) "" else " · " + stringResource(R.string.iptv_disabled),
                    { viewModel.clearResult(); editing = source }, fillWidth = true,
                )
            }
            if (sources.isEmpty()) Text(stringResource(R.string.iptv_empty), style = MaterialTheme.typography.bodySmall)
            TvActionButton(stringResource(R.string.iptv_add), {
                viewModel.clearResult(); editing = IptvSource(name = "", playlistUrl = "")
            }, fillWidth = true)
            Text(stringResource(R.string.iptv_privacy_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
    editing?.let { source -> IptvSourceDialog(source, existing = sources.any { it.id == source.id }, viewModel, onDismiss = { editing = null }) }
}

@Composable
private fun IptvSourceDialog(source: IptvSource, existing: Boolean, viewModel: IptvSettingsViewModel, onDismiss: () -> Unit) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var playlist by remember(source.id) { mutableStateOf(source.playlistUrl) }
    var epg by remember(source.id) { mutableStateOf(source.epgUrl) }
    var userAgent by remember(source.id) { mutableStateOf(source.userAgent) }
    var enabled by remember(source.id) { mutableStateOf(source.enabled) }
    var invalid by remember(source.id) { mutableStateOf(false) }
    var confirmDelete by remember(source.id) { mutableStateOf(false) }
    val testing by viewModel.testing.collectAsState()
    val result by viewModel.result.collectAsState()
    val form = source.copy(name = name.ifBlank { hostOf(playlist) }, playlistUrl = playlist, epgUrl = epg, userAgent = userAgent, enabled = enabled)
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(if (existing) R.string.iptv_edit else R.string.iptv_add),
        fillHeight = true,
        footer = {
            TvActionButton(stringResource(R.string.files_test), { invalid = !viewModel.test(form) }, loading = testing == source.id, enabled = testing == null)
            TvActionButton(stringResource(R.string.files_save), { if (viewModel.save(form)) onDismiss() else invalid = true })
            if (existing) TvActionButton(stringResource(R.string.iptv_delete), { confirmDelete = true })
            TvActionButton(stringResource(R.string.files_cancel), onDismiss)
        },
    ) {
        TvTextField(name, { name = it.take(80) }, stringResource(R.string.files_source_name), Modifier.fillMaxWidth())
        TvTextField(playlist, { playlist = it.trim() }, stringResource(R.string.iptv_playlist_url), Modifier.fillMaxWidth())
        TvTextField(epg, { epg = it.trim() }, stringResource(R.string.iptv_epg_url), Modifier.fillMaxWidth())
        Text(stringResource(R.string.iptv_epg_hint), style = MaterialTheme.typography.bodySmall)
        TvTextField(userAgent, { userAgent = it.take(256) }, stringResource(R.string.iptv_user_agent), Modifier.fillMaxWidth())
        TvSettingRow(stringResource(R.string.iptv_enabled), null, enabled, { enabled = it })
        if (invalid) Text(stringResource(R.string.iptv_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        result?.takeIf { it.sourceId == source.id }?.let { r ->
            val text = when {
                r.ok -> stringResource(R.string.iptv_test_ok, r.channels) + " · " +
                    stringResource(if (r.guide) R.string.iptv_test_guide else R.string.iptv_test_no_guide)
                r.error == null && r.channels == 0 -> stringResource(R.string.iptv_test_empty)
                else -> stringResource(R.string.iptv_test_failed) + (r.error?.let { " ($it)" } ?: "")
            }
            Text(text, color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmDelete) AppDialog(
        onDismiss = { confirmDelete = false },
        title = stringResource(R.string.iptv_delete_title, source.name),
        footer = {
            TvActionButton(stringResource(R.string.files_cancel), { confirmDelete = false })
            TvActionButton(stringResource(R.string.iptv_delete), { viewModel.remove(source.id); confirmDelete = false; onDismiss() })
        },
    )
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.").orEmpty()

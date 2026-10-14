package com.ambient.tv.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.subtitles.OnlineSubtitle
import com.ambient.tv.data.subtitles.OpenSubtitlesClient
import com.ambient.tv.data.subtitles.OpenSubtitlesException
import com.ambient.tv.data.subtitles.OpenSubtitlesHash
import com.ambient.tv.data.subtitles.SubtitleSearchQuery
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloaded subtitles per playback route, so they stay in the list when the player rebuilds its
 * metadata (next item loaded, renderer switch, reopening the player).
 */
object OnlineSubtitleTracks {
    private val byRoute = java.util.concurrent.ConcurrentHashMap<String, List<MediaTrack>>()
    fun forRoute(route: String): List<MediaTrack> = byRoute[route].orEmpty()
    fun add(route: String, track: MediaTrack) {
        byRoute.compute(route) { _, old -> (old.orEmpty().filterNot { it.externalUrl == track.externalUrl } + track).takeLast(8) }
        while (byRoute.size > 20) byRoute.keys.firstOrNull()?.let(byRoute::remove)
    }
}

data class OnlineSubtitleState(
    val searching: Boolean = false,
    val results: List<OnlineSubtitle>? = null,
    val downloadingId: Long? = null,
    val error: Int? = null,
    val errorDetail: String? = null,
    val loaded: String? = null,
)

data class OpenSubtitlesSettingsState(
    val apiKeySet: Boolean = false,
    val username: String = "",
    val passwordSet: Boolean = false,
    val testing: Boolean = false,
    /** Null: not tested; otherwise the result line. */
    val status: String? = null,
    val ok: Boolean = false,
)

/** Downloads belong to one playback generation, even when the same title is reopened. */
internal suspend fun downloadSubtitleForPlayback(
    owner: BackgroundPlaybackSession.Snapshot,
    current: () -> BackgroundPlaybackSession.Snapshot,
    download: suspend () -> java.io.File,
    apply: (java.io.File) -> Unit,
): Boolean {
    val file = download()
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    val latest = current()
    if (!latest.active || latest.generation != owner.generation || latest.metadata?.route != owner.metadata?.route) return false
    apply(file)
    return true
}

@HiltViewModel
class OnlineSubtitlesViewModel @Inject constructor(
    private val client: OpenSubtitlesClient,
    private val tokens: TokenStore,
    private val preferences: AppPreferences,
    private val fileStore: FileStore,
    private val fileAccess: FileAccess,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    private val _state = MutableStateFlow(OnlineSubtitleState())
    val state = _state.asStateFlow()
    private val _settings = MutableStateFlow(settingsSnapshot())
    val settings = _settings.asStateFlow()
    private var job: Job? = null

    val configured: Boolean get() = client.configured

    /** Preferred subtitle language, the fallback and the UI language, as two-letter codes. */
    fun defaultLanguages(): List<String> = listOf(
        LanguagePreferences.resolve(preferences.subtitleLanguageKey),
        preferences.subtitleFallbackLanguageKey.takeIf { it != "none" },
        LanguagePreferences.systemLanguageTag(),
    ).mapNotNull { LanguagePreferences.baseLanguage(it) }.filter { it.length == 2 }.distinct()

    fun search(info: SubtitleSearchInfo, languages: List<String>) {
        job?.cancel()
        _state.value = OnlineSubtitleState(searching = true)
        job = viewModelScope.launch {
            val hash = info.fileMediaId?.let { key -> withContext(Dispatchers.IO) { runCatching { fileHash(key) }.getOrNull() } }
            val result = suspendResult {
                val query = SubtitleSearchQuery(info.title, info.year, info.season, info.episode, info.imdbId, hash, languages)
                // An IMDb id or hash the service does not know returns nothing: then by title (and season/episode).
                client.search(query).ifEmpty {
                    if (query.imdbId != null || query.movieHash != null) client.search(query.copy(imdbId = null, movieHash = null)) else emptyList()
                }
            }
            _state.value = result.fold(
                onSuccess = { OnlineSubtitleState(results = it) },
                onFailure = { OnlineSubtitleState(error = errorText(it), errorDetail = it.message) },
            )
        }
    }

    fun download(subtitle: OnlineSubtitle, onLoaded: () -> Unit) {
        if (_state.value.downloadingId != null) return
        val playback = BackgroundPlaybackSession.snapshot.value
        _state.update { it.copy(downloadingId = subtitle.fileId, error = null) }
        job = viewModelScope.launch {
            var attachmentFailed = false
            val result = suspendResult {
                downloadSubtitleForPlayback(playback, { BackgroundPlaybackSession.snapshot.value },
                    download = { client.download(subtitle) }, apply = { file ->
                        attachmentFailed = runCatching { loadIntoPlayer(file, subtitle) }.isFailure
                    })
            }
            result.onSuccess { stillCurrent ->
                val loaded = stillCurrent && !attachmentFailed
                _state.update { it.copy(downloadingId = null, loaded = subtitle.release.takeIf { loaded },
                    error = if (attachmentFailed) R.string.online_subtitles_load_failed else null) }
                if (loaded) onLoaded()
            }.onFailure { error ->
                _state.update { it.copy(downloadingId = null, error = errorText(error), errorDetail = error.message) }
            }
        }
    }

    fun reset() { job?.cancel(); _state.value = OnlineSubtitleState() }

    // ---- Settings ----

    fun saveSettings(apiKey: String?, username: String, password: String?) {
        apiKey?.let { tokens.openSubtitlesApiKey = it }
        tokens.openSubtitlesUsername = username
        password?.let { tokens.openSubtitlesPassword = it }
        if (username.isBlank()) tokens.openSubtitlesPassword = null
        _settings.value = settingsSnapshot()
    }

    fun testSettings() {
        _settings.update { it.copy(testing = true, status = null) }
        viewModelScope.launch {
            val result = suspendResult { client.test() }
            _settings.value = settingsSnapshot().copy(
                ok = result.isSuccess,
                status = result.fold(
                    onSuccess = { remaining -> if (remaining != null) context.getString(R.string.opensubtitles_ok_remaining, remaining) else context.getString(R.string.opensubtitles_ok) },
                    onFailure = { context.getString(errorText(it)) + (it.message?.let { message -> " ($message)" } ?: "") },
                ),
            )
        }
    }

    private fun settingsSnapshot() = OpenSubtitlesSettingsState(
        apiKeySet = !tokens.openSubtitlesApiKey.isNullOrBlank(),
        username = tokens.openSubtitlesUsername.orEmpty(),
        passwordSet = !tokens.openSubtitlesPassword.isNullOrEmpty(),
    )

    private fun fileHash(key: String): String? {
        val (sourceId, path) = FileKeys.parse(key)
        val source = fileStore.sources().firstOrNull { it.id == sourceId } ?: return null
        return fileAccess.open(source, path).use { OpenSubtitlesHash.of(it) }
    }

    private fun errorText(error: Throwable): Int = when ((error as? OpenSubtitlesException)?.kind) {
        OpenSubtitlesException.Kind.NOT_CONFIGURED -> R.string.opensubtitles_error_key
        OpenSubtitlesException.Kind.LOGIN -> R.string.opensubtitles_error_login
        OpenSubtitlesException.Kind.QUOTA -> R.string.opensubtitles_error_quota
        OpenSubtitlesException.Kind.RATE_LIMIT -> R.string.opensubtitles_error_rate
        else -> R.string.opensubtitles_error_network
    }

    /** Adds the file as an external track of the running playback and selects it. Main thread. */
    private fun loadIntoPlayer(file: java.io.File, subtitle: OnlineSubtitle) {
        val metadata = requireNotNull(BackgroundPlaybackSession.snapshot.value.metadata) { "No playback" }
        val language = subtitle.languageCode.takeIf(String::isNotBlank)
        val track = MediaTrack(
            id = null, type = MediaTrackType.SUBTITLE, codec = subtitle.format,
            language = language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { null } },
            languageCode = language, title = "OpenSubtitles · " + subtitle.release.take(60), channels = null,
            selected = true, forced = subtitle.forced, externalUrl = android.net.Uri.fromFile(file).toString(),
        )
        OnlineSubtitleTracks.add(metadata.route, track.copy(selected = false))
        BackgroundPlaybackSession.updateMetadata(metadata.copy(subtitleTracks = metadata.subtitleTracks.filterNot { it.externalUrl == track.externalUrl } + track))
        BackgroundPlaybackSession.manualSubtitleSelection()
        when (val handle = BackgroundPlaybackSession.activeHandle()) {
            is MpvPlaybackController -> handle.selectExternalSubtitle(track)
            is VlcPlaybackController -> handle.selectExternalSubtitle(track)
            is Media3PlaybackHandle -> handle.addExternalSubtitle(track)
            else -> error("No player")
        }
    }
}

/** "Search online" in the subtitle panel; shown when a key is set and the title can be searched. */
@Composable
fun OnlineSubtitleSearchButton(info: SubtitleSearchInfo?, onLoaded: () -> Unit) {
    if (info == null) return
    val viewModel: OnlineSubtitlesViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    if (!viewModel.configured) return
    val playback by BackgroundPlaybackSession.snapshot.collectAsState()
    var open by remember(info, playback.generation) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(info, playback.generation) { onDispose { viewModel.reset() } }
    TvActionButton(stringResource(R.string.online_subtitles_search), { viewModel.reset(); open = true }, fillWidth = true)
    if (open) OnlineSubtitleDialog(viewModel, info, onDismiss = { viewModel.reset(); open = false }, onLoaded = { open = false; onLoaded() })
}

@Composable
private fun OnlineSubtitleDialog(viewModel: OnlineSubtitlesViewModel, info: SubtitleSearchInfo, onDismiss: () -> Unit, onLoaded: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val defaults = remember { viewModel.defaultLanguages() }
    var languages by remember { mutableStateOf(defaults) }
    androidx.compose.runtime.DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    LaunchedEffect(info, languages) { viewModel.search(info, languages) }
    val heading = listOfNotNull(info.title, info.season?.let { s -> info.episode?.let { e -> "S%02dE%02d".format(s, e) } }, info.year?.takeIf { info.season == null }?.toString())
        .joinToString(" · ")
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.online_subtitles_title),
        hint = heading,
        fillHeight = true,
        footer = { TvActionButton(stringResource(R.string.files_cancel), onDismiss) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            defaults.forEach { code ->
                TvActionButton(java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code }, { languages = listOf(code) },
                    selected = languages == listOf(code))
            }
            if (defaults.size > 1) TvActionButton(stringResource(R.string.online_subtitles_all_defaults), { languages = defaults }, selected = languages == defaults)
            TvActionButton(stringResource(R.string.online_subtitles_any_language), { languages = emptyList() }, selected = languages.isEmpty())
        }
        when {
            state.searching -> Text(stringResource(R.string.online_subtitles_searching), style = MaterialTheme.typography.bodySmall)
            state.error != null -> Text(stringResource(state.error!!) + (state.errorDetail?.let { " ($it)" } ?: ""),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            state.results?.isEmpty() == true -> Text(stringResource(R.string.online_subtitles_none), style = MaterialTheme.typography.bodySmall)
        }
        state.results.orEmpty().take(60).forEach { subtitle ->
            val badges = listOfNotNull(
                subtitle.languageCode.uppercase(),
                stringResource(R.string.online_subtitles_exact).takeIf { subtitle.hashMatch },
                stringResource(R.string.online_subtitles_forced).takeIf { subtitle.forced },
                "SDH".takeIf { subtitle.hearingImpaired },
                stringResource(R.string.online_subtitles_downloads, subtitle.downloads),
            ).joinToString(" · ")
            TvActionButton(subtitle.release + "\n" + badges, { viewModel.download(subtitle, onLoaded) },
                enabled = state.downloadingId == null, loading = state.downloadingId == subtitle.fileId, fillWidth = true)
        }
    }
}

/** Settings › Playback › Subtitles: the user's own OpenSubtitles API key and account. */
@Composable
fun OpenSubtitlesSettingsGroup(content: @Composable (title: String, subtitle: String, body: @Composable () -> Unit) -> Unit) {
    val viewModel: OnlineSubtitlesViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val settings by viewModel.settings.collectAsState()
    // Secrets are never shown again; empty means "keep the stored value".
    var apiKey by remember { mutableStateOf("") }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf("") }
    content(stringResource(R.string.opensubtitles_title), stringResource(R.string.opensubtitles_hint)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(apiKey, { apiKey = it.trim() }, stringResource(if (settings.apiKeySet) R.string.opensubtitles_api_key_set else R.string.opensubtitles_api_key),
                Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            TvTextField(username, { username = it }, stringResource(R.string.opensubtitles_username), Modifier.fillMaxWidth())
            TvTextField(password, { password = it }, stringResource(if (settings.passwordSet) R.string.opensubtitles_password_set else R.string.opensubtitles_password),
                Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(stringResource(R.string.files_save), {
                    viewModel.saveSettings(apiKey.takeIf(String::isNotBlank), username, password.takeIf(String::isNotEmpty))
                    apiKey = ""; password = ""
                })
                TvActionButton(stringResource(R.string.opensubtitles_test), viewModel::testSettings,
                    enabled = settings.apiKeySet && !settings.testing, loading = settings.testing)
                if (settings.apiKeySet) TvActionButton(stringResource(R.string.opensubtitles_remove), {
                    viewModel.saveSettings("", "", null); apiKey = ""; username = ""; password = ""
                })
            }
            settings.status?.let {
                Text(it, color = if (settings.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

package com.ambient.tv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import com.ambient.tv.R
import com.ambient.tv.branding.BrandMark
import com.ambient.tv.branding.BrandWordmark
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.common.wrapVerticalScrollFocus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InitialSetupScreen(
    onUseFiles: () -> Unit,
    onSetupPlex: () -> Unit,
    onSetupJellyfin: () -> Unit,
    onSetupEmby: () -> Unit,
    onSetupDlna: () -> Unit,
    onSetupTvheadend: () -> Unit,
    onSetupIptv: () -> Unit,
    onUseYouTubeWithoutLogin: () -> Unit,
    onYouTubeSignedIn: () -> Unit,
    onSetupSpotify: () -> Unit,
    onSetupTrailers: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: (() -> Unit)?,
    onRestore: () -> Unit,
    viewModel: InitialSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var youtubeStep by rememberSaveable { mutableStateOf(false) }
    var lastSystem by rememberSaveable { mutableStateOf(MediaSystem.PLEX.key) }
    val requesters = remember { MediaSystem.entries.associateWith { FocusRequester() } }
    val loginFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val codeVisibility = remember { BringIntoViewRequester() }
    LaunchedEffect(state.youtubeSignInCode) {
        if (state.youtubeSignInCode != null) { withFrameNanos { }; codeVisibility.bringIntoView() }
    }
    val tokens = LocalUiTokens.current
    fun cancelLogin() { viewModel.cancelYouTubeSignIn(); runCatching { loginFocus.requestFocus() } }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelYouTubeSignIn() } }
    BackHandler(youtubeStep) {
        if (state.youtubeLoading || state.youtubeSignInCode != null) cancelLogin() else youtubeStep = false
    }
    LaunchedEffect(youtubeStep) {
        repeat(3) {
            withFrameNanos { }
            val target = if (youtubeStep) { if (state.youtubeAvailable) loginFocus else backFocus } else requesters.getValue(MediaSystem.fromKey(lastSystem) ?: MediaSystem.PLEX)
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(state.youtubeSignedIn) { if (state.youtubeSignedIn) { youtubeStep = false; viewModel.cancelYouTubeSignIn(); onYouTubeSignedIn() } }
    val setupScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().wrapVerticalScrollFocus(setupScroll).verticalScroll(setupScroll)
        .padding(tokens.screenPadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark(size = 40.dp, contentDescription = null)
            BrandWordmark(height = 20.dp)
        }
        Text(stringResource(if (youtubeStep) R.string.setup_youtube_title else R.string.setup_welcome), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(if (youtubeStep) R.string.setup_youtube_intro else R.string.setup_intro),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!youtubeStep) {
            val companion: com.ambient.tv.companion.CompanionViewModel = hiltViewModel()
            val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
            TvActionButton(stringResource(if (handheld) R.string.companion_setup_phone else R.string.companion_setup_tv),
                onClick = { if (handheld) companion.manager.showRemoteOrDevices() else companion.manager.showPairing() },
                selected = true, fillWidth = true)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = when { maxWidth >= 800.dp -> 3; maxWidth >= 500.dp -> 2; else -> 1 }
                val systems = MediaSystem.addOrder
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    systems.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { system -> key(system.key) {
                                SetupSystemCard(system, Modifier.weight(1f).focusRequester(requesters.getValue(system)),
                                    onFocused = { lastSystem = system.key }) {
                                    when (system) {
                                        MediaSystem.PLEX -> onSetupPlex()
                                        MediaSystem.JELLYFIN -> onSetupJellyfin()
                                        MediaSystem.EMBY -> onSetupEmby()
                                        MediaSystem.DLNA -> onSetupDlna()
                                        MediaSystem.TVHEADEND -> onSetupTvheadend()
                                        MediaSystem.IPTV -> onSetupIptv()
                                        MediaSystem.YOUTUBE -> youtubeStep = true
                                        MediaSystem.SPOTIFY -> onSetupSpotify()
                                        MediaSystem.TRAILERS -> onSetupTrailers()
                                        MediaSystem.FILES -> onUseFiles()
                                    }
                                }
                            } }
                        }
                    }
                }
            }
            // Utility actions are separated from the provider grid and remain reachable at large scales.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(stringResource(R.string.setup_restore), onRestore)
                onSkip?.let { TvActionButton(stringResource(R.string.setup_skip), it) }
                TvActionButton(stringResource(R.string.setup_more_settings), onOpenSettings)
            }
        } else {
            SystemBadge(MediaSystem.YOUTUBE)
            TvActionButton(stringResource(if (state.youtubeLoading) R.string.action_signing_in else R.string.action_sign_in),
                viewModel::requestYouTubeSignIn, enabled = state.youtubeAvailable, loading = state.youtubeLoading,
                modifier = Modifier.focusRequester(loginFocus), fillWidth = true)
            TvActionButton(stringResource(R.string.action_without_sign_in), onUseYouTubeWithoutLogin,
                enabled = state.youtubeAvailable, fillWidth = true)
            state.youtubeSignInCode?.let { code ->
                Column(Modifier.fillMaxWidth().bringIntoViewRequester(codeVisibility).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(tokens.cardRadius)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Phones open the activation page in a browser and can paste the copied code.
                    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
                        Text(stringResource(R.string.youtube_activate_handheld), style = MaterialTheme.typography.bodyLarge)
                        com.ambient.tv.ui.youtube.YouTubeSignInPanel(
                            code = code,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.setup_youtube_code_instructions), style = MaterialTheme.typography.bodyLarge)
                        Text(code, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (state.youtubeLoading || state.youtubeSignInCode != null) TvActionButton(stringResource(R.string.settings_link_cancel), ::cancelLogin)
            state.youtubeErrorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            state.youtubeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.youtubeAvailable) Text(stringResource(R.string.setup_youtube_unavailable), color = MaterialTheme.colorScheme.error)
            TvActionButton(stringResource(R.string.back), { viewModel.cancelYouTubeSignIn(); youtubeStep = false }, modifier = Modifier.focusRequester(backFocus))
        }
    }
}

@Composable
internal fun SetupSystemCard(system: MediaSystem, modifier: Modifier, onFocused: () -> Unit, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.cardRadius)
    Surface(onClick = onClick, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape),
        modifier = modifier.mediaFocusMotion(focused).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }.activateOnTvKey(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().appControlSurface(focused, selected, shape = shape).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The wordmark always names the provider (a bare symbol like Emby's or Spotify's does not).
            // TVHeadend keeps tvh_title ("Live TV") for the Live TV screen; only this add-system row says TVHeadend.
            val markName = when (system) {
                MediaSystem.TVHEADEND -> systemName(system, stringResource(R.string.add_system_label_tvheadend))
                // files_title stays "Files" / "Dateien" for the Files screen; only this card says SMB and NFS.
                MediaSystem.FILES -> systemName(system, stringResource(R.string.add_system_label_files))
                else -> systemName(system)
            }
            // A fixed header height keeps every card equally tall, whatever the wordmark's own height.
            Box(Modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
            if (system == MediaSystem.FILES || system == MediaSystem.IPTV) {
                Row( verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painterResource(if (system == MediaSystem.IPTV) R.drawable.system_iptv else R.drawable.system_files),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
                    )
                    Text(markName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                SystemWordmark(system, markName, height = 24.dp)
            }
            }
            Text(stringResource(when (system) {
                MediaSystem.PLEX -> R.string.setup_desc_plex
                MediaSystem.TRAILERS -> R.string.trailers_setup_hint
                MediaSystem.JELLYFIN -> R.string.setup_desc_jellyfin
                MediaSystem.EMBY -> R.string.setup_desc_emby
                MediaSystem.TVHEADEND -> R.string.setup_desc_tvheadend
                MediaSystem.IPTV -> R.string.iptv_settings_hint
                MediaSystem.YOUTUBE -> R.string.setup_desc_youtube
                MediaSystem.SPOTIFY -> R.string.setup_desc_spotify
                MediaSystem.DLNA -> R.string.dlna_explanation
                MediaSystem.FILES -> R.string.setup_desc_files
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SetupCompleteScreen(onStart: () -> Unit, onAddSource: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    val setupScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(LocalUiTokens.current.screenPadding).wrapVerticalScrollFocus(setupScroll).verticalScroll(setupScroll),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.setup_ready_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.setup_ready_body), style = MaterialTheme.typography.bodyLarge)
        TvActionButton(stringResource(R.string.setup_start), onStart, selected = true, modifier = Modifier.focusRequester(focus))
        TvActionButton(stringResource(R.string.setup_add_source), onAddSource)
    }
}

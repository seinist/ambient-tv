package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi

import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.handheldDetailStacks

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.player.LocalVisibleBackgroundVideo

@Composable
fun YouTubeDetailScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (String) -> Unit,
    viewModel: YouTubeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    val visibleBackgroundVideo = LocalVisibleBackgroundVideo.current || com.ambient.tv.ui.common.LocalHomeBackgroundVisible.current
    val primaryFocusRequester = remember { FocusRequester() }
    // A skeleton is not an action. Focus Play only once content can actually be started.
    LaunchedEffect(state.loading, state.metadata?.videoId, state.offline) {
        if (state.loading || state.metadata == null || state.offline) return@LaunchedEffect
        kotlinx.coroutines.delay(60)
        runCatching { primaryFocusRequester.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> DeviceActionRow(stack = handheldDetailStacks(LocalConfiguration.current.screenWidthDp),
                modifier = Modifier.fillMaxSize().padding(deviceDp(46.dp, 16.dp)),
                horizontalArrangement=Arrangement.spacedBy(36.dp),
                verticalAlignment=Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(deviceDp(420.dp, 320.dp))
                        .aspectRatio(16f/9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.62f)),
                )
                Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.fillMaxWidth(.78f).height(34.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.72f), RoundedCornerShape(999.dp)))
                    Box(Modifier.fillMaxWidth(.36f).height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.56f), RoundedCornerShape(999.dp)))
                    repeat(4) { Box(Modifier.fillMaxWidth(if (it == 3) .52f else .92f).height(13.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.42f), RoundedCornerShape(999.dp))) }
                    DeviceActionRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Box(Modifier.width(150.dp).height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.62f), RoundedCornerShape(10.dp))); Box(Modifier.width(120.dp).height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.52f), RoundedCornerShape(10.dp))) }
                }
            }
            state.metadata == null -> StatePanel(
                title = stringResource(R.string.youtube_video_unavailable),
                body = state.error,
                actionLabel = stringResource(R.string.home_retry),
                onAction = viewModel::load,
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val m=state.metadata!!
                val current = com.ambient.tv.companion.playbackIndicator(com.ambient.tv.companion.CompanionMedia("youtube", m.videoId))
                val openCurrent = com.ambient.tv.companion.LocalOpenActivePlayback.current
                Box(Modifier.fillMaxSize()) {
                    if (!visibleBackgroundVideo) ArtworkImage(model=m.backgroundUrl ?: m.imageUrl, title=m.title, modifier=Modifier.fillMaxSize(), contentScale=ContentScale.Crop, focusCrop=true)
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha=.66f)))
                    DeviceActionRow(Modifier.fillMaxSize().then(if (LocalHandheldUi.current) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(start = deviceDp(46.dp, 16.dp), end = deviceDp(46.dp, 16.dp), top = deviceDp(82.dp, 72.dp), bottom = deviceDp(46.dp, 16.dp)), stack = handheldDetailStacks(LocalConfiguration.current.screenWidthDp), horizontalArrangement=Arrangement.spacedBy(36.dp), verticalAlignment=Alignment.CenterVertically) {
                        ArtworkImage(model=m.imageUrl, title=m.title, modifier=Modifier.width(deviceDp(420.dp, 320.dp)).aspectRatio(16f/9f).clip(RoundedCornerShape(12.dp)), contentScale=ContentScale.Crop)
                        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                            com.ambient.tv.ui.common.SystemBadge(com.ambient.tv.ui.common.MediaSystem.YOUTUBE)
                            com.ambient.tv.companion.PlaybackStatusSection(current)
                            if (current != null && !state.offline) com.ambient.tv.companion.CompanionChooseDeviceAction { onPlay(m.videoId) }
                            Text(m.title, style=MaterialTheme.typography.headlineLarge, maxLines=3, overflow=TextOverflow.Ellipsis)
                            m.author?.let { Text(it, style=MaterialTheme.typography.titleMedium, color=MaterialTheme.colorScheme.primary) }
                            m.description?.let { Text(it, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant, maxLines=8, overflow=TextOverflow.Ellipsis) }
                            if (state.offline) Text(stringResource(R.string.browse_offline))
                            DeviceActionRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){ if (!state.offline) TvActionButton(if (current != null) stringResource(R.string.playback_open) else if(m.percentWatched>0) stringResource(R.string.common_resume) else stringResource(R.string.common_play), onClick={ if (current != null) openCurrent(current) else onPlay(m.videoId) }, modifier=Modifier.focusRequester(primaryFocusRequester)) }
                        }
                    }
                }
            }
        }
        PageUtilityBar(
            onBack = onBack,
            onSettings = onOpenSettings,
            onDown = if (!state.loading && state.metadata != null && !state.offline) ({ runCatching { primaryFocusRequester.requestFocus() } }) else null,
            requestInitialFocus = state.loading || (state.metadata != null && state.offline),
            leftNavigatesBack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

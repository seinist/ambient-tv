package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi

import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.handheldDetailStacks

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.focus.onFocusChanged
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.youtube.YouTubeLikeStatus
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Subscriptions
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

/** Titled horizontal strip below the detail hero. */
@Composable
private fun YouTubeDetailRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = deviceDp(46.dp, 16.dp)))
        LazyRow(
            contentPadding = PaddingValues(horizontal = deviceDp(46.dp, 16.dp)),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun YouTubeDetailChapters(chapters: List<com.ambient.tv.domain.model.VideoChapter>, onChapter: (com.ambient.tv.domain.model.VideoChapter) -> Unit) =
    YouTubeDetailRow(stringResource(R.string.youtube_chapters)) {
        items(chapters, key = { it.startMs }) { chapter ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(10.dp)
            androidx.tv.material3.Surface(
                onClick = { onChapter(chapter) },
                colors = com.ambient.tv.ui.common.transparentSurfaceColors(),
                scale = com.ambient.tv.ui.common.noSurfaceScale(),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
                modifier = Modifier
                    .width(deviceDp(220.dp, 180.dp))
                    .mediaFocusMotion(focused)
                    .activateOnTvKey { onChapter(chapter) }
                    .onFocusChanged { focused = it.isFocused },
            ) {
                Column(
                    Modifier
                        .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surface.copy(alpha = .58f), shape)
                        .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White.copy(alpha = .06f), shape)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chapter.imageUrl?.let { ArtworkImage(model = it, title = chapter.title.orEmpty(), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop) }
                    Text(com.ambient.tv.ui.common.formatPlaybackClock(chapter.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    chapter.title?.let { MarqueeTitle(it, focused = focused, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

@Composable
fun YouTubeDetailScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenChannel: (com.ambient.tv.youtube.YouTubeChannelInfo) -> Unit = {},
    /** Related video or channel tile; the caller applies the selection behavior. */
    onOpenItem: (String) -> Unit = {},
    viewModel: YouTubeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    com.ambient.tv.ui.common.RefreshOnReconnect(setOf("youtube"), viewModel::refresh)
    val visibleBackgroundVideo = LocalVisibleBackgroundVideo.current || com.ambient.tv.ui.common.LocalHomeBackgroundVisible.current
    val primaryFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember(state.metadata?.videoId) { mutableStateOf(false) }
    // A skeleton is not an action. Focus Play only once content can actually be started.
    LaunchedEffect(state.loading, state.metadata?.videoId, state.offline) {
        if (initialFocusRequested || state.loading || state.metadata == null || state.offline) return@LaunchedEffect
        kotlinx.coroutines.delay(60)
        initialFocusRequested = runCatching { primaryFocusRequester.requestFocus() }.getOrDefault(false)
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
                    if (!visibleBackgroundVideo) ArtworkImage(model=m.backgroundUrl ?: m.imageUrl, title=m.title, modifier=Modifier.fillMaxSize(), contentScale=ContentScale.Crop, focusCrop=true, placeholder=false)
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha=.66f)))
                    // Chapters and related videos follow below the fold, as in SmartTube's details.
                    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = deviceDp(46.dp, 16.dp))) {
                    DeviceActionRow(Modifier.fillMaxWidth().heightIn(min = if (LocalHandheldUi.current) 0.dp else screenHeight * .86f).padding(start = deviceDp(46.dp, 16.dp), end = deviceDp(46.dp, 16.dp), top = deviceDp(82.dp, 72.dp), bottom = deviceDp(24.dp, 16.dp)), stack = handheldDetailStacks(LocalConfiguration.current.screenWidthDp), horizontalArrangement=Arrangement.spacedBy(36.dp), verticalAlignment=Alignment.CenterVertically) {
                        ArtworkImage(model=m.imageUrl, title=m.title, modifier=Modifier.width(deviceDp(420.dp, 320.dp)).aspectRatio(16f/9f).clip(RoundedCornerShape(12.dp)), contentScale=ContentScale.Crop)
                        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                            com.ambient.tv.ui.common.SystemBadge(com.ambient.tv.ui.common.MediaSystem.YOUTUBE)
                            com.ambient.tv.companion.PlaybackStatusSection(current)
                            if (current != null && !state.offline) com.ambient.tv.companion.CompanionChooseDeviceAction { onPlay(m.videoId) }
                            Text(m.title, style=MaterialTheme.typography.headlineLarge, maxLines=3, overflow=TextOverflow.Ellipsis)
                            m.author?.let {
                                Row(horizontalArrangement=Arrangement.spacedBy(10.dp), verticalAlignment=Alignment.CenterVertically) {
                                    m.authorImageUrl?.let { url -> ArtworkImage(model=url, title=it, modifier=Modifier.size(deviceDp(32.dp, 28.dp)).clip(CircleShape), contentScale=ContentScale.Crop) }
                                    Text(it, style=MaterialTheme.typography.titleMedium, color=MaterialTheme.colorScheme.primary)
                                }
                            }
                            listOfNotNull(m.viewCount, m.publishedDate).takeIf { it.isNotEmpty() }?.let {
                                Text(it.joinToString("  ·  "), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            m.description?.let { Text(it, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant, maxLines=8, overflow=TextOverflow.Ellipsis) }
                            if (state.offline) Text(stringResource(R.string.browse_offline))
                            DeviceActionRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                if (!state.offline) TvActionButton(if (current != null) stringResource(R.string.playback_open) else if(m.percentWatched>0) stringResource(R.string.common_resume) else stringResource(R.string.common_play), onClick={ if (current != null) openCurrent(current) else onPlay(m.videoId) }, modifier=Modifier.focusRequester(primaryFocusRequester))
                                state.channel?.takeIf { !state.offline }?.let { channel ->
                                    TvActionButton(channel.title ?: stringResource(R.string.youtube_channel_open), onClick={ onOpenChannel(channel) }, icon=androidx.compose.material.icons.Icons.Filled.AccountCircle)
                                    channel.subscribed?.let { subscribed ->
                                        TvActionButton(stringResource(if (subscribed) R.string.youtube_subscribed else R.string.youtube_subscribe), onClick=viewModel::toggleSubscription, selected=subscribed, icon=androidx.compose.material.icons.Icons.Filled.Subscriptions)
                                    }
                                }
                                if (state.signedIn && !state.offline) {
                                    val liked = state.likeStatus == YouTubeLikeStatus.LIKE
                                    TvActionButton(m.likeCount ?: stringResource(R.string.youtube_like), onClick={ viewModel.rate(YouTubeLikeStatus.LIKE) }, selected=liked, icon=androidx.compose.material.icons.Icons.Filled.ThumbUp)
                                    TvActionButton(stringResource(R.string.youtube_dislike), onClick={ viewModel.rate(YouTubeLikeStatus.DISLIKE) }, selected=state.likeStatus == YouTubeLikeStatus.DISLIKE, icon=androidx.compose.material.icons.Icons.Filled.ThumbDown)
                                }
                            }
                        }
                    }
                    if (m.chapters.isNotEmpty() && !state.offline) {
                        YouTubeDetailChapters(m.chapters) { chapter ->
                            // A chapter of the running video seeks it; otherwise the player starts there.
                            if (current != null) {
                                openCurrent(current)
                                com.ambient.tv.ui.player.BackgroundPlaybackSession.seekTo(chapter.startMs)
                            } else {
                                YouTubeStartRequest.request(m.videoId, chapter.startMs)
                                onPlay(m.videoId)
                            }
                        }
                    }
                    if (!state.offline) m.suggestions.filter { it.items.isNotEmpty() }.forEach { row ->
                        YouTubeDetailRow(row.title.takeUnless { it == "SmartTube" } ?: stringResource(R.string.youtube_suggestions)) {
                            items(row.items, key = { it.videoId }) { item ->
                                YouTubeItemCard(item, onOpenItem, Modifier.width(deviceDp(300.dp, 240.dp)))
                            }
                        }
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

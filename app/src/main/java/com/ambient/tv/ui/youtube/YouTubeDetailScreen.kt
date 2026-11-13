package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.theme.animatedFocusSurface
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi

import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.handheldDetailStacks

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.ambient.tv.ui.common.DetailSkeletonBlock
import com.ambient.tv.ui.common.DetailSkeletonParagraph
import com.ambient.tv.ui.common.DetailSkeletonActions
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.RowColumnFocus
import com.ambient.tv.ui.common.rememberRowColumnFocus
import com.ambient.tv.ui.common.RegisterRowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusTarget
import com.ambient.tv.ui.common.rowColumnFocusWrapRight
import com.ambient.tv.ui.common.containRowFocusExit
import com.ambient.tv.ui.player.LocalVisibleBackgroundVideo

/** Titled horizontal strip below the detail hero. */
@Composable
private fun YouTubeDetailRow(
    title: String,
    itemKeys: List<Any>,
    content: androidx.compose.foundation.lazy.LazyListScope.(RowColumnFocus, String) -> Unit,
) {
    val rowFocus = rememberRowColumnFocus()
    val rowKey = "youtube-detail:$title"
    val rowState = rememberLazyListState()
    KeepYouTubeLeadingInsertVisible(rowState, itemKeys)
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, itemKeys)
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).rowColumnFocusWrapRight(rowFocus),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = deviceDp(46.dp, 16.dp)))
        LazyRow(
            state = rowState,
            modifier = Modifier.containRowFocusExit(),
            contentPadding = PaddingValues(horizontal = deviceDp(46.dp, 16.dp)),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content(rowFocus, rowKey)
        }
    }
}

@Composable
private fun YouTubeDetailChapters(chapters: List<com.ambient.tv.domain.model.VideoChapter>, onChapter: (com.ambient.tv.domain.model.VideoChapter) -> Unit) =
    YouTubeDetailRow(stringResource(R.string.youtube_chapters), chapters.map { it.startMs }) { rowFocus, rowKey ->
        items(chapters, key = { it.startMs }) { chapter ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(10.dp)
            androidx.tv.material3.Surface(
                onClick = { onChapter(chapter) },
                colors = com.ambient.tv.ui.common.transparentSurfaceColors(),
                scale = com.ambient.tv.ui.common.noSurfaceScale(),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
                modifier = youtubeItemMotionModifier()
                    .rowColumnFocusTarget(rowFocus, rowKey, chapter.startMs)
                    .width(deviceDp(220.dp, 180.dp))
                    .mediaFocusMotion(focused)
                    .activateOnTvKey { onChapter(chapter) }
                    .onFocusChanged { focused = it.isFocused },
            ) {
                Column(
                    Modifier
                        .animatedFocusSurface(
                focused = focused, shape = shape,
                focusedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                unfocusedBackground = MaterialTheme.colorScheme.surface.copy(alpha = .58f),
                focusedBorder = MaterialTheme.colorScheme.primary,
                unfocusedBorder = androidx.compose.ui.graphics.Color.White.copy(alpha = .06f),
                borderWidth = 2.dp,
            )
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

/**
 * Uses the same centered hero width, insets and action order as YouTubeDetailScreen after
 * metadata arrives. Below-the-fold cards can be laid out without forcing the page to jump.
 */
@Composable
private fun YouTubeDetailLoadingSkeleton() {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = deviceDp(46.dp, 16.dp)),
    ) {
        DeviceActionRow(
            Modifier.fillMaxWidth()
                .heightIn(min = if (LocalHandheldUi.current) 0.dp else screenHeight * .86f)
                .padding(
                    start = deviceDp(46.dp, 16.dp), end = deviceDp(46.dp, 16.dp),
                    top = deviceDp(82.dp, 72.dp), bottom = deviceDp(24.dp, 16.dp),
                ),
            stack = handheldDetailStacks(LocalConfiguration.current.screenWidthDp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailSkeletonBlock(
                Modifier.width(deviceDp(420.dp, 320.dp)).aspectRatio(16f / 9f),
                radius = 12.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailSkeletonBlock(Modifier.width(110.dp).height(25.dp), radius = 6.dp)
                DetailSkeletonBlock(Modifier.fillMaxWidth(0.88f).height(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailSkeletonBlock(Modifier.size(deviceDp(32.dp, 28.dp)), radius = 16.dp)
                    DetailSkeletonBlock(Modifier.width(190.dp).height(21.dp), radius = 5.dp)
                }
                DetailSkeletonBlock(Modifier.fillMaxWidth(0.52f).height(15.dp), radius = 5.dp)
                DetailSkeletonParagraph(lines = 4)
                DetailSkeletonActions()
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailSkeletonBlock(
                Modifier.padding(horizontal = deviceDp(46.dp, 16.dp)).width(160.dp).height(22.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = deviceDp(46.dp, 16.dp)),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = false,
            ) {
                items(4) {
                    Column(Modifier.width(deviceDp(300.dp, 240.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailSkeletonBlock(Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 10.dp)
                        DetailSkeletonBlock(Modifier.fillMaxWidth(0.75f).height(13.dp), radius = 5.dp)
                    }
                }
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
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(setOf("youtube"), viewModel::refresh)
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
            state.loading -> YouTubeDetailLoadingSkeleton()
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
                                    val channelId = m.channelId?.takeIf(String::isNotBlank)
                                    // The channel name opens its channel page.
                                    if (channelId != null) TvActionButton(it, onClick = {
                                        com.ambient.tv.launcher.LauncherIntents.post(
                                            com.ambient.tv.ui.navigation.Destinations.youtubeChannel(channelId, it, m.authorImageUrl))
                                    })
                                    else Text(it, style=MaterialTheme.typography.titleMedium, color=MaterialTheme.colorScheme.primary)
                                }
                            }
                            listOfNotNull(m.viewCount, m.publishedDate).takeIf { it.isNotEmpty() }?.let {
                                Text(it.joinToString("  ·  "), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            m.description?.let { Text(it, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant, maxLines=8, overflow=TextOverflow.Ellipsis) }
                            // Videos, channels and Telegram posts linked in the description, openable in the app.
                            val descriptionLinks = remember(m.videoId, m.description) {
                                com.ambient.tv.ui.player.playerDetailYouTubeLinks(m.description).filterNot { link ->
                                    link.kind == com.ambient.tv.ui.player.PlayerDetailYouTubeLinkKind.VIDEO && link.value == m.videoId ||
                                        link.kind == com.ambient.tv.ui.player.PlayerDetailYouTubeLinkKind.CHANNEL_ID && link.value == m.channelId
                                }.take(6)
                            }
                            val telegramLinks = remember(m.videoId, m.description) { com.ambient.tv.ui.player.playerDetailTelegramLinks(m.description) }
                            com.ambient.tv.ui.player.DescriptionLinkButtons(descriptionLinks, telegramLinks, lockHorizontal = false)
                            if (state.offline) Text(stringResource(R.string.browse_offline))
                            DeviceActionRow(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                if (!state.offline) TvActionButton(if (current != null) stringResource(R.string.playback_open) else if(!m.isLive && (com.ambient.tv.youtube.YouTubeLocalProgress.resumePositionMs(m.videoId, m.durationMs, m.percentWatched) ?: 0L)>0) stringResource(R.string.common_resume) else stringResource(R.string.common_play), onClick={ if (current != null) openCurrent(current) else onPlay(m.videoId) }, modifier=Modifier.focusRequester(primaryFocusRequester))
                                state.channel?.takeIf { !state.offline }?.let { channel ->
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
                    state.channel?.takeIf { !state.offline }?.let { channel ->
                        val channelItem = com.ambient.tv.youtube.YouTubeItem(
                            videoId = com.ambient.tv.youtube.youtubeChannelKey(channel.channelId),
                            title = channel.title ?: m.author.orEmpty().ifBlank { stringResource(R.string.youtube_channel_open) },
                            subtitle = stringResource(R.string.youtube_channel_open),
                            imageUrl = channel.avatarUrl,
                            channelId = channel.channelId,
                        )
                        YouTubeDetailRow(
                            stringResource(R.string.youtube_channel_open),
                            listOf(channelItem.videoId),
                        ) { rowFocus, rowKey ->
                            item(channelItem.videoId) {
                                YouTubeItemCard(
                                    channelItem,
                                    { _ -> onOpenChannel(channel) },
                                    youtubeItemMotionModifier().width(deviceDp(300.dp, 240.dp))
                                        .rowColumnFocusTarget(rowFocus, rowKey, channelItem.videoId),
                                )
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
                        YouTubeDetailRow(row.title.takeUnless { it == "SmartTube" } ?: stringResource(R.string.youtube_suggestions),
                            row.items.map { it.videoId }) { rowFocus, rowKey ->
                            items(row.items, key = { it.videoId }) { item ->
                                YouTubeItemCard(item, onOpenItem, youtubeItemMotionModifier().width(deviceDp(300.dp, 240.dp))
                                    .rowColumnFocusTarget(rowFocus, rowKey, item.videoId))
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

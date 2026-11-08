package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.theme.animatedFocusSurface
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi

import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.handheldDetailStacks

import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ambient.tv.ui.common.DetailArtworkFrame
import com.ambient.tv.ui.common.DetailDescriptionSection
import com.ambient.tv.ui.common.DetailPrimaryMetaRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.PageUtilityBar
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
                modifier = Modifier
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YouTubeDetailScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenChannel: (com.ambient.tv.youtube.YouTubeChannelInfo) -> Unit = {},
    /** Related video or channel tile; the caller applies the selection behavior. */
    onOpenItem: (String) -> Unit = {},
    /** The source post is a separate destination; its text never supplies YouTube metadata. */
    onOpenOriginalPost: ((Long, Int) -> Unit)? = null,
    viewModel: YouTubeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(setOf("youtube"), viewModel::refresh)
    val videoId = viewModel.videoId
    val metadata = state.metadata
    val origin = state.origin
    val handheld = LocalHandheldUi.current
    val configuration = LocalConfiguration.current
    val visibleBackgroundVideo = LocalVisibleBackgroundVideo.current || com.ambient.tv.ui.common.LocalHomeBackgroundVisible.current
    val current = com.ambient.tv.companion.playbackIndicator(com.ambient.tv.companion.CompanionMedia("youtube", videoId))
    val openCurrent = com.ambient.tv.companion.LocalOpenActivePlayback.current
    val canOpenPlayback = current != null || !state.offline
    val hasOriginalPostAction = origin != null && onOpenOriginalPost != null
    val primaryFocus = remember(videoId) { FocusRequester() }
    val originalPostFocus = remember(videoId) { FocusRequester() }
    var returnToOriginalPost by rememberSaveable(videoId) { mutableStateOf(false) }
    var initialFocusRequested by remember(videoId) { mutableStateOf(false) }
    // A known video ID is enough for Play. Do not first focus the utility bar or wait for
    // network metadata, then visibly move the focus after the screen is already displayed.
    val initialUtilityFocus = remember(videoId) { !canOpenPlayback && !hasOriginalPostAction }
    LaunchedEffect(videoId) {
        if (handheld || initialFocusRequested || initialUtilityFocus) return@LaunchedEffect
        val target = if (hasOriginalPostAction && (returnToOriginalPost || !canOpenPlayback)) originalPostFocus else primaryFocus
        repeat(5) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                initialFocusRequested = true
                returnToOriginalPost = false
                return@LaunchedEffect
            }
        }
    }
    val title = metadata?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.youtube_detail_video)
    val artworkFallbacks = remember(videoId) { com.ambient.tv.youtube.youtubePanelThumbnailUrls(videoId) }
    val artwork = metadata?.imageUrl?.takeIf(String::isNotBlank) ?: artworkFallbacks.first()
    val publication = remember(metadata?.publishedDate, metadata?.publishedAtMs, configuration.locales[0]) {
        com.ambient.tv.youtube.youtubePublicationText(metadata?.publishedDate)
            ?: metadata?.publishedAtMs?.let { com.ambient.tv.youtube.youtubePublicationEpochMs(it) }
                ?.let { com.ambient.tv.ui.common.relativePublishedLabel(it, withTime = true) }
    }
    val resume = metadata?.let {
        !it.isLive && (com.ambient.tv.youtube.YouTubeLocalProgress.resumePositionMs(videoId, it.durationMs, it.percentWatched) ?: 0L) > 0L
    } == true
    val actionLabel = when {
        current != null -> R.string.playback_open
        resume -> R.string.common_resume
        origin != null -> R.string.youtube_detail_play_smarttube
        else -> R.string.common_play
    }
    val contentActionFocus = if (canOpenPlayback) primaryFocus else originalPostFocus.takeIf { hasOriginalPostAction }
    val screenHeight = configuration.screenHeightDp.dp

    Box(Modifier.fillMaxSize()) {
        if (!visibleBackgroundVideo) ArtworkImage(
            model = metadata?.backgroundUrl ?: artwork,
            title = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            focusCrop = true,
            placeholder = false,
            fallbackModels = artworkFallbacks,
        )
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .66f)))
        // Only the bounded description scrolls while reading. The page scroll remains for
        // chapters and related videos, which are below the common detail header.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = deviceDp(46.dp, 16.dp))) {
            DeviceActionRow(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = if (handheld) 0.dp else screenHeight * .86f)
                    .padding(start = deviceDp(46.dp, 16.dp), end = deviceDp(46.dp, 16.dp),
                        top = deviceDp(82.dp, 72.dp), bottom = deviceDp(24.dp, 16.dp)),
                stack = handheldDetailStacks(configuration.screenWidthDp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.Top,
            ) {
                DetailArtworkFrame(
                    model = artwork,
                    title = title,
                    aspectRatio = 16f / 9f,
                    width = deviceDp(420.dp, 320.dp),
                    fallbackModels = artworkFallbacks,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.ambient.tv.ui.common.SystemBadge(com.ambient.tv.ui.common.MediaSystem.YOUTUBE)
                    // Reserve the heading/author/meta slots so late metadata does not push
                    // the initially focused action down or change its D-pad neighbours.
                    Text(title, style = MaterialTheme.typography.headlineLarge,
                        minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.heightIn(min = deviceDp(32.dp, 28.dp)),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        metadata?.author?.takeIf(String::isNotBlank)?.let { author ->
                            metadata.authorImageUrl?.let { url ->
                                ArtworkImage(model = url, title = author,
                                    modifier = Modifier.size(deviceDp(32.dp, 28.dp)).clip(CircleShape), contentScale = ContentScale.Crop)
                            }
                            Text(author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.heightIn(min = 24.dp)) {
                        DetailPrimaryMetaRow(
                            primaryMeta = listOfNotNull(publication,
                                metadata?.takeUnless { it.isLive }?.durationMs?.takeIf { it > 0L }
                                    ?.let { com.ambient.tv.ui.common.formatDuration(it) },
                                metadata?.viewCount?.takeIf(String::isNotBlank)),
                            item = null,
                            leading = if (metadata?.isLive == true) ({
                                com.ambient.tv.ui.common.MetaBadge(stringResource(R.string.youtube_live_badge), emphasized = true)
                            }) else null,
                        )
                    }
                    // These actions are mounted immediately and never sit below a long post.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(
                            text = stringResource(actionLabel),
                            onClick = { if (current != null) openCurrent(current) else onPlay(videoId) },
                            enabled = canOpenPlayback,
                            icon = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                            // Reserve room for the full SmartTube label even before the saved
                            // position is known, so Resume cannot shift Original post sideways.
                            modifier = Modifier.widthIn(min = deviceDp(240.dp, 220.dp)).focusRequester(primaryFocus),
                        )
                        if (origin != null && onOpenOriginalPost != null) TvActionButton(
                            text = stringResource(R.string.youtube_detail_original_post),
                            onClick = {
                                returnToOriginalPost = true
                                onOpenOriginalPost(origin.chatId, origin.messageId)
                            },
                            icon = androidx.compose.material.icons.Icons.Filled.Description,
                            modifier = Modifier.focusRequester(originalPostFocus),
                        )
                    }
                    com.ambient.tv.companion.PlaybackStatusSection(current)
                    if (current != null && !state.offline) com.ambient.tv.companion.CompanionChooseDeviceAction { onPlay(videoId) }
                    if (!state.offline && (state.channel != null || state.signedIn)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.channel?.let { channel ->
                                TvActionButton(channel.title ?: stringResource(R.string.youtube_channel_open),
                                    onClick = { onOpenChannel(channel) }, icon = androidx.compose.material.icons.Icons.Filled.AccountCircle)
                                channel.subscribed?.let { subscribed ->
                                    TvActionButton(stringResource(if (subscribed) R.string.youtube_subscribed else R.string.youtube_subscribe),
                                        onClick = viewModel::toggleSubscription, selected = subscribed,
                                        icon = androidx.compose.material.icons.Icons.Filled.Subscriptions)
                                }
                            }
                            if (state.signedIn && metadata != null) {
                                TvActionButton(metadata.likeCount ?: stringResource(R.string.youtube_like),
                                    onClick = { viewModel.rate(YouTubeLikeStatus.LIKE) }, selected = state.likeStatus == YouTubeLikeStatus.LIKE,
                                    icon = androidx.compose.material.icons.Icons.Filled.ThumbUp)
                                TvActionButton(stringResource(R.string.youtube_dislike),
                                    onClick = { viewModel.rate(YouTubeLikeStatus.DISLIKE) }, selected = state.likeStatus == YouTubeLikeStatus.DISLIKE,
                                    icon = androidx.compose.material.icons.Icons.Filled.ThumbDown)
                            }
                        }
                    }
                    if (state.offline) Text(stringResource(R.string.browse_offline), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (origin != null) {
                        val shared = origin.channel?.let { stringResource(R.string.youtube_detail_shared_in, it) }
                            ?: stringResource(R.string.youtube_detail_shared_monogram)
                        val postDate = remember(origin.publishedAtMs, configuration.locales[0]) {
                            origin.publishedAtMs.takeIf { it > 0L }
                                ?.let { com.ambient.tv.ui.common.relativePublishedLabel(it, withTime = true) }
                        }
                        Text(listOfNotNull(shared, postDate).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DetailDescriptionSection(metadata?.description, actionFocus = contentActionFocus,
                        maxHeight = if (handheld) 220.dp else (screenHeight * .25f).coerceIn(100.dp, 220.dp))
                    if (metadata == null && state.loading) {
                        Text(stringResource(R.string.player_loading), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (state.error != null) {
                        Text(stringResource(R.string.youtube_detail_metadata_unavailable),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TvActionButton(stringResource(R.string.home_retry), onClick = viewModel::load,
                            icon = androidx.compose.material.icons.Icons.Filled.Refresh)
                    }
                }
            }
            if (metadata != null && !state.offline) {
                if (metadata.chapters.isNotEmpty()) YouTubeDetailChapters(metadata.chapters) { chapter ->
                    // A chapter of the running video seeks it; otherwise the player starts there.
                    if (current != null) {
                        openCurrent(current)
                        com.ambient.tv.ui.player.BackgroundPlaybackSession.seekTo(chapter.startMs)
                    } else {
                        YouTubeStartRequest.request(videoId, chapter.startMs)
                        onPlay(videoId)
                    }
                }
                metadata.suggestions.filter { it.items.isNotEmpty() }.forEach { row ->
                    YouTubeDetailRow(row.title.takeUnless { it == "SmartTube" } ?: stringResource(R.string.youtube_suggestions),
                        row.items.map { it.videoId }) { rowFocus, rowKey ->
                        items(row.items, key = { it.videoId }) { item ->
                            YouTubeItemCard(item, onOpenItem, Modifier.width(deviceDp(300.dp, 240.dp))
                                .rowColumnFocusTarget(rowFocus, rowKey, item.videoId))
                        }
                    }
                }
            }
        }
        PageUtilityBar(
            onBack = onBack,
            onSettings = onOpenSettings,
            onDown = contentActionFocus?.let { focus -> ({ runCatching { focus.requestFocus() }; Unit }) },
            requestInitialFocus = initialUtilityFocus,
            leftNavigatesBack = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

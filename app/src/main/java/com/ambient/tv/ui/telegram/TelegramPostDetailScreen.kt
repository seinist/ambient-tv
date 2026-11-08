package com.ambient.tv.ui.telegram

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.companion.CompanionChooseDeviceAction
import com.ambient.tv.companion.CompanionMedia
import com.ambient.tv.companion.LocalOpenActivePlayback
import com.ambient.tv.companion.PlaybackStatusSection
import com.ambient.tv.companion.playbackIndicator
import com.ambient.tv.companion.telegramCompanionId
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.data.telegram.monogramThumbModel
import com.ambient.tv.ui.common.BrowseStatusKind
import com.ambient.tv.ui.common.BrowseStatusPanel
import com.ambient.tv.ui.common.DetailArtworkFrame
import com.ambient.tv.ui.common.DetailDescriptionSection
import com.ambient.tv.ui.common.DetailEyebrow
import com.ambient.tv.ui.common.DetailPrimaryMetaRow
import com.ambient.tv.ui.common.PageBackground
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.RegisterRowColumnFocus
import com.ambient.tv.ui.common.SystemBadge
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.containRowFocusExit
import com.ambient.tv.ui.common.formatDuration
import com.ambient.tv.ui.common.relativePublishedLabel
import com.ambient.tv.ui.common.rememberRowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusTarget
import com.ambient.tv.ui.common.rowColumnFocusWrapRight
import com.ambient.tv.ui.common.skeletonPulseAlpha
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.handheldDetailStacks
import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.youtube.youtubeThumbnailUrl
import kotlinx.coroutines.CancellationException

/**
 * A normal Ambient TV detail destination, not a second player overlay.
 * Keep post paragraphs and URLs intact; cards may abbreviate text, details do not.
 */
@Composable
fun TelegramPostDetailScreen(
    chatId: Long,
    messageId: Int,
    repository: TelegramRepository,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChannel: (Long) -> Unit,
    onPlayMedia: (Long, Int) -> Unit,
    /** Photos open in the photo viewer, not the player. */
    onOpenPhoto: (TelegramMedia) -> Unit = { onPlayMedia(it.chatId, it.messageId) },
    /** Explicit playback action: bypasses the browse selection's Details/Ask preference. */
    onOpenYouTube: (String) -> Unit,
) {
    var media by remember(chatId, messageId) { mutableStateOf<TelegramMedia?>(null) }
    var error by remember(chatId, messageId) { mutableStateOf<String?>(null) }
    var loading by remember(chatId, messageId) { mutableStateOf(true) }
    var retry by remember(chatId, messageId) { mutableIntStateOf(0) }
    val unavailable = stringResource(R.string.monogram_error_post_unavailable)
    val context = LocalContext.current

    LaunchedEffect(chatId, messageId, retry) {
        loading = true
        error = null
        try {
            media = repository.media(chatId, messageId)
            if (media == null) error = unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (wait: com.ambient.tv.data.telegram.TelegramFloodWaitException) {
            // Count the pause down on screen, then load again by itself (restarts this effect).
            loading = false
            for (left in wait.seconds downTo 1) {
                error = context.getString(R.string.monogram_error_flood_wait, left)
                kotlinx.coroutines.delay(1_000)
            }
            retry++
        } catch (lost: com.ambient.tv.data.telegram.TelegramSessionLostException) {
            error = context.getString(R.string.monogram_error_session_lost, lost.type)
        } catch (failure: Exception) {
            error = failure.message ?: unavailable
        } finally {
            loading = false
        }
    }

    // TV: focus starts on the post's main action (play/open), not nowhere, so D-pad works at once.
    val primaryFocus = remember(chatId, messageId) { FocusRequester() }
    val handheld = LocalHandheldUi.current
    LaunchedEffect(media?.mediaKey) {
        if (handheld || media == null) return@LaunchedEffect
        repeat(5) {
            withFrameNanos { }
            if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageBackground(Modifier.fillMaxSize())
        val selected = media
        when {
            loading && selected == null -> TelegramPostDetailSkeleton()
            selected == null -> BrowseStatusPanel(
                title = stringResource(R.string.telegram_error),
                body = error,
                kind = BrowseStatusKind.ERROR,
                actionLabel = stringResource(R.string.home_retry),
                onAction = { retry++ },
                modifier = Modifier.fillMaxSize(),
            )
            else -> TelegramPostDetails(
                media = selected,
                repository = repository,
                primaryFocus = primaryFocus,
                onOpenChannel = onOpenChannel,
                onPlayMedia = onPlayMedia,
                onOpenPhoto = onOpenPhoto,
                onPlayYouTube = onOpenYouTube,
            )
        }
        PageUtilityBar(onBack = onBack, onSettings = onOpenSettings,
            onDown = if (media != null) ({ runCatching { primaryFocus.requestFocus() } }) else null,
            leftNavigatesBack = false, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** The original post uses the same artwork / metadata / actions / description order as video details. */
@Composable
private fun TelegramPostDetails(
    media: TelegramMedia,
    repository: TelegramRepository,
    primaryFocus: FocusRequester,
    onOpenChannel: (Long) -> Unit,
    onPlayMedia: (Long, Int) -> Unit,
    onOpenPhoto: (TelegramMedia) -> Unit,
    onPlayYouTube: (String) -> Unit,
) {
    val actions = remember(media) { telegramPostDetailActions(media) }
    val current = if (actions.native == TelegramPostNativeAction.PLAY) {
        playbackIndicator(CompanionMedia("telegram", telegramCompanionId(media.chatId, media.messageId)))
    } else null
    val openCurrent = LocalOpenActivePlayback.current
    var resumePosition by remember(media.mediaKey) {
        mutableLongStateOf(repository.resumePositionMs(media.mediaKey))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumePosition = repository.resumePositionMs(media.mediaKey)
    }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val published = remember(media.publishedAtMs, locale) {
        media.publishedAtMs.takeIf { it > 0L }?.let { relativePublishedLabel(it, withTime = true) }
    }
    val duration = media.durationMs.takeIf {
        media.kind == TelegramMediaKind.VIDEO || media.kind == TelegramMediaKind.AUDIO
    }?.let(::formatDuration)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = screenGutter(), end = screenGutter(), top = deviceDp(82.dp, 72.dp), bottom = 36.dp),
    ) {
        DeviceActionRow(
            modifier = Modifier.fillMaxWidth(),
            stack = handheldDetailStacks(configuration.screenWidthDp),
            horizontalArrangement = Arrangement.spacedBy(deviceDp(36.dp, 20.dp)),
            verticalAlignment = Alignment.Top,
        ) {
            TelegramPostArtwork(media, actions.primaryYouTubeId)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SystemBadge(MediaSystem.TELEGRAM)
                Text(media.title, style = MaterialTheme.typography.headlineLarge,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                DetailEyebrow(media.channel)
                DetailPrimaryMetaRow(listOfNotNull(published, duration), item = null)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    actions.native?.let { native ->
                        TvActionButton(
                            text = stringResource(when {
                                native == TelegramPostNativeAction.PHOTO -> R.string.monogram_open_photo
                                current != null -> R.string.playback_open
                                resumePosition > 0L -> R.string.common_resume
                                else -> R.string.common_play
                            }),
                            onClick = {
                                when {
                                    native == TelegramPostNativeAction.PHOTO -> onOpenPhoto(media)
                                    current != null -> openCurrent(current)
                                    else -> onPlayMedia(media.chatId, media.messageId)
                                }
                            },
                            icon = if (native == TelegramPostNativeAction.PHOTO) Icons.Filled.Image else Icons.Filled.PlayArrow,
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                    }
                    actions.primaryYouTubeId?.let { videoId ->
                        TelegramPostYouTubeAction(videoId, onPlayYouTube, Modifier.focusRequester(primaryFocus))
                    }
                    TvActionButton(
                        text = stringResource(R.string.monogram_detail_open_channel),
                        onClick = { onOpenChannel(media.chatId) },
                        icon = Icons.Filled.AccountCircle,
                        modifier = if (!actions.hasPrimaryAction && actions.linkedYouTubeIds.isEmpty())
                            Modifier.focusRequester(primaryFocus) else Modifier,
                    )
                    if (current != null) CompanionChooseDeviceAction { onPlayMedia(media.chatId, media.messageId) }
                }
                if (current != null) PlaybackStatusSection(current)
                if (media.protectedContent) {
                    Text(stringResource(R.string.monogram_protected_post),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Links are actions belonging to the post, and stay reachable before its long caption.
                if (actions.linkedYouTubeIds.isNotEmpty()) {
                    TelegramPostYouTubeLinks(
                        postKey = media.mediaKey,
                        videos = actions.linkedYouTubeIds,
                        onPlay = onPlayYouTube,
                        initialFocus = primaryFocus.takeUnless { actions.hasPrimaryAction },
                    )
                }
                if (!media.summary.isNullOrBlank()) {
                    Text(stringResource(R.string.monogram_post_text), style = MaterialTheme.typography.titleMedium)
                    DetailDescriptionSection(media.summary, actionFocus = primaryFocus,
                        maxHeight = deviceDp(180.dp, 220.dp))
                }
                TelegramPostFileInfo(media)
            }
        }
    }
}

@Composable
private fun TelegramPostArtwork(media: TelegramMedia, singleYouTubeId: String?) {
    // A multiple-link original post represents its channel; it must not present one link as selected.
    val channelImage = monogramThumbModel(media.chatId, 0, null)
    val isYouTube = media.kind == TelegramMediaKind.YOUTUBE
    val model = if (isYouTube) singleYouTubeId?.let(::youtubeThumbnailUrl) ?: channelImage
        else media.cardImageUrl ?: channelImage
    val logo = model == channelImage
    val aspect = when {
        logo || media.kind == TelegramMediaKind.AUDIO -> 1f
        isYouTube -> 16f / 9f
        media.width > 0 && media.height > 0 -> (media.width.toFloat() / media.height).coerceIn(0.5f, 16f / 9f)
        media.kind == TelegramMediaKind.PHOTO -> 1f
        else -> 16f / 9f
    }
    val width = when {
        aspect < 1f -> deviceDp(190.dp, 112.dp)
        aspect == 1f -> deviceDp(210.dp, 144.dp)
        else -> deviceDp(340.dp, 230.dp)
    }
    DetailArtworkFrame(
        model = model,
        title = media.title,
        aspectRatio = aspect,
        width = width,
        contentScale = ContentScale.Fit,
        fallbackModels = if (logo) emptyList() else listOf(channelImage),
    )
}

@Composable
private fun TelegramPostYouTubeAction(videoId: String, onPlay: (String) -> Unit, modifier: Modifier = Modifier) {
    val current = playbackIndicator(CompanionMedia("youtube", videoId))
    val openCurrent = LocalOpenActivePlayback.current
    TvActionButton(
        text = stringResource(if (current != null) R.string.playback_open else R.string.monogram_detail_play_youtube),
        onClick = { if (current != null) openCurrent(current) else onPlay(videoId) },
        icon = Icons.Filled.PlayArrow,
        modifier = modifier,
    )
}

@Composable
private fun TelegramPostYouTubeLinks(
    postKey: String,
    videos: List<String>,
    onPlay: (String) -> Unit,
    initialFocus: FocusRequester?,
) {
    val rowFocus = rememberRowColumnFocus()
    val rowKey = "monogram-post-links:$postKey"
    val remembered = rowFocus.rememberedItem(rowKey)?.toString()?.takeIf { it in videos } ?: videos.first()
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = videos.indexOf(remembered))
    RegisterRowColumnFocus(rowFocus, rowKey, rowState, videos)
    Column(
        Modifier.fillMaxWidth().rowColumnFocusWrapRight(rowFocus)
            // The requester belongs to the stable group, not a lazy child that can be disposed
            // while browsing more links. Down from the utility bar can always re-enter the row.
            .then(if (initialFocus != null) Modifier.focusRequester(initialFocus).focusGroup() else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.telegram_youtube), style = MaterialTheme.typography.titleMedium)
        LazyRow(state = rowState, modifier = Modifier.containRowFocusExit(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(videos, key = { _, id -> id }) { index, videoId ->
                val title = stringResource(R.string.monogram_detail_video_number, index + 1)
                Column(Modifier.width(deviceDp(260.dp, 220.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailArtworkFrame(youtubeThumbnailUrl(videoId), title, aspectRatio = 16f / 9f,
                        width = deviceDp(260.dp, 220.dp))
                    Text(title, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TelegramPostYouTubeAction(
                        videoId, onPlay,
                        Modifier.rowColumnFocusTarget(rowFocus, rowKey, videoId, soundsOnFocus = true),
                    )
                }
            }
        }
    }
}

@Composable
private fun TelegramPostFileInfo(media: TelegramMedia) {
    if (media.kind == TelegramMediaKind.YOUTUBE) return
    val context = LocalContext.current
    val visual = media.kind == TelegramMediaKind.VIDEO || media.kind == TelegramMediaKind.PHOTO
    val facts = listOfNotNull(
        if (visual && media.width > 0 && media.height > 0) "${media.width}×${media.height}" else null,
        media.fileSize.takeIf { it > 0L }?.let { android.text.format.Formatter.formatFileSize(context, it) },
    )
    val fileName = media.fileName?.trim()?.takeIf { it.isNotBlank() && it != media.title }
    if (facts.isEmpty() && fileName == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.monogram_detail_file_info), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DetailDescriptionSection(
            text = listOfNotNull(facts.takeIf { it.isNotEmpty() }?.joinToString(" · "), fileName).joinToString("\n"),
            maxHeight = deviceDp(100.dp, 140.dp),
        )
    }
}

/** Match the compact artwork and action-first header while the original post loads. */
@Composable
private fun TelegramPostDetailSkeleton() {
    val pulse = com.ambient.tv.ui.common.rememberSkeletonPulseOrNull()
    val fill = MaterialTheme.colorScheme.surfaceVariant
    fun Modifier.bar(radius: androidx.compose.ui.unit.Dp) =
        skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(radius))
    DeviceActionRow(
        Modifier.fillMaxSize()
            .padding(start = screenGutter(), end = screenGutter(), top = deviceDp(82.dp, 72.dp), bottom = 36.dp),
        stack = handheldDetailStacks(LocalConfiguration.current.screenWidthDp),
        horizontalArrangement = Arrangement.spacedBy(deviceDp(36.dp, 20.dp)),
    ) {
        Box(Modifier.width(deviceDp(340.dp, 230.dp)).aspectRatio(16f / 9f).bar(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(96.dp).height(24.dp).bar(12.dp))
            Box(Modifier.fillMaxWidth(.8f).height(34.dp).bar(8.dp))
            Box(Modifier.width(180.dp).height(18.dp).bar(4.dp))
            Box(Modifier.width(140.dp).height(14.dp).bar(4.dp))
            DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(140.dp).height(48.dp).bar(10.dp))
                Box(Modifier.width(140.dp).height(48.dp).bar(10.dp))
            }
            repeat(3) { Box(Modifier.fillMaxWidth(if (it == 2) .7f else 1f).height(14.dp).bar(4.dp)) }
        }
    }
}

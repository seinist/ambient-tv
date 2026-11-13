package com.ambient.tv.ui.telegram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.BrowseStatusKind
import com.ambient.tv.ui.common.BrowseStatusPanel
import com.ambient.tv.ui.common.PageBackground
import com.ambient.tv.ui.common.PageUtilityBar
import com.ambient.tv.ui.common.SystemBadge
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.DetailSkeletonBlock
import com.ambient.tv.ui.common.DetailSkeletonParagraph
import com.ambient.tv.ui.common.RowColumnFocus
import com.ambient.tv.ui.common.rememberRowColumnFocus
import com.ambient.tv.ui.common.RegisterRowColumnFocus
import com.ambient.tv.ui.common.rowColumnFocusTarget
import com.ambient.tv.ui.common.rowColumnFocusWrapRight
import com.ambient.tv.ui.common.containRowFocusExit
import com.ambient.tv.ui.theme.screenGutter
import androidx.compose.ui.layout.layout
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    onOpenYouTube: (String) -> Unit,
) {
    var media by remember(chatId, messageId) { mutableStateOf<TelegramMedia?>(null) }
    var error by remember(chatId, messageId) { mutableStateOf<String?>(null) }
    var loading by remember(chatId, messageId) { mutableStateOf(true) }
    var retry by remember(chatId, messageId) { mutableIntStateOf(0) }
    val unavailable = stringResource(R.string.monogram_error_post_unavailable)
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val smartTube = remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            TelegramDetailYouTubeEntryPoint::class.java,
        ).engine()
    }

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
    val primaryFocus = remember(chatId, messageId) { androidx.compose.ui.focus.FocusRequester() }
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    LaunchedEffect(media?.mediaKey) {
        if (handheld || media == null) return@LaunchedEffect
        repeat(3) {
            androidx.compose.runtime.withFrameNanos { }
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
            else -> {
                val youtubeLinks = remember(selected.mediaKey, selected.summary) {
                    com.ambient.tv.ui.player.playerDetailYouTubeLinks(selected.summary)
                }
                val videos = remember(selected.mediaKey, selected.summary) {
                    (selected.youtubeIds + youtubeLinks.filter { it.kind == com.ambient.tv.ui.player.PlayerDetailYouTubeLinkKind.VIDEO }.map { it.value }).distinct()
                }
                val channels = remember(selected.mediaKey, selected.summary) {
                    youtubeLinks.filter { it.kind != com.ambient.tv.ui.player.PlayerDetailYouTubeLinkKind.VIDEO }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = screenGutter(), end = screenGutter(), top = 94.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SystemBadge(MediaSystem.TELEGRAM)
                    Text(selected.title, style = MaterialTheme.typography.headlineLarge)
                    TvActionButton(selected.channel, onClick = { onOpenChannel(selected.chatId) },
                        modifier = if (selected.playableOnTelegram) Modifier
                            else Modifier.focusRequester(primaryFocus))
                    if (selected.publishedAtMs > 0L) {
                        val formatted = remember(selected.publishedAtMs) {
                            com.ambient.tv.ui.common.relativePublishedLabel(selected.publishedAtMs, withTime = true)
                        }
                        Text(formatted, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    selected.cardImageUrl?.takeIf { selected.kind != TelegramMediaKind.YOUTUBE }?.let {
                        ArtworkImage(it, selected.title,
                            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit)
                    }
                    if (selected.playableOnTelegram) {
                        TvActionButton(
                            stringResource(if (selected.kind == TelegramMediaKind.PHOTO)
                                R.string.monogram_open_photo else R.string.common_play),
                            onClick = {
                                if (selected.kind == TelegramMediaKind.PHOTO) onOpenPhoto(selected)
                                else onPlayMedia(selected.chatId, selected.messageId)
                            },
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                    } else if (selected.protectedContent) {
                        Text(stringResource(R.string.monogram_protected_post),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    selected.summary?.takeIf(String::isNotBlank)?.let { fullText ->
                        Text(stringResource(R.string.monogram_post_text),
                            style = MaterialTheme.typography.titleMedium)
                        // Paragraphs are focus stops: D-pad down scrolls through a long post.
                        fullText.split(Regex("\\n\\s*\\n")).filter(String::isNotBlank).forEach { paragraph ->
                            var focused by remember { mutableStateOf(false) }
                            Text(paragraph.trim(), style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth()
                                    .background(
                                        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        RoundedCornerShape(8.dp))
                                    .onFocusChanged { focused = it.isFocused }
                                    .focusable())
                        }
                    }
                    // Links to other Telegram posts and channels, openable in Monogram.
                    val telegramLinks = remember(selected.mediaKey, selected.summary) {
                        com.ambient.tv.ui.player.playerDetailTelegramLinks(selected.summary)
                            .filterNot { it.chatId == selected.chatId && it.messageId == selected.messageId }
                    }
                    com.ambient.tv.ui.player.DescriptionLinkButtons(emptyList(), telegramLinks, lockHorizontal = false)
                    if (videos.isNotEmpty()) {
                        TelegramDetailYouTubeRow(
                            title = stringResource(R.string.telegram_youtube),
                            items = videos.map { id ->
                                com.ambient.tv.youtube.YouTubeItem(
                                    videoId = id,
                                    title = stringResource(R.string.monogram_open_youtube),
                                    subtitle = selected.channel,
                                    imageUrl = com.ambient.tv.youtube.youtubeThumbnailUrl(id),
                                )
                            },
                            onOpen = onOpenYouTube,
                        )
                    }
                    if (channels.isNotEmpty()) {
                        TelegramDetailYouTubeRow(
                            title = stringResource(R.string.youtube_channel_open),
                            items = channels.map { link ->
                                com.ambient.tv.youtube.YouTubeItem(
                                    videoId = when (link.kind) {
                                        com.ambient.tv.ui.player.PlayerDetailYouTubeLinkKind.CHANNEL_ID ->
                                            com.ambient.tv.youtube.youtubeChannelKey(link.value)
                                        else -> "channel-query:" + link.value
                                    },
                                    title = link.value,
                                    subtitle = link.label,
                                )
                            },
                            onOpen = { key ->
                                com.ambient.tv.youtube.youtubeChannelIdOf(key)?.let {
                                    com.ambient.tv.launcher.LauncherIntents.post(
                                        com.ambient.tv.ui.navigation.Destinations.youtubeChannel(it))
                                } ?: key.removePrefix("channel-query:").takeIf { key.startsWith("channel-query:") }?.let { query ->
                                    scope.launch {
                                        val found = runCatching {
                                            smartTube.search("@" + query).asSequence()
                                                .flatMap { it.items.asSequence() }
                                                .firstOrNull { it.isChannel }
                                        }.getOrNull()
                                        val id = found?.let { com.ambient.tv.youtube.youtubeChannelIdOf(it.videoId) } ?: found?.channelId
                                        if (!id.isNullOrBlank()) {
                                            com.ambient.tv.launcher.LauncherIntents.post(
                                                com.ambient.tv.ui.navigation.Destinations.youtubeChannel(id, found?.title, found?.imageUrl))
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        PageUtilityBar(onBack = onBack, onSettings = onOpenSettings,
            onDown = if (media != null) ({ runCatching { primaryFocus.requestFocus() } }) else null,
            leftNavigatesBack = false, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** Same column, paddings and block sizes as the loaded post, so content replaces it in place. */
@Composable
private fun TelegramPostDetailSkeleton() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = screenGutter(), end = screenGutter(), top = 94.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        DetailSkeletonBlock(Modifier.width(96.dp).height(24.dp), radius = 12.dp) // system badge
        DetailSkeletonBlock(Modifier.fillMaxWidth(0.6f).height(34.dp)) // headline
        DetailSkeletonBlock(Modifier.width(180.dp).height(48.dp), radius = 10.dp) // channel
        DetailSkeletonBlock(Modifier.width(140.dp).height(14.dp), radius = 5.dp) // date
        DetailSkeletonBlock(Modifier.fillMaxWidth().height(230.dp), radius = 12.dp) // media
        DetailSkeletonBlock(Modifier.width(140.dp).height(48.dp), radius = 10.dp) // primary action
        DetailSkeletonBlock(Modifier.width(160.dp).height(22.dp), radius = 6.dp) // post-text heading
        DetailSkeletonParagraph(lines = 5, lineHeight = 16.dp)
        // Posts can contain linked videos/channels, with cards below the text.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), userScrollEnabled = false) {
            items(3) {
                Column(Modifier.width(com.ambient.tv.ui.mobile.deviceDp(300.dp, 240.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailSkeletonBlock(Modifier.fillMaxWidth().height(135.dp), radius = 10.dp)
                    DetailSkeletonBlock(Modifier.fillMaxWidth(0.65f).height(13.dp), radius = 5.dp)
                }
            }
        }
    }
}


@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface TelegramDetailYouTubeEntryPoint {
    fun engine(): com.ambient.tv.youtube.SmartTubeEngine
}

@Composable
private fun TelegramDetailYouTubeRow(
    title: String,
    items: List<com.ambient.tv.youtube.YouTubeItem>,
    onOpen: (String) -> Unit,
) {
    if (items.isEmpty()) return
    val focus = rememberRowColumnFocus()
    val rowKey = "telegram-detail:" + title
    val state = rememberLazyListState()
    RegisterRowColumnFocus(focus, rowKey, state, items.map { it.videoId })
    Column(
        Modifier.fillMaxWidth().rowColumnFocusWrapRight(focus),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        // The page is inset by the gutter. The row reaches into it on both sides and insets its
        // cards again, so a focused (scaled, outlined) card is not cut off at the row's edges.
        val gutter = screenGutter()
        LazyRow(
            state = state,
            modifier = Modifier.bleedHorizontally(gutter).containRowFocusExit(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = gutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.videoId }) { item ->
                com.ambient.tv.ui.youtube.YouTubeItemCard(
                    item,
                    onOpen,
                    Modifier.width(com.ambient.tv.ui.mobile.deviceDp(300.dp, 240.dp))
                        .rowColumnFocusTarget(focus, rowKey, item.videoId),
                )
            }
        }
    }
}

/** Widens the element by [amount] on both sides, beyond its parent's padding. */
private fun Modifier.bleedHorizontally(amount: androidx.compose.ui.unit.Dp): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val extra = amount.roundToPx()
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = constraints.maxWidth + 2 * extra
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
    })

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ambient.tv.ui.theme.screenGutter
import java.text.DateFormat
import java.util.Date
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
    onOpenYouTube: (String) -> Unit,
) {
    var media by remember(chatId, messageId) { mutableStateOf<TelegramMedia?>(null) }
    var error by remember(chatId, messageId) { mutableStateOf<String?>(null) }
    var loading by remember(chatId, messageId) { mutableStateOf(true) }
    var retry by remember(chatId, messageId) { mutableIntStateOf(0) }

    LaunchedEffect(chatId, messageId, retry) {
        loading = true
        error = null
        try {
            media = repository.media(chatId, messageId)
            if (media == null) error = "Monogram post unavailable"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Monogram post unavailable"
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageBackground(Modifier.fillMaxSize())
        val selected = media
        when {
            loading && selected == null ->
                androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
            selected == null -> BrowseStatusPanel(
                title = stringResource(R.string.telegram_error),
                body = error,
                kind = BrowseStatusKind.ERROR,
                actionLabel = stringResource(R.string.home_retry),
                onAction = { retry++ },
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val videos = remember(selected.mediaKey, selected.summary) { selected.youtubeIds }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = screenGutter(), end = screenGutter(), top = 94.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SystemBadge(MediaSystem.TELEGRAM)
                    Text(selected.title, style = MaterialTheme.typography.headlineLarge)
                    TvActionButton(selected.channel, onClick = { onOpenChannel(selected.chatId) })
                    if (selected.publishedAtMs > 0L) {
                        val formatted = remember(selected.publishedAtMs) {
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(selected.publishedAtMs))
                        }
                        Text(formatted, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    selected.thumbnailUrl?.takeIf { selected.kind != TelegramMediaKind.YOUTUBE }?.let {
                        ArtworkImage(it, selected.title,
                            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit)
                    }
                    if (selected.playableOnTelegram) {
                        TvActionButton(
                            stringResource(if (selected.kind == TelegramMediaKind.PHOTO)
                                R.string.monogram_open_photo else R.string.common_play),
                            onClick = { onPlayMedia(selected.chatId, selected.messageId) },
                        )
                    } else if (selected.protectedContent) {
                        Text(stringResource(R.string.monogram_protected_post),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    selected.summary?.takeIf(String::isNotBlank)?.let { fullText ->
                        Text(stringResource(R.string.monogram_post_text),
                            style = MaterialTheme.typography.titleMedium)
                        Text(fullText, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (videos.isNotEmpty()) {
                        Text(stringResource(R.string.telegram_youtube),
                            style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(videos, key = { it }) { id ->
                                Column(Modifier.width(220.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ArtworkImage(
                                        com.ambient.tv.youtube.youtubeThumbnailUrl(id),
                                        "YouTube", Modifier.fillMaxWidth().height(124.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    TvActionButton(stringResource(R.string.monogram_open_youtube),
                                        onClick = { onOpenYouTube(id) })
                                }
                            }
                        }
                    }
                }
            }
        }
        PageUtilityBar(onBack = onBack, onSettings = onOpenSettings,
            leftNavigatesBack = false, modifier = Modifier.align(Alignment.TopCenter))
    }
}

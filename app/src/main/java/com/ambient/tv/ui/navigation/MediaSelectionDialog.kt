package com.ambient.tv.ui.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.delay

@Composable
fun MediaSelectionDialog(
    selection: ResolvedMediaSelection,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
    onDetails: () -> Unit,
) {
    val original = selection.original
    val playable = selection.playable ?: return
    val firstFocus = remember(playable.ratingKey) { FocusRequester() }
    val hasProgress = (playable.progressFraction ?: 0f) in 0.01f..0.94f
    val resolvedChild = original.ratingKey != playable.ratingKey
    val artworkRatio = when (original.type) {
        "episode" -> 16f / 9f
        "album", "artist", "track", "audio" -> 1f
        else -> 2f / 3f
    }
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    // Portrait phones: a 210dp still beside the text would leave the text column almost no width.
    val narrow = com.ambient.tv.ui.mobile.isNarrowHandheldWindow()
    val artworkWidth = when {
        original.type == "episode" -> if (narrow) 112.dp else 210.dp
        else -> if (narrow) 76.dp else 138.dp
    }
    val episodeMeta = if (resolvedChild) {
        buildList {
            playable.parentIndex?.let { add("S${it.toString().padStart(2, '0')}") }
            playable.index?.let { add("E${it.toString().padStart(2, '0')}") }
            add(playable.title)
        }.joinToString(" · ")
    } else null

    LaunchedEffect(playable.ratingKey) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }

    AppDialog(
        onDismiss = onDismiss,
        title = original.title,
        hint = stringResource(R.string.media_selection_dialog_hint),
        captureInitialFocus = false,
        widthFraction = if (handheld) null else 0.62f,
        maxWidth = 800.dp,
        footer = {
            TvActionButton(
                text = when {
                    hasProgress -> stringResource(R.string.media_selection_continue)
                    resolvedChild -> stringResource(R.string.media_selection_play_episode)
                    else -> stringResource(R.string.detail_play)
                },
                onClick = onPlay,
                selected = true,
                modifier = Modifier.focusRequester(firstFocus),
            )
            if (hasProgress) {
                TvActionButton(text = stringResource(R.string.detail_restart), onClick = onRestart)
            }
            TvActionButton(text = stringResource(R.string.media_selection_details), onClick = onDetails)
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 26.dp), verticalAlignment = Alignment.Top) {
            ArtworkImage(
                model = original.thumbUrl ?: playable.thumbUrl ?: original.artUrl ?: playable.artUrl,
                title = original.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(artworkWidth)
                    .aspectRatio(artworkRatio)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            )
            episodeMeta?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

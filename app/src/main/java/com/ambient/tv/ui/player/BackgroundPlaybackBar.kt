package com.ambient.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.activateOnTvKey
import kotlinx.coroutines.delay

/**
 * Compact global controller for the service-owned background playback session.
 *
 * This deliberately is not a miniature copy of the fullscreen player. The sidebar only needs a
 * recognisable artwork tile, the current title/progress and the actions that are useful while the
 * user is browsing the app. Provider-specific artwork is supplied through [PlaybackMetadata].
 */
@Composable
fun BackgroundPlaybackBar(
    onOpen: (String) -> Unit,
    onPlayNext: (NextUpCandidate) -> Unit,
    onFocusExit: () -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onExpand: () -> Unit = {},
    onFocusOwnershipChanged: (Boolean) -> Unit = {},
) {
    val session by BackgroundPlaybackSession.snapshot.collectAsState()
    val metadata = session.metadata
    var barHasFocus by remember { mutableStateOf(false) }
    var compactHadFocus by remember { mutableStateOf(false) }
    var barHadFocus by remember { mutableStateOf(false) }
    var focusedAction by remember { mutableStateOf<String?>(null) }
    val fullscreenFocus = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { onFocusOwnershipChanged(false) } }

    LaunchedEffect(barHasFocus, metadata) {
        if (!barHasFocus && metadata != null) {
            delay(80)
            barHadFocus = false
            focusedAction = null
            if (compact) compactHadFocus = false
        }
    }
    LaunchedEffect(compact) {
        if (!compact && compactHadFocus) {
            withFrameNanos { }
            runCatching { fullscreenFocus.requestFocus() }
            compactHadFocus = false
        }
    }
    LaunchedEffect(metadata, barHadFocus) {
        if (metadata == null && barHadFocus) {
            delay(16)
            onFocusExit()
            barHadFocus = false
        }
    }
    LaunchedEffect(metadata?.nextUp, focusedAction, compact, barHadFocus) {
        if (!compact && barHadFocus && metadata != null && metadata.nextUp == null && focusedAction == "next") {
            delay(16)
            runCatching { fullscreenFocus.requestFocus() }
        }
    }
    metadata ?: return

    if (compact) {
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier = modifier
                .height(58.dp)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) compactHadFocus = true
                    onFocusOwnershipChanged(it.hasFocus)
                    barHasFocus = it.hasFocus
                    if (it.hasFocus) barHadFocus = true
                }
                .border(1.5.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                .activateOnTvKey(onClick = onExpand)
                .clickable(onClick = onExpand)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            PlaybackArtworkCard(
                artworkUrl = metadata.artworkUrl, fallbackUrls = metadata.artworkFallbackUrls,
                title = metadata.title, headers = metadata.artworkHeaders,
                modifier = Modifier.size(44.dp), contentScale = ContentScale.Crop,
            )
            Icon(
                if (session.playing) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = metadata.title,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).size(18.dp),
                tint = Color.White,
            )
        }
        return
    }

    val durationMs = playbackDurationMs(session)
    val progress = playbackProgress(session)
    val artworkShape = when {
        metadata.artworkAspectRatio >= 1.2f -> "landscape"
        metadata.artworkAspectRatio <= 0.8f -> "poster"
        else -> "square"
    }
    val secondaryLine = metadata.subtitle.takeIf(String::isNotBlank)
    val clockLine = if (metadata.isLive) {
        "LIVE"
    } else {
        "${miniFormatDuration(session.status.positionMs)} / ${miniFormatDuration(durationMs)}"
    }

    var artworkFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusOwnershipChanged(it.hasFocus); barHasFocus = it.hasFocus; if (it.hasFocus) barHadFocus = true }
            // Left/Right close the menu only from the outer edges (artwork row, first/last button);
            // between the buttons they move focus as usual.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> if (focusedAction == "artwork" || focusedAction == "play") { onClose(); true } else false
                    Key.DirectionRight -> if (focusedAction == "artwork" || focusedAction == "fullscreen") { onClose(); true } else false
                    else -> false
                }
            }
            .focusGroup()
            .background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { artworkFocused = it.isFocused; if (it.isFocused) focusedAction = "artwork" }
                .border(1.5.dp, if (artworkFocused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                .activateOnTvKey(onClick = { onOpen(metadata.route) })
                .clickable { onOpen(metadata.route) }
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!metadata.artworkUrl.isNullOrBlank() || metadata.artworkFallbackUrls.isNotEmpty()) {
                PlaybackArtworkCard(
                    artworkUrl = metadata.artworkUrl,
                    fallbackUrls = metadata.artworkFallbackUrls,
                    title = metadata.title,
                    modifier = when (artworkShape) {
                        "landscape" -> Modifier.width(72.dp).height(42.dp)
                        "poster" -> Modifier.width(40.dp).height(60.dp)
                        else -> Modifier.size(46.dp)
                    },
                    headers = metadata.artworkHeaders,
                    contentScale = ContentScale.Crop,
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = metadata.title,
                    color = Color.White.copy(alpha = 0.96f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
                )
                if (secondaryLine != null) {
                    Text(
                        text = secondaryLine,
                        color = Color.White.copy(alpha = 0.60f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
                    )
                }
                Row(Modifier.height(22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.ambient.tv.ui.common.MediaSystem.fromKey(metadata.source)?.let {
                        com.ambient.tv.ui.common.SystemLogo(it, size = 16.dp)
                    }
                    Text(
                        text = clockLine,
                        color = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.text,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        progress?.let {
            com.ambient.tv.ui.common.PlaybackStatusBar(
                progress = it,
                modifier = Modifier.fillMaxWidth(),
                trackColor = Color.White.copy(alpha = 0.11f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlaybackAction(
                icon = if (session.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = stringResource(if (session.playing) R.string.player_pause else R.string.player_play),
                onClick = BackgroundPlaybackSession::userToggle,
                primary = true,
                onFocusedChanged = { if (it) focusedAction = "play" },
            )
            Spacer(Modifier.width(3.dp))
            MiniPlaybackAction(
                icon = Icons.Filled.Stop,
                label = stringResource(R.string.player_stop),
                onClick = {
                    onFocusExit()
                    BackgroundPlaybackSession.stop()
                },
                onFocusedChanged = { if (it) focusedAction = "stop" },
            )
            metadata.nextUp?.let { next ->
                Spacer(Modifier.width(3.dp))
                MiniPlaybackAction(
                    icon = Icons.Filled.SkipNext,
                    label = stringResource(R.string.player_next),
                    onClick = { onPlayNext(next) },
                    onFocusedChanged = { if (it) focusedAction = "next" },
                )
            }
            Spacer(Modifier.width(3.dp))
            MiniPlaybackAction(
                icon = Icons.Filled.Fullscreen,
                label = stringResource(R.string.player_fullscreen),
                onClick = { onOpen(metadata.route) },
                modifier = Modifier.focusRequester(fullscreenFocus),
                onFocusedChanged = { if (it) focusedAction = "fullscreen" },
            )
        }
    }
}

private fun playbackDurationMs(session: BackgroundPlaybackSession.Snapshot): Long {
    val metadata = session.metadata ?: return 0L
    return session.status.durationMs.takeIf { it > 0L }
        ?: (metadata.durationMs - metadata.timelineOffsetMs).coerceAtLeast(0L)
}

/** 0…1 through the item (or the live programme), null when unknown. */
private fun playbackProgress(session: BackgroundPlaybackSession.Snapshot): Float? {
    val metadata = session.metadata ?: return null
    val durationMs = playbackDurationMs(session)
    return when {
        metadata.isLive && metadata.liveProgramStartMs != null && metadata.liveProgramEndMs != null -> {
            val span = metadata.liveProgramEndMs - metadata.liveProgramStartMs
            if (span > 0L) ((System.currentTimeMillis() - metadata.liveProgramStartMs).toFloat() / span.toFloat()).coerceIn(0f, 1f) else null
        }
        !metadata.isLive && durationMs > 0L ->
            (session.status.positionMs.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> null
    }
}

/**
 * Non-focusable reminder at the collapsed menu's bottom edge that something plays in the
 * background. Focus reaches the real [BackgroundPlaybackBar] through the opened menu.
 */
@Composable
fun BackgroundPlaybackIndicator(modifier: Modifier = Modifier) {
    val session by BackgroundPlaybackSession.snapshot.collectAsState()
    val metadata = session.metadata ?: return
    val progress = playbackProgress(session)
    val track = Color.White.copy(alpha = 0.18f)
    val accent = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator
    Box(
        modifier
            .size(56.dp)
            .background(Color.Black, androidx.compose.foundation.shape.CircleShape)
            .drawBehind {
                // Progress runs clockwise around the tile from the top.
                val stroke = 3.dp.toPx()
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(track, -90f, 360f, false, topLeft, arcSize, style = style)
                if (progress != null) drawArc(accent, -90f, 360f * progress, false, topLeft, arcSize, style = style)
            }
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        PlaybackArtworkCard(
            artworkUrl = metadata.artworkUrl, fallbackUrls = metadata.artworkFallbackUrls,
            title = metadata.title, headers = metadata.artworkHeaders,
            modifier = Modifier.size(46.dp), contentScale = ContentScale.Crop,
            shape = androidx.compose.foundation.shape.CircleShape,
        )
        Icon(
            if (session.playing) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = metadata.title,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape).size(16.dp),
            tint = Color.White,
        )
    }
}

@Composable
private fun MiniPlaybackAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onFocusedChanged: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val foreground = if (focused) Color.Black else if (primary) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.76f)

    Box(
        modifier = modifier
            .size(30.dp)
            .onFocusChanged {
                focused = it.isFocused
                onFocusedChanged(it.isFocused)
            }
            .background(background, shape)
            .border(
                if (focused) 1.5.dp else 1.dp,
                if (focused) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = if (primary) 0.12f else 0.06f),
                shape,
            )
            .activateOnTvKey(onClick = onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(18.dp))
    }
}

internal fun miniFormatDuration(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

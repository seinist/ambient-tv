package com.ambient.tv.ui.player

import coil3.request.allowHardware
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.ambient.tv.R
import com.ambient.tv.domain.model.*
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class PlaybackNavigationState {
    var timeline by mutableStateOf(PlaybackTimeline())
    var chromeVisible by mutableStateOf(false)
    var skipOverlayBlocked by mutableStateOf(false)
    var focusReturnVersion by mutableIntStateOf(0)
    fun returnToControls() { focusReturnVersion++; revealControls() }
    var revealControls: () -> Unit = {}
    var chaptersOpen by mutableStateOf(false)
    var interactionVersion by mutableIntStateOf(0)
    val returnFocus = FocusRequester()
    fun interact() { interactionVersion++; BackgroundPlaybackSession.registerUserInteraction() }
}
val LocalPlaybackNavigation = staticCompositionLocalOf<PlaybackNavigationState?> { null }

@Composable
fun PlaybackNavigationProvider(
    metadata: PlaybackMetadata,
    key: String,
    supplied: PlaybackTimeline,
    loader: (suspend () -> PlaybackTimeline)?,
    onSeek: ((Long) -> Unit)?,
    nextUpVisible: Boolean = false,
    onPlayNext: ((Boolean) -> Unit)? = null,
    canAutoAdvance: Boolean = true,
    navigationState: PlaybackNavigationState? = null,
    creditsHandledByNextUp: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state = navigationState ?: remember(key) { PlaybackNavigationState() }
    val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    val enabled = metadata.hasVideo && !metadata.isLive
    val currentLoader by rememberUpdatedState(loader)
    val handheld = LocalHandheldUi.current
    var wasOpen by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, supplied) { state.timeline = supplied.normalized(metadata.durationMs) }
    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        // Metadata enrichment never delays acquisition of the video decoder.
        delay(800)
        val loaded = withTimeoutOrNull(10_000) { com.ambient.tv.util.suspendResult { currentLoader?.invoke() }.getOrNull() }
        if (loaded != null) state.timeline = loaded.copy(chapters = loaded.chapters.ifEmpty { state.timeline.chapters }).normalized(metadata.durationMs)
    }
    LaunchedEffect(key, snapshot.generation, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(20) {
            delay(750)
            if (state.timeline.chapters.isNotEmpty()) return@LaunchedEffect
            if (BackgroundPlaybackSession.snapshot.value.metadata?.route != metadata.route) return@LaunchedEffect
            val chapters = BackgroundPlaybackSession.activeHandle()?.chapters().orEmpty()
            if (chapters.isNotEmpty()) {
                state.timeline = state.timeline.copy(chapters = chapters).normalized(metadata.durationMs)
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(state.chaptersOpen) {
        if (state.chaptersOpen) {
            wasOpen = true
            while (true) { state.interact(); delay(1_000) }
        } else if (wasOpen) {
            state.interact()
            if (!handheld) { delay(100); runCatching { state.returnFocus.requestFocus() } }
        }
    }
    CompositionLocalProvider(LocalPlaybackNavigation provides state.takeIf { enabled }) {
        SegmentSkipOverlay(metadata, key, state, onSeek, nextUpVisible, onPlayNext, canAutoAdvance, creditsHandledByNextUp, content)
        if (state.chaptersOpen && enabled) {
            val position = snapshot.status.positionMs + metadata.timelineOffsetMs
            ChapterDialog(state.timeline, position,
                onDismiss = { state.chaptersOpen = false },
                onChoose = { target ->
                    state.interact()
                    if (onSeek != null) onSeek(target)
                    else BackgroundPlaybackSession.seekTo((target - metadata.timelineOffsetMs).coerceAtLeast(0))
                    state.chaptersOpen = false
                })
        }
    }
}

@Composable
fun ChapterButton() {
    val navigation = LocalPlaybackNavigation.current ?: return
    if (navigation.timeline.chapters.isEmpty()) return
    PlayerIconButton(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.player_chapters),
        onClick = { navigation.interact(); navigation.chaptersOpen = true },
        modifier = Modifier.focusRequester(navigation.returnFocus), osd = OsdButton.CHAPTERS)
}

@Composable
private fun ChapterDialog(timeline: PlaybackTimeline, positionMs: Long, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    val chapters = timeline.chapters
    val selected = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val initialFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val handheld = LocalHandheldUi.current
    val initialIndex = remember { selected }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.player_chapters),
        captureInitialFocus = false,
        scrollable = false,
        widthFraction = if (handheld) 0.96f else 0.88f,
        maxWidth = 1100.dp,
        footer = {
            TvActionButton(stringResource(R.string.back), onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus))
        },
    ) {
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(6.dp)) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(12.dp)
                val title = chapter.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.player_chapter_number, index + 1)
                Column(Modifier.width(if (handheld) 176.dp else 224.dp)
                    .then(if (index == initialIndex) Modifier.focusRequester(initialFocus) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.12f else 0.04f))
                    .border(if (focused) 2.dp else 1.dp, if (focused || index == selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                    .activateOnTvKey { onChoose(chapter.startMs) }.clickable(role = Role.Button) { onChoose(chapter.startMs) }
                    .padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val frame = timeline.chapterFrame(chapter)
                    if (frame != null) TimelineImage(frame, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                    Text(title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    Text(timelineTime(chapter.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LaunchedEffect(Unit) {
            if (!handheld) {
                delay(120)
                if (runCatching { initialFocus.requestFocus() }.isFailure) runCatching { closeFocus.requestFocus() }
            }
        }
    }
}

/** No video decoder or frame extraction: bounded Coil cache, cancelable requests and sprite cropping at draw time. */
@Composable
private fun TimelineImage(frame: PreviewFrame, modifier: Modifier) {
    val context = LocalContext.current
    // A whole sprite is cached once; moving within it does not fetch/decode another bitmap.
    val bitmap by produceState<android.graphics.Bitmap?>(null, frame.url) {
        value = null
        delay(90)
        val request = ImageRequest.Builder(context).data(frame.url).allowHardware(false)
            .size(if (frame.sheetWidth > 0) frame.sheetWidth else 320, if (frame.sheetHeight > 0) frame.sheetHeight else 180).build()
        val result = context.imageLoader.execute(request)
        value = ((result as? SuccessResult)?.image as? coil3.BitmapImage)?.bitmap
    }
    Box(modifier.background(Color.Black.copy(alpha = 0.3f))) {
        Canvas(Modifier.fillMaxSize()) {
            val image = bitmap ?: return@Canvas
            val cropped = frame.width > 0 && frame.height > 0
            // Scale crop coordinates if the image loader/server returned a smaller sheet.
            val sx = if (cropped) image.width.toFloat() / frame.sheetWidth.coerceAtLeast(1) else 1f
            val sy = if (cropped) image.height.toFloat() / frame.sheetHeight.coerceAtLeast(1) else 1f
            val x = if (cropped) (frame.x * sx).toInt().coerceIn(0, image.width - 1) else 0
            val y = if (cropped) (frame.y * sy).toInt().coerceIn(0, image.height - 1) else 0
            val w = if (cropped) (frame.width * sx).toInt().coerceIn(1, image.width - x) else image.width
            val h = if (cropped) (frame.height * sy).toInt().coerceIn(1, image.height - y) else image.height
            drawImage(image.asImageBitmap(), srcOffset = IntOffset(x, y), srcSize = IntSize(w, h), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}

@Composable
fun SeekPreview(positionMs: Long?, durationMs: Long, modifier: Modifier = Modifier) {
    val navigation = LocalPlaybackNavigation.current ?: return
    val timeline = navigation.timeline
    var lastPosition by remember(navigation) { mutableLongStateOf(0L) }
    SideEffect { if (positionMs != null) lastPosition = positionMs }
    val shownPosition = positionMs ?: lastPosition
    val chapter = timeline.chapterAt(shownPosition)
    val frame = timeline.previewFrameAt(shownPosition)
    val handheld = LocalHandheldUi.current
    val previewWidth = if (handheld) 152.dp else 208.dp
    val progress = if (durationMs > 0L) shownPosition.toFloat() / durationMs.toFloat() else 0f
    val density = LocalDensity.current
    val chapterTitle = chapter?.let {
        it.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.player_chapter_number, timeline.chapters.indexOf(it) + 1)
    }
    AnimatedVisibility(
        visible = positionMs != null && (frame != null || chapter != null),
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
            val trackPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 0f
            val previewPx = with(density) { previewWidth.toPx() }
            val x = seekPreviewOffset(trackPx, previewPx, progress)
            Box(
                Modifier.offset { IntOffset(x.roundToInt(), 0) }.width(previewWidth)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.86f)),
            ) {
                if (frame != null) {
                    TimelineImage(frame, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .then(if (frame != null) Modifier.background(Color.Black.copy(alpha = 0.62f)) else Modifier)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(timelineTime(shownPosition), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    chapterTitle?.let {
                        Text(it, color = Color.White.copy(alpha = 0.75f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Center on the playhead as soon as that fits; pin to the track ends instead of overflowing them. */
internal fun seekPreviewOffset(trackWidth: Float, previewWidth: Float, progress: Float): Float {
    if (!trackWidth.isFinite() || !previewWidth.isFinite() || trackWidth <= 0f || previewWidth <= 0f) return 0f
    val t = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val maxOffset = (trackWidth - previewWidth).coerceAtLeast(0f)
    return (trackWidth * t - previewWidth / 2f).coerceIn(0f, maxOffset)
}

internal fun timelineTime(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)

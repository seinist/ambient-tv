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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    /** Seeks to an absolute timeline position; installed by [PlaybackNavigationProvider]. */
    var onChapterSeek: (Long) -> Unit = {}
    var interactionVersion by mutableIntStateOf(0)
    val returnFocus = FocusRequester()
    fun interact() { interactionVersion++; BackgroundPlaybackSession.registerUserInteraction() }
    /** Chapter the last chapter key jumped to; shown briefly even while the OSD is hidden. */
    var chapterNotice by mutableStateOf<VideoChapter?>(null)
    fun chapterStep(positionMs: Long, direction: Int): Long? = timeline.chapterStepDelta(positionMs, direction)?.also { delta ->
        if (delta != 0L) chapterNotice = timeline.chapterAt(positionMs + delta)
    }
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
            // Television's chapter strip places focus itself when it closes.
            state.interact()
        }
    }
    state.onChapterSeek = { target ->
        if (onSeek != null) onSeek(target)
        else BackgroundPlaybackSession.seekTo((target - metadata.timelineOffsetMs).coerceAtLeast(0))
    }
    CompositionLocalProvider(LocalPlaybackNavigation provides state.takeIf { enabled }) {
        SegmentSkipOverlay(metadata, key, state, onSeek, nextUpVisible, onPlayNext, canAutoAdvance, creditsHandledByNextUp, content)
        state.chapterNotice?.takeIf { enabled }?.let { ChapterNotice(state.timeline, it) { state.chapterNotice = null } }
        if (state.chaptersOpen && enabled && handheld) {
            val position = snapshot.status.positionMs + metadata.timelineOffsetMs
            ChapterDialog(state.timeline, position,
                onDismiss = { state.chaptersOpen = false },
                onChoose = { target ->
                    state.interact()
                    state.onChapterSeek(target)
                    state.chaptersOpen = false
                })
        }
    }
}

@Composable
private fun ChapterNotice(timeline: PlaybackTimeline, chapter: VideoChapter, onDone: () -> Unit) {
    val index = timeline.chapters.indexOf(chapter)
    LaunchedEffect(chapter) { delay(2_200); onDone() }
    if (index < 0) return
    val position = stringResource(R.string.player_chapter_position, index + 1, timeline.chapters.size)
    androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, with(LocalDensity.current) { 32.dp.roundToPx() }),
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
        Text(chapter.meaningfulTitle?.let { "$position · $it" } ?: position, color = Color.White, maxLines = 1,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

/**
 * Current chapter above the timeline; pointer taps open the chapter list. The line is reserved whenever any
 * chapter has a title, so the timeline does not jump between titled and untitled chapters. Never a D-pad
 * focus target: the seek bar owns OK and vertical focus.
 */
@Composable
internal fun CurrentChapterLabel(positionMs: Long, modifier: Modifier = Modifier) {
    val navigation = LocalPlaybackNavigation.current ?: return
    if (navigation.timeline.chapters.none { it.meaningfulTitle != null }) return
    val osd = LocalPlayerChromeState.current?.osd
    val handheld = LocalHandheldUi.current
    val title = navigation.timeline.chapterAt(positionMs)?.meaningfulTitle
    Text(title?.let { "● $it" }.orEmpty(), color = Color.White.copy(alpha = 0.9f), maxLines = 1, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.clip(RoundedCornerShape(6.dp))
            .focusProperties { canFocus = false }
            .then(if (title != null) Modifier.clickable(role = Role.Button) {
                navigation.interact()
                // The chapter strip lives below the action bar and must be visible before focus enters it.
                if (!handheld) osd?.expand()
                navigation.chaptersOpen = true
            } else Modifier)
            .padding(horizontal = 2.dp, vertical = 1.dp))
}

@Composable
fun ChapterButton() {
    val navigation = LocalPlaybackNavigation.current ?: return
    if (navigation.timeline.chapters.isEmpty()) return
    PlayerIconButton(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.player_chapters),
        onClick = { navigation.interact(); navigation.chaptersOpen = true },
        modifier = Modifier.focusRequester(navigation.returnFocus), osd = OsdButton.CHAPTERS)
}

/**
 * Television: the chapter row below the OSD action bar, opened by Down on the bar or the chapter
 * button. Up/Back fold it back onto the chapter button; OK seeks and hands focus to the seek bar.
 */
@Composable
internal fun ChapterStrip() {
    val navigation = LocalPlaybackNavigation.current ?: return
    val osd = LocalPlayerChromeState.current?.osd
    val acceptsInput = osd?.let { it.expanded && it.show } ?: true
    val open = navigation.chaptersOpen && navigation.timeline.chapters.isNotEmpty()
    LaunchedEffect(acceptsInput) {
        if (!acceptsInput) navigation.chaptersOpen = false
    }
    // Retained through the fold-up so the row does not empty mid-animation.
    var shownChapters by remember { mutableStateOf(navigation.timeline.chapters) }
    if (open) shownChapters = navigation.timeline.chapters
    DisposableEffect(Unit) { onDispose { navigation.chaptersOpen = false } }
    AnimatedVisibility(
        visible = open,
        enter = androidx.compose.animation.expandVertically(tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeIn(tween(220)),
        exit = androidx.compose.animation.shrinkVertically(tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeOut(tween(200)),
    ) {
        val timeline = navigation.timeline
        val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
        val offset = LocalPlaybackMetadata.current?.timelineOffsetMs ?: 0L
        val positionMs = remember { snapshot.status.positionMs + offset }
        val selected = shownChapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 1).coerceAtLeast(0))
        val initialFocus = remember { FocusRequester() }
        val controlFocus = LocalPlayerControlFocus.current
        fun fold() {
            runCatching { navigation.returnFocus.requestFocus() }
            navigation.chaptersOpen = false
            navigation.interact()
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                when (event.key) {
                    // Both halves of Back are taken so the back dispatcher does not also close the OSD.
                    androidx.compose.ui.input.key.Key.Back, androidx.compose.ui.input.key.Key.Escape -> {
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) fold(); true
                    }
                    androidx.compose.ui.input.key.Key.DirectionUp -> {
                        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) fold(); true
                    }
                    androidx.compose.ui.input.key.Key.DirectionDown -> true
                    else -> { navigation.interact(); false }
                }
            },
        ) {
            itemsIndexed(shownChapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
                var focused by remember { mutableStateOf(false) }
                val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "chapterScale")
                val shape = RoundedCornerShape(10.dp)
                val title = chapter.meaningfulTitle ?: stringResource(R.string.player_chapter_number, index + 1)
                val frame = timeline.chapterFrame(chapter)
                val choose = {
                    if (acceptsInput) {
                        navigation.interact()
                        navigation.onChapterSeek(chapter.startMs)
                        runCatching { controlFocus?.seekTarget()?.requestFocus() }
                        navigation.chaptersOpen = false
                    }
                }
                Column(
                    Modifier.width(200.dp)
                        .then(if (index == selected) Modifier.focusRequester(initialFocus) else Modifier)
                        .focusProperties { canFocus = acceptsInput }
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .onFocusChanged { focused = it.isFocused }
                        .clip(shape).background(Color.Black.copy(alpha = if (focused) 0.72f else 0.5f))
                        .border(if (focused) 2.dp else 1.dp, when {
                            focused -> Color.White
                            index == selected -> com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator
                            else -> Color.White.copy(alpha = 0.12f)
                        }, shape)
                        .activateOnTvKey { choose() }.clickable(enabled = acceptsInput, role = Role.Button) { choose() },
                ) {
                    if (frame != null) TimelineImage(frame, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(timelineTime(chapter.startMs), color = Color.White.copy(alpha = if (focused) 0.85f else 0.6f),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(60)
            if (navigation.chaptersOpen && (osd == null || osd.show && osd.expanded)) {
                runCatching { initialFocus.requestFocus() }
            }
        }
    }
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
                val title = chapter.meaningfulTitle ?: stringResource(R.string.player_chapter_number, index + 1)
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
        it.meaningfulTitle ?: stringResource(R.string.player_chapter_number, timeline.chapters.indexOf(it) + 1)
    }
    AnimatedVisibility(
        visible = positionMs != null && (frame != null || chapter != null),
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(220)),
        // Zero layout height, drawn upward: the card overlays the OSD above instead of pushing
        // title and poster up, and it fades in place.
        modifier = modifier.fillMaxWidth().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
        },
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

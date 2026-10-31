package com.ambient.tv.ui.photos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Scale
import com.ambient.tv.R
import com.ambient.tv.data.photos.PhotoEntry
import com.ambient.tv.data.photos.PhotoExif
import com.ambient.tv.data.photos.PhotoKind
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * Full-screen photo viewer and slideshow (all systems).
 *
 * Remote: ◀/▶ previous/next · OK info (plays videos) · ▲ or Menu options · ▼ info ·
 * Play/Pause slideshow · FF/RW or channel keys next/previous · Back closes zoom, menu, viewer.
 * While zoomed, the D-pad pans and OK/Back return to the full picture.
 */
@Composable
fun PhotoViewerScreen(
    onClose: () -> Unit,
    onPlayVideo: (String) -> Unit,
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    if (!viewModel.valid) {
        // The hand-off list does not survive process death; return to the browser instead.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val handheld = LocalHandheldUi.current
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(0L) }

    // Decode at display resolution; 4K is the ceiling even on larger panels.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceIn(640, 3840)
    val heightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }.coerceIn(360, 2160)

    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = !menuOpen) {
        when {
            state.zoom > 1f -> viewModel.resetZoom()
            controlsVisible -> controlsVisible = false
            else -> onClose()
        }
    }
    LaunchedEffect(menuOpen) {
        if (!menuOpen) repeat(4) { withFrameNanos { }; if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    LaunchedEffect(lastInput, state.slideshow) {
        controlsVisible = true
        delay(if (state.slideshow) 2500 else 4000)
        controlsVisible = false
    }
    // Preload the neighbours so moving on never shows a black frame while decoding.
    LaunchedEffect(state.position, state.order) {
        listOf(1, -1).mapNotNull { state.neighbour(it) }.forEach { entry ->
            val model = entry.imageModel(widthPx, heightPx) ?: entry.thumbModel() ?: return@forEach
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(model).size(widthPx, heightPx).scale(Scale.FIT).build())
        }
    }

    fun onKey(key: Key, repeat: Boolean): Boolean {
        lastInput = System.nanoTime()
        // Holding ◀/▶ browses quickly; holding a toggle key must not flip it on and off.
        if (repeat && key !in REPEATABLE_KEYS) return key in HANDLED_KEYS
        val zoomed = state.zoom > 1f
        when (key) {
            Key.DirectionLeft -> if (zoomed) viewModel.pan(-1, 0) else viewModel.previous()
            Key.DirectionRight -> if (zoomed) viewModel.pan(1, 0) else viewModel.next()
            Key.DirectionUp -> if (zoomed) viewModel.pan(0, -1) else menuOpen = true
            Key.DirectionDown -> if (zoomed) viewModel.pan(0, 1) else viewModel.toggleInfo()
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                val current = state.current
                when {
                    zoomed -> viewModel.resetZoom()
                    current?.kind == PhotoKind.VIDEO -> current.playKey?.let { viewModel.stopSlideshow(); onPlayVideo(it) }
                    else -> viewModel.toggleInfo()
                }
            }
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar -> viewModel.toggleSlideshow()
            Key.MediaFastForward, Key.MediaNext, Key.ChannelUp, Key.PageDown -> viewModel.next()
            Key.MediaRewind, Key.MediaPrevious, Key.ChannelDown, Key.PageUp -> viewModel.previous()
            Key.Menu -> menuOpen = true
            else -> return false
        }
        return true
    }

    val current = state.current
    val touchGestures = if (handheld && !menuOpen) {
        Modifier
            .pointerInput(current?.id, state.zoom > 1f) {
                detectTapGestures(
                    onTap = {
                        if (controlsVisible) controlsVisible = false
                        else lastInput = System.nanoTime()
                    },
                    onDoubleTap = {
                        lastInput = System.nanoTime()
                        if (state.zoom > 1f) viewModel.resetZoom() else viewModel.zoomIn()
                    },
                    onLongPress = {
                        lastInput = System.nanoTime()
                        menuOpen = true
                    },
                )
            }
            .then(if (state.zoom > 1f) {
                Modifier.pointerInput(current?.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val width = size.width.coerceAtLeast(1).toFloat()
                        val height = size.height.coerceAtLeast(1).toFloat()
                        viewModel.transform(zoom, -2f * pan.x / width, -2f * pan.y / height)
                        lastInput = System.nanoTime()
                    }
                }
            } else {
                Modifier.pointerInput(current?.id) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                        onDragEnd = {
                            if (abs(drag) >= size.width * 0.12f) {
                                if (drag < 0f) viewModel.next() else viewModel.previous()
                                lastInput = System.nanoTime()
                            }
                        },
                    )
                }
            })
    } else Modifier

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .then(touchGestures)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (menuOpen || event.key == Key.Back) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> onKey(event.key, event.nativeKeyEvent.repeatCount > 0)
                    // Swallow the matching key-up so it cannot activate something behind the viewer.
                    KeyEventType.KeyUp -> event.key in HANDLED_KEYS
                    else -> false
                }
            }
            .focusable(),
    ) {
        AnimatedContent(
            targetState = state.current,
            contentKey = { it?.id },
            transitionSpec = {
                when (state.transitionKey) {
                    "none" -> EnterTransition.None togetherWith ExitTransition.None
                    "slide" -> {
                        val dir = state.direction
                        slideInHorizontally(tween(450)) { it * dir } togetherWith slideOutHorizontally(tween(450)) { -it * dir }
                    }
                    else -> fadeIn(tween(650)) togetherWith fadeOut(tween(650))
                }
            },
            label = "photo",
        ) { entry ->
            if (entry != null) PhotoStage(
                entry = entry,
                widthPx = widthPx,
                heightPx = heightPx,
                quarterTurns = if (entry.id == state.current?.id) state.quarterTurns else 0,
                zoom = if (entry.id == state.current?.id) state.zoom else 1f,
                panX = state.panX,
                panY = state.panY,
                kenBurns = state.kenBurns && state.slideshow && entry.kind == PhotoKind.PHOTO,
                kenBurnsMs = state.intervalSeconds * 1000 + 700,
            )
        }

        // Title, hint and position appear on input and fade out, so the photo stays unobstructed.
        AnimatedVisibility(controlsVisible && state.zoom == 1f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (handheld) R.string.photos_viewer_hint_touch else R.string.photos_viewer_hint), color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.bodySmall)
                }
                if (state.slideshow) Icon(Icons.Filled.Slideshow, null, tint = MaterialTheme.colorScheme.primary)
                if (state.loadingSubfolders) Text(stringResource(R.string.photos_loading), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                if (state.count > 0) Text(stringResource(R.string.photos_position, state.position + 1, state.count), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (state.count == 0) {
            Text(
                stringResource(if (state.loadingSubfolders) R.string.photos_loading else R.string.photos_empty),
                color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center),
            )
        }
        if (current?.kind == PhotoKind.VIDEO) {
            val playVideo = {
                current.playKey?.let { viewModel.stopSlideshow(); onPlayVideo(it) }
                Unit
            }
            Column(
                Modifier.align(Alignment.Center)
                    .then(if (handheld) Modifier.clickable(role = Role.Button, onClick = playVideo).padding(20.dp) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.PlayCircle, null, Modifier.size(96.dp), tint = Color.White)
                Text(stringResource(if (handheld) R.string.photos_play_video_hint_touch else R.string.photos_play_video_hint), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        AnimatedVisibility(state.showInfo && current != null && state.zoom == 1f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
            if (current != null) Box(Modifier.padding(bottom = if (handheld) 76.dp else 0.dp)) { PhotoInfoPanel(current, state.exif ?: current.exif) }
        }

        if (state.zoom > 1f) {
            Text(
                stringResource(R.string.photos_zoom_hint, state.zoom.toInt()),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (handheld) 84.dp else 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (handheld) {
            AnimatedVisibility(
                visible = controlsVisible && !menuOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier.navigationBarsPadding().padding(bottom = 10.dp)
                        .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(30.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhotoTouchButton(Icons.Filled.SkipPrevious, stringResource(R.string.photos_previous)) {
                        viewModel.previous(); lastInput = System.nanoTime()
                    }
                    PhotoTouchButton(Icons.Filled.Slideshow, stringResource(if (state.slideshow) R.string.photos_slideshow_stop else R.string.photos_slideshow_start), selected = state.slideshow) {
                        viewModel.toggleSlideshow(); lastInput = System.nanoTime()
                    }
                    PhotoTouchButton(Icons.Filled.Info, stringResource(R.string.photos_info), selected = state.showInfo) {
                        viewModel.toggleInfo(); lastInput = System.nanoTime()
                    }
                    PhotoTouchButton(Icons.Filled.MoreVert, stringResource(R.string.photos_menu_title)) {
                        menuOpen = true; lastInput = System.nanoTime()
                    }
                    PhotoTouchButton(Icons.Filled.SkipNext, stringResource(R.string.photos_next)) {
                        viewModel.next(); lastInput = System.nanoTime()
                    }
                }
            }
        }

        if (menuOpen) {
            com.ambient.tv.ui.common.RightPanelOverlay(onOutsideTap = { menuOpen = false }) {
            RightSidePanel(
                title = stringResource(R.string.photos_menu_title),
                onBack = { menuOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
                closeInsteadOfBack = true,
                onOutsideTap = { menuOpen = false },
            ) {
                TvActionButton(
                    stringResource(if (state.slideshow) R.string.photos_slideshow_stop else R.string.photos_slideshow_start),
                    { viewModel.toggleSlideshow(); menuOpen = false },
                    icon = Icons.Filled.Slideshow, fillWidth = true,
                )
                if (current?.kind == PhotoKind.VIDEO) {
                    TvActionButton(stringResource(R.string.photos_play_video), {
                        current.playKey?.let { viewModel.stopSlideshow(); onPlayVideo(it) }
                    }, icon = Icons.Filled.PlayCircle, fillWidth = true)
                }
                TvSettingRow(stringResource(R.string.photos_shuffle), null, state.shuffle, { viewModel.toggleShuffle() })
                TvSettingRow(stringResource(R.string.photos_info), null, state.showInfo, { viewModel.toggleInfo() })
                viewModel.albumKey?.let { albumKey ->
                    TvSettingRow(
                        stringResource(R.string.photos_subfolders), stringResource(R.string.photos_subfolders_hint),
                        viewModel.includeSubfolders, { enabled -> viewModel.setIncludeSubfolders(enabled, albumKey) },
                    )
                }
                if (current?.kind == PhotoKind.PHOTO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(stringResource(R.string.photos_rotate_left), { viewModel.rotate(-1) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.RotateLeft, fillWidth = true)
                        TvActionButton(stringResource(R.string.photos_rotate_right), { viewModel.rotate(1) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.RotateRight, fillWidth = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(stringResource(R.string.photos_zoom_in), { viewModel.zoomIn(); menuOpen = false }, Modifier.weight(1f), icon = Icons.Filled.ZoomIn, fillWidth = true)
                        TvActionButton(stringResource(R.string.photos_zoom_out), { viewModel.zoomOut() }, Modifier.weight(1f), enabled = state.zoom > 1f, icon = Icons.Filled.ZoomOut, fillWidth = true)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun PhotoTouchButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f)
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
    Box(
        Modifier.size(52.dp).background(background, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(25.dp), tint = foreground)
    }
}

@Composable
private fun PhotoStage(
    entry: PhotoEntry,
    widthPx: Int,
    heightPx: Int,
    quarterTurns: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    kenBurns: Boolean,
    kenBurnsMs: Int,
) {
    val context = LocalContext.current
    val model = if (entry.kind == PhotoKind.PHOTO) entry.imageModel(widthPx, heightPx) else entry.thumbModel()
    val request = remember(model, widthPx, heightPx) {
        // FIT keeps portrait photos at screen height instead of filling the width (4x the pixels).
        ImageRequest.Builder(context).data(model).size(widthPx, heightPx).scale(Scale.FIT).build()
    }
    // Ken Burns: slow zoom towards a per-photo corner over the slideshow interval.
    val progress = remember(entry.id) { Animatable(0f) }
    LaunchedEffect(entry.id, kenBurns) {
        if (kenBurns) progress.animateTo(1f, tween(kenBurnsMs, easing = LinearEasing)) else progress.snapTo(0f)
    }
    val seed = entry.id.hashCode()
    val driftX = if (seed and 1 == 0) 1f else -1f
    val driftY = if (seed and 2 == 0) 1f else -1f
    AsyncImage(
        model = request,
        contentDescription = entry.title,
        contentScale = ContentScale.Fit,
        colorFilter = com.ambient.tv.ui.display.uiHdrBitmapColorFilter(
            com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f,
        ),
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val w = size.width
            val h = size.height
            // A quarter turn swaps the axes; shrink so the rotated picture still fits the screen.
            val rotationFit = if (quarterTurns % 2 != 0 && w > 0 && h > 0) min(w, h) / max(w, h) else 1f
            val kb = 1f + 0.12f * progress.value
            val scale = zoom * rotationFit * kb
            scaleX = scale
            scaleY = scale
            rotationZ = quarterTurns * 90f
            translationX = -panX * (zoom - 1f) * w / 2f + driftX * 0.03f * w * progress.value
            translationY = -panY * (zoom - 1f) * h / 2f + driftY * 0.03f * h * progress.value
        },
    )
}

@Composable
private fun PhotoInfoPanel(entry: PhotoEntry, exif: PhotoExif?) {
    val rows = buildList<Pair<Int, String>> {
        formatPhotoDate(exif?.takenAt ?: entry.takenAt)?.let { add(R.string.photos_info_taken to it) }
        exif?.camera?.let { add(R.string.photos_info_camera to it) }
        exif?.lens?.let { add(R.string.photos_info_lens to it) }
        listOfNotNull(exif?.aperture, exif?.exposure, exif?.iso, exif?.focalLength).joinToString(" · ").takeIf { it.isNotBlank() }
            ?.let { add(R.string.photos_info_exposure to it) }
        val w = exif?.width ?: entry.width
        val h = exif?.height ?: entry.height
        if (w != null && h != null && w > 0 && h > 0) add(R.string.photos_info_resolution to "$w × $h")
        exif?.location?.let { add(R.string.photos_info_location to it) }
        exif?.fileName?.takeIf { it != entry.title }?.let { add(R.string.photos_info_file to it) }
    }
    Column(
        Modifier.padding(32.dp).widthIn(max = 560.dp)
            .background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(entry.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(label), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 96.dp))
                Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val REPEATABLE_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.MediaFastForward, Key.MediaNext, Key.ChannelUp, Key.PageDown,
    Key.MediaRewind, Key.MediaPrevious, Key.ChannelDown, Key.PageUp,
)

private val HANDLED_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
    Key.Spacebar, Key.MediaFastForward, Key.MediaNext, Key.ChannelUp, Key.PageDown,
    Key.MediaRewind, Key.MediaPrevious, Key.ChannelDown, Key.PageUp, Key.Menu,
)

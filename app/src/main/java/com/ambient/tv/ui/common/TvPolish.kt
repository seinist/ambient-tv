package com.ambient.tv.ui.common

import com.ambient.tv.ui.theme.SelectionIndicator
import com.ambient.tv.network.artworkRefreshRevision
import com.ambient.tv.network.artworkNetworkAccess
import coil3.request.crossfade
import coil3.request.bitmapConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import com.ambient.tv.ui.sound.rememberUiSoundAction
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.interactionColors
import com.ambient.tv.ui.theme.menuFocusMotion
import com.ambient.tv.ui.navigation.LocalSidebarContentPending
import kotlinx.coroutines.delay

/**
 * Surface's own default focused/pressed container color inverts to a light color (its normal
 * TV-focus convention). List/grid rows across the app draw their own, much subtler focus
 * background on top of Surface's content instead, so Surface itself needs to stay fully
 * transparent in every interaction state or the two fight and wash the row out to near-white.
 * That includes the disabled state: Surface's grey disabled fill covers its full min-size box,
 * which is usually taller than the drawn control, and shows up as a second offset frame.
 */
@Composable
fun transparentSurfaceColors(): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    focusedContainerColor = Color.Transparent,
    pressedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
)

/**
 * Surface's own default focus-scale compounds with any Modifier.scale() a caller applies on
 * top, and list/grid rows here that DON'T apply their own scale (relying on a border/background
 * highlight instead) still got an uncontrolled pop-and-overlap from this default. Rows that DO
 * apply their own scale should use this too, rather than getting both.
 */
@Composable
fun noSurfaceScale() = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f)

/** Surface's default focused-disabled stroke is a 2dp scheme.border; keep chrome off Surface. */
@Composable
fun noSurfaceBorder(): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None,
    disabledBorder = Border.None,
    focusedDisabledBorder = Border.None,
)

/**
 * Shared media focus policy for Home tiles, library grids and provider rows.
 * Balanced/Maximum keep their familiar spring; reduced UI profiles use a shorter,
 * smaller tween, without layout, blur or extra shadows.
 */
internal fun mediaFocusTargetScale(focusedScale: Float, reducedEffects: Boolean): Float =
    if (reducedEffects) 1f + (focusedScale - 1f) * 0.5f else focusedScale

internal fun mediaFocusReducedDurationMs(focused: Boolean): Int = if (focused) 150 else 115

@Composable
internal fun rememberMediaFocusScale(
    focused: Boolean,
    focusedScale: Float,
): androidx.compose.runtime.State<Float> {
    val reduced = LocalPerformanceProfile.current.reducedEffects
    return animateFloatAsState(
        targetValue = if (focused) mediaFocusTargetScale(focusedScale, reduced) else 1f,
        animationSpec = if (reduced) {
            tween(mediaFocusReducedDurationMs(focused), easing = androidx.compose.animation.core.FastOutSlowInEasing)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "mediaFocusScale",
    )
}

/**
 * Applies the shared focus scale to a graphics layer. Full-width list rows can pass a
 * smaller [focusedScale] so adjacent actions and viewport edges stay clear.
 */
@Composable
fun Modifier.mediaFocusMotion(
    focused: Boolean,
    focusedScale: Float = LocalUiTokens.current.focusScale,
): Modifier {
    val performance = LocalPerformanceProfile.current
    val tokens = LocalUiTokens.current
    val animatedScale = rememberMediaFocusScale(focused, focusedScale)
    val shadow = animateFloatAsState(
        if (focused && performance.allowFocusedItemShadow) 5f else 0f,
        tween(if (focused) 230 else 210),
        label = "mediaFocusShadow",
    )
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = animatedScale.value
            scaleY = animatedScale.value
            // One existing layer for both motions; no abrupt shadow removal.
            shadowElevation = shadow.value.dp.toPx()
            shape = RoundedCornerShape(tokens.cardRadius)
            clip = false
        }
}

private const val MARQUEE_START_DELAY_MS = 1500
private const val MARQUEE_REPEAT_DELAY_MS = 2000
private val MARQUEE_VELOCITY = 28.dp
private val MARQUEE_GAP = 32.dp
private val MARQUEE_FADE_WIDTH = 12.dp

/**
 * Single-line card title that scrolls right-to-left while its card is [focused] and the text
 * does not fit; every other card keeps a static ellipsis, so at most one title moves at a time.
 *
 * The caller passes [focused] because most titles sit beside the focusable Surface rather than
 * inside it, where basicMarquee's own WhileFocused mode never sees the focus change.
 */
@Composable
fun MarqueeTitle(
    text: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    // Only trusted while the ellipsis is showing: under basicMarquee the text is measured
    // unbounded and never reports an overflow, which would switch the marquee straight off again.
    var overflows by remember(text) { mutableStateOf(false) }
    val scrolling = focused && overflows
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { if (!scrolling) overflows = it.hasVisualOverflow },
        modifier = if (scrolling) {
            modifier
                .marqueeFadeEdge()
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    repeatDelayMillis = MARQUEE_REPEAT_DELAY_MS,
                    initialDelayMillis = MARQUEE_START_DELAY_MS,
                    spacing = MarqueeSpacing(MARQUEE_GAP),
                    velocity = MARQUEE_VELOCITY,
                )
        } else modifier,
    )
}

/** [MarqueeTitle] centered across the full card width, for text below or on top of card artwork. */
@Composable
fun CenteredMarqueeTitle(
    text: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    MarqueeTitle(
        text = text,
        focused = focused,
        modifier = modifier.fillMaxWidth(),
        style = style.copy(textAlign = TextAlign.Center),
        color = color,
    )
}

/** Fades the trailing edge out so scrolling text enters softly instead of at a hard cut. */
private fun Modifier.marqueeFadeEdge(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadeStart = (1f - MARQUEE_FADE_WIDTH.toPx() / size.width).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Black, fadeStart to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Without this, D-pad right past a horizontal row's last item searches outside the row for
 * the next-nearest focusable - often something above it, like a page's filter tabs - jumping
 * out of the row instead of just stopping at its last item. Left is left alone, since exiting
 * left toward the sidebar at the first item is the wanted behavior.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.containRowFocusExit(): Modifier = focusProperties {
    onExit = { if (requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
}

/**
 * Mouse/pointer hover follows the same focus state as D-pad navigation. That reuses every
 * control's existing focus highlight and makes the next keyboard press continue at the item
 * under the pointer instead of leaving an invisible second selection elsewhere on screen.
 */
private val pointerFocus = TvPointerFocusState()

internal fun suppressTvPointerFocus() {
    pointerFocus.onKeyInput()
}

fun Modifier.focusOnTvPointer(
    enabled: Boolean = true,
    pointerIcon: PointerIcon = PointerIcon.Hand,
): Modifier = composed {
    if (!enabled || com.ambient.tv.ui.mobile.LocalHandheldUi.current) return@composed Modifier
    val requester = remember { FocusRequester() }
    val coordinates = remember { object { var value: androidx.compose.ui.layout.LayoutCoordinates? = null } }
    Modifier
        .focusRequester(requester)
        .onGloballyPositioned { coordinates.value = it }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) com.ambient.tv.companion.CompanionRemoteInput.hide()
            false
        }
        .pointerHoverIcon(pointerIcon)
        .pointerInput(enabled, pointerFocus) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Enter && event.type != PointerEventType.Move && event.type != PointerEventType.Press) continue
                    val layout = coordinates.value?.takeIf { it.isAttached } ?: continue
                    val position = event.changes.firstOrNull()?.position ?: continue
                    val screenPosition = layout.localToScreen(position)
                    if (!screenPosition.x.isFinite() || !screenPosition.y.isFinite()) continue
                    if (pointerFocus.onPointer(screenPosition.x, screenPosition.y,
                            entered = event.type == PointerEventType.Enter, pressed = event.type == PointerEventType.Press)) {
                        runCatching { requester.requestFocus() }
                    }
                }
            }
        }
}

/** Mouse-wheel/trackpad hook for custom controls; standard lazy/scroll containers need none. */
fun Modifier.onTvPointerScroll(
    enabled: Boolean = true,
    onScroll: (androidx.compose.ui.geometry.Offset) -> Boolean,
): Modifier = composed {
    if (!enabled) return@composed Modifier
    val currentScroll by androidx.compose.runtime.rememberUpdatedState(onScroll)
    Modifier.pointerInput(enabled) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (currentScroll(change.scrollDelta)) change.consume()
            }
        }
    }
}

/** Ensures physical keyboards and TV remotes activate focused controls consistently.
 * TV Material handles DPAD_CENTER on most devices, but desktop/USB/Bluetooth keyboards can
 * deliver Enter/NumpadEnter/Space in ways that do not reach ClickableSurface reliably.
 */
fun Modifier.activateOnTvKey(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    activateOnTvKey(enabled = enabled, onClick = onClick, onLongClick = null)

/** Menu, Info, or a touch long-press. OK still activates [onClick] on the first key-down. */
fun Modifier.activateOnTvKey(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier =
    this.then(Modifier.composed {
        val click by androidx.compose.runtime.rememberUpdatedState(onClick)
        val longClick by androidx.compose.runtime.rememberUpdatedState(onLongClick)
        val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
        if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
            Modifier.focusProperties { canFocus = inputMode.inputMode != androidx.compose.ui.input.InputMode.Touch }
                .pointerInput(enabled, onLongClick) {
                    if (onLongClick != null) {
                        detectTapGestures(
                            onLongPress = { if (enabled) longClick?.invoke() },
                            onTap = { if (enabled) click() },
                        )
                    } else {
                        detectTapGestures(onTap = { if (enabled) click() })
                    }
                }
        } else Modifier.focusOnTvPointer(enabled)
    }).onPreviewKeyEvent { event ->
        if (onLongClick != null && (event.key == Key.Menu || event.key == Key.Info)) {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && enabled) onLongClick.invoke()
            return@onPreviewKeyEvent true
        }
        val activationKey = event.key == Key.Enter ||
            event.key == Key.NumPadEnter ||
            event.key == Key.DirectionCenter ||
            event.key == Key.Spacebar
        if (!activationKey) return@onPreviewKeyEvent false
        // Activate on the key-down that the focused control actually received.  Activating on
        // key-up caused a subtle TV-player bug: OK opened a hidden OSD, focus moved to the
        // Play/Pause button before OK was released, and that same release then paused playback.
        // Consuming both phases while firing only on KeyDown prevents this focus-transfer
        // "ghost click" and still keeps remote/keyboard activation deterministic.
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && enabled) onClick()
        true
    }

/**
 * Draws the outline fully inside the component bounds.  The previous custom implementation
 * centered the stroke on the outer shape path; when a control was clipped to the same rounded
 * shape, half of that stroke was clipped away at the corners.  Compose's border modifier keeps
 * the stroke inside the outline and therefore preserves all four rounded corners.
 */
fun Modifier.uniformBorder(
    width: Dp,
    color: Color,
    shape: Shape,
): Modifier = border(width = width, color = color, shape = shape)

private class ArtworkRequestContinuity {
    var model: Any? = null
    var identity: Any? = null
    var memoryKey: coil3.memory.MemoryCache.Key? = null
}

/** Recent memory-cache keys per artwork model, one index per loader; see [ArtworkMemoryKeyIndex]. */
private val CardArtworkKeys = ArtworkMemoryKeyIndex()

/** An OSD card is small enough to reuse the guide's already-decoded TMDb landscape. */
internal fun preferCachedTmdbCardVariant(context: android.content.Context, url: String?): String? {
    val prefix = "https://image.tmdb.org/t/p/w780/"
    if (url == null || !url.startsWith(prefix)) return url
    val thumb = "https://image.tmdb.org/t/p/w300/" + url.removePrefix(prefix)
    val cache = coil3.SingletonImageLoader.get(context).memoryCache ?: return url
    fun present(model: String) = CardArtworkKeys.cachedKey(model) { cache[it] != null } != null
    return if (!present(url) && present(thumb)) thumb else url
}
private val BackdropArtworkKeys = ArtworkMemoryKeyIndex(maxModels = 64)

/** Only the edited URLs restart their requests; ordinary frames/progress never emit here. */
@Composable
internal fun rememberArtworkRefresh(
    model: Any?, refresh: com.ambient.tv.network.MediaArtworkRefresh?, address: com.ambient.tv.network.MediaArtworkAddress?,
): Long {
    val url = remember(model) { (model as? String)?.let { com.ambient.tv.network.mediaArtworkResourceUrl(it) } }
    val revision by androidx.compose.runtime.produceState(0L, url, address?.imageKey) {
        value = 0L
        var previous = address?.let { refresh?.cachedIdentity(it)?.revision }
        if (url != null) com.ambient.tv.network.MediaArtworkRefreshEvents.changes.collect { event ->
            val current = address?.let { refresh?.cachedIdentity(it)?.revision }
            // StateFlow may conflate two edits; compare this image's retained revision as well.
            if (com.ambient.tv.network.mediaArtworkRefreshChanged(previous, current, url in event.urls)) value = event.sequence
            if (current != null) previous = current
        }
    }
    return revision
}

@Composable
fun ArtworkImage(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    headers: Map<String, String> = emptyMap(),
    fallbackText: String = title,
    fallbackModels: List<Any?> = emptyList(),
    /** Crop backdrops around detected faces instead of [alignment] (see rememberArtworkFocusAlignment). */
    focusCrop: Boolean = false,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** False for full-screen backdrops: no grey pulse while loading, no title card on failure. */
    placeholder: Boolean = true,
    /** Optional caller-specific fallback, used after all URLs fail (or none is supplied). */
    fallbackContent: (@Composable () -> Unit)? = null,
    /**
     * Browse cards opt in so uncached fetch/decode work waits for active scrolling to settle
     * and is then spread across frames. Known memory-cache hits still render immediately.
     */
    staggerUncached: Boolean = false,
    /** Focused browse cards skip the background admission wave once scrolling is quiet. */
    prioritizeUncached: Boolean = false,
    /**
     * Full-screen art (detail backdrops) loads through the backdrop loader: one such bitmap
     * weighs as much as dozens of cards and must not push them out of the card cache.
     */
    backdrop: Boolean = focusCrop,
    /** Fade new loaded artwork in; Coil and our memory index keep cached tiles instantaneous. */
    crossfadeOnLoad: Boolean = true,
    /** Locked detail previews may display cached bytes, but must not fetch remote artwork. */
    cacheOnly: Boolean = false,
    /** A caller can defer offscreen requests; memory hits and already-admitted work remain usable. */
    loadUncached: Boolean = true,
) {
    val context = LocalContext.current
    val candidates = remember(model, fallbackModels) {
        // An HD Telegram thumbnail that downloads but cannot be decoded must still fall back
        // to the post's inline preview instead of the title card.
        val monogramFallback = (model as? String)
            ?.let { com.ambient.tv.data.telegram.parseMonogramThumbModel(it) }?.fallback
        (listOf(model, monogramFallback) + fallbackModels)
            .filter { it != null && (it !is String || it.isNotBlank()) }
            .distinct()
    }
    val primaryNetwork = rememberArtworkNetworkAccess(candidates.firstOrNull(), artworkHeadersForModel(candidates.firstOrNull(), headers))
    var candidateIndex by remember(candidates, primaryNetwork.recovery) { mutableStateOf(0) }
    val currentModel = candidates.getOrNull(candidateIndex)
    val effectiveHeaders = remember(currentModel, headers) { artworkHeadersForModel(currentModel, headers) }
    val network = rememberArtworkNetworkAccess(currentModel, effectiveHeaders)
    val headerKey = remember(effectiveHeaders) { com.ambient.tv.network.mediaArtworkHeadersKey(effectiveHeaders) }
    val refresh = (context.applicationContext as? com.ambient.tv.AmbientTvApplication)?.mediaArtworkRefresh
    val sourceVersion = refresh?.sources?.collectAsState()?.value
    val address = remember(currentModel, effectiveHeaders, sourceVersion, refresh) {
        (currentModel as? String)?.let { refresh?.address(it, effectiveHeaders) }
    }
    val cacheIdentity = address?.let { refresh?.cachedIdentity(it) }
    val refreshRevision = rememberArtworkRefresh(currentModel, refresh, address)
    val hasModel = currentModel != null
    val continuity = remember { ArtworkRequestContinuity() }
    val requestIdentity = remember(currentModel, refreshRevision, address?.imageKey, headerKey) {
        listOf(com.ambient.tv.data.browse.artworkRequestIdentity(currentModel), address?.imageKey, headerKey, refreshRevision)
    }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val constrainDecode = LocalPerformanceProfile.current.constrainImageDecodeSize
    // Coil only checks its memory cache after AsyncImage has been measured, which leaves a
    // freshly composed tile empty for a frame (e.g. every poster on Back to Home). The key of
    // the last successful load is served as placeholder before the size is resolved.
    val imageLoader = if (backdrop) com.ambient.tv.backdropImageLoader(context) else coil3.SingletonImageLoader.get(context)
    fun currentMemoryKey(key: coil3.memory.MemoryCache.Key): Boolean =
        (if (address != null) com.ambient.tv.network.mediaArtworkMemoryKeyMatches(key.extras, cacheIdentity)
         else key.extras[com.ambient.tv.network.MEDIA_ARTWORK_IDENTITY_EXTRA] == null &&
             key.extras[com.ambient.tv.network.MEDIA_ARTWORK_HEADERS_EXTRA] == headerKey) &&
            imageLoader.memoryCache?.get(key) != null
    // A cold-start snapshot and the live response can describe the same provider image with
    // different credential query strings. If this exact visible card already succeeded, link
    // the live request to that memory entry so Coil returns it without fetch/decode or a blank frame.
    val continuityKey = continuity.memoryKey?.takeIf { key ->
        continuity.model != currentModel &&
            continuity.identity == requestIdentity &&
            currentMemoryKey(key)
    }
    val keyIndex = if (backdrop) BackdropArtworkKeys else CardArtworkKeys
    val cachedKey = remember(currentModel, continuityKey, backdrop, refreshRevision, cacheIdentity, headerKey) {
        currentModel?.let { model -> keyIndex.cachedKey(model, ::currentMemoryKey) }
            ?: continuityKey
    }
    // The key can outlive the bitmap. A miss must still show the pulse; Coil's placeholder
    // only draws when the bitmap is actually still in memory.
    val shownFromMemory = remember(currentModel, cachedKey, context, refreshRevision) {
        val key = cachedKey ?: return@remember false
        imageLoader.memoryCache?.get(key) != null
    }
    val staggerThisLoad = staggerUncached && candidateIndex == 0 &&
        !com.ambient.tv.ui.mobile.LocalHandheldUi.current && hasModel && !shownFromMemory
    // Keep admission once granted. A focus change restarts only a request still waiting in the
    // gate, which lets the newly focused card jump ahead without restarting an active load.
    var loadAdmitted by remember(currentModel) {
        mutableStateOf(shownFromMemory || loadUncached && !staggerThisLoad)
    }
    LaunchedEffect(currentModel, staggerThisLoad, prioritizeUncached, loadUncached, shownFromMemory) {
        if (shownFromMemory) {
            loadAdmitted = true
        } else if (!loadAdmitted && loadUncached) {
            if (staggerThisLoad) {
                awaitBrowseArtworkIdle()
                val waitMs = browseArtworkAdmissionDelayMs(
                    prioritized = prioritizeUncached,
                    nowMs = android.os.SystemClock.elapsedRealtime(),
                )
                if (waitMs > 0L) delay(waitMs)
                // Another D-pad/touch move may have started while this card waited in its wave.
                // Re-check once so decode/bitmap delivery never begins in the middle of a glide.
                awaitBrowseArtworkIdle()
            }
            loadAdmitted = true
        }
    }
    // Settings › Appearance: card images decode at a 1080p interface's size on 4K interfaces.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val cap1080 = !backdrop && density > CARD_IMAGE_1080_DENSITY &&
        remember(context) { com.ambient.tv.ui.player.videoImagePreferences(context).cardImages1080 }
    val cardSize = remember(cap1080, density) { if (cap1080) CardImage1080SizeResolver(density) else null }
    // A successful painter keeps its request during an outage/recovery. Only failed/in-flight
    // images retry; otherwise every visible poster would briefly re-enter its loading state.
    var successfulNetwork by remember(currentModel, refreshRevision, address?.imageKey, headerKey) {
        mutableStateOf<com.ambient.tv.network.ArtworkNetworkState?>(null)
    }
    val requestNetwork = successfulNetwork ?: network
    val fadeMillis = artworkLoadFadeMillis(
        requested = crossfadeOnLoad,
        reducedEffects = LocalPerformanceProfile.current.reducedEffects,
        cachedInMemory = shownFromMemory,
    )
    val request = remember(currentModel, context, headers, constrainDecode, cardSize, fadeMillis, refreshRevision, address?.imageKey, headerKey, requestNetwork, cacheOnly) {
        ImageRequest.Builder(context).data(currentModel)
            .apply { if (fadeMillis > 0) crossfade(fadeMillis) else crossfade(false) }
            .placeholderMemoryCacheKey(cachedKey)
            .apply {
                // Only reuse a full cache key inside the same composable/card. That guarantees
                // identical layout constraints; global URL aliases could incorrectly reuse a
                // small tile bitmap for a large detail/backdrop request.
                if (continuityKey != null) memoryCacheKey(continuityKey)
                artworkRefreshRevision(refreshRevision)
                memoryCacheKeyExtra(com.ambient.tv.network.MEDIA_ARTWORK_IDENTITY_EXTRA, address?.imageKey)
                memoryCacheKeyExtra(com.ambient.tv.network.MEDIA_ARTWORK_HEADERS_EXTRA, headerKey)
                requestHeaders(effectiveHeaders)
                // AsyncImage's constraints resolver decodes at the measured view size.
                // Do not wait for onSizeChanged/recomposition before creating the painter.
                if (constrainDecode) {
                    bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                }
                // Inexact like AsyncImage's own resolver: never upscale a smaller source.
                cardSize?.let { size(it); precision(coil3.size.Precision.INEXACT) }
            }
            .artworkNetworkAccess(requestNetwork, cacheOnly)
            .build()
    }
    var loading by remember(currentModel) { mutableStateOf(hasModel && !shownFromMemory) }
    // Keep the existing pulse *behind* the newly decoded image while Coil fades it in.
    // Otherwise dismissing the pulse on success exposes a dark frame at fade alpha = 0.
    var fadeBackground by remember(currentModel, refreshRevision, address?.imageKey, headerKey) { mutableStateOf(false) }
    if (fadeBackground) {
        LaunchedEffect(currentModel, fadeMillis) {
            delay(fadeMillis.toLong())
            fadeBackground = false
        }
    }
    var failed by remember(candidates) { mutableStateOf(false) }
    var visibleFraction by remember(currentModel, headers, refreshRevision, address?.imageKey) { mutableStateOf<Float?>(null) }
    var focusRevision by remember(currentModel, headers, refreshRevision, address?.imageKey) { mutableStateOf<String?>(null) }
    val focusModel = (currentModel as? String)?.takeIf { focusCrop && contentScale == ContentScale.Crop }
    val focus = if (focusModel != null) rememberArtworkFocusCrop(focusModel, artworkHeadersForModel(focusModel, headers), visibleFraction, focusRevision) else null
    val hdrFilter = com.ambient.tv.ui.display.rememberUiHdrBitmapColorFilter()
    Box(modifier = if (focusCrop) modifier.onSizeChanged { containerSize = it } else modifier) {
        if (hasModel) {
            if (loadAdmitted && placeholder && (loading || fadeBackground)) PulsingArtworkPlaceholder()
            if (loadAdmitted) AsyncImage(
                model = request,
                contentDescription = title,
                imageLoader = imageLoader,
                contentScale = contentScale,
                alignment = focus?.alignment ?: alignment,
                colorFilter = hdrFilter,
                modifier = Modifier.fillMaxSize().then(cardSize?.modifier() ?: Modifier),
                onLoading = { loading = !shownFromMemory; fadeBackground = false; failed = false },
                onSuccess = {
                    val source = it.result.dataSource
                    fadeBackground = placeholder && fadeMillis > 0 &&
                        source != coil3.decode.DataSource.MEMORY_CACHE && source != coil3.decode.DataSource.MEMORY
                    loading = false
                    failed = false
                    successfulNetwork = requestNetwork
                    val key = it.result.memoryCacheKey
                    // Diagnostics: why a picture that was loaded before had to load again.
                    // Without the query: signed thumbnail URLs differ only there between two fetches.
                    val pictureIdentity = (currentModel as? String)?.substringBefore('?') ?: requestIdentity
                    if (!backdrop && source != coil3.decode.DataSource.MEMORY_CACHE &&
                        source != coil3.decode.DataSource.MEMORY) {
                        val known = keyIndex.keys(currentModel)
                        val urlChanged = keyIndex.modelChanged(pictureIdentity, currentModel)
                        if (known.isNotEmpty() || urlChanged) {
                            val cache = imageLoader.memoryCache
                            val reason = when {
                                urlChanged && known.isEmpty() -> "url-changed"
                                key != null && key !in known -> "size-changed"
                                else -> "evicted"
                            }
                            com.ambient.tv.diagnostics.DiagnosticLog.info("artwork",
                                "reload reason=$reason source=$source id=${currentModel.hashCode().toUInt().toString(16)} " +
                                    "key=${key?.extras} known=${known.map { k -> k.extras }} " +
                                    "cache=${(cache?.size ?: 0L) / 1024}/${(cache?.maxSize ?: 0L) / 1024} KiB")
                        }
                    } else keyIndex.modelChanged(pictureIdentity, currentModel)
                    if (key != null) {
                        keyIndex.remember(currentModel, key)
                        continuity.model = currentModel
                        continuity.identity = requestIdentity
                        continuity.memoryKey = key
                    }
                    if (focusCrop) {
                        focusRevision = artworkFocusRevision(it.result.diskCacheKey, it.result.memoryCacheKey?.key, it.result.memoryCacheKey?.extras.orEmpty())
                        visibleFraction = cropVisibleFraction(it.painter.intrinsicSize, containerSize) ?: 1f
                    }
                },
                onError = {
                    loading = false
                    fadeBackground = false
                    successfulNetwork = null
                    if (candidateIndex < candidates.lastIndex) {
                        candidateIndex += 1
                    } else {
                        failed = true
                    }
                },
            )
        }
        if (placeholder && (!hasModel || failed)) {
            when {
                fallbackContent != null -> fallbackContent()
                fallbackIcon == null -> ArtworkFallback(fallbackText)
                else -> Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(fallbackIcon, title, Modifier.fillMaxSize(0.3f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private class BackdropLayer(val id: Long, val model: Any, val refreshRevision: Long, val scope: String?) {
    val alpha = androidx.compose.animation.core.Animatable(0f)
}

/**
 * Full-bleed backdrop that crossfades when [model] changes. Unlike a plain Crossfade, the
 * previous image stays fully opaque until the next one has actually loaded, so focus moves
 * never flash an empty or placeholder frame. Superseded images that never finished loading
 * are dropped, which keeps fast row traversal from stacking requests.
 */
@Composable
fun CrossfadeBackdropImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    headers: Map<String, String> = emptyMap(),
    durationMillis: Int = 450,
    /** Crop backdrops around detected faces instead of [alignment] (see rememberArtworkFocusAlignment). */
    focusCrop: Boolean = false,
    /** Fixed decode bound instead of the measured (full-screen) size, for dimmed backdrops. */
    decodeSize: IntSize? = null,
    /** False when an unavailable incoming image must not retain unrelated previous art. */
    retainPreviousOnError: Boolean = true,
) {
    val context = LocalContext.current
    val layers = remember { androidx.compose.runtime.mutableStateListOf<BackdropLayer>() }
    var nextId by remember { mutableStateOf(0L) }
    val normalized = model?.takeIf { it !is String || it.isNotBlank() }
    val effectiveHeaders = remember(normalized, headers) { artworkHeadersForModel(normalized, headers) }
    val headerKey = remember(effectiveHeaders) { com.ambient.tv.network.mediaArtworkHeadersKey(effectiveHeaders) }
    val refresh = (context.applicationContext as? com.ambient.tv.AmbientTvApplication)?.mediaArtworkRefresh
    val sourceVersion = refresh?.sources?.collectAsState()?.value
    val address = remember(normalized, effectiveHeaders, sourceVersion, refresh) {
        (normalized as? String)?.let { refresh?.address(it, effectiveHeaders) }
    }
    val scope = address?.scope ?: headerKey
    val refreshRevision = rememberArtworkRefresh(normalized, refresh, address)
    val network = rememberArtworkNetworkAccess(normalized, effectiveHeaders)
    LaunchedEffect(normalized, refreshRevision, scope, network.recovery) {
        if (layers.lastOrNull()?.scope != scope) layers.clear()
        if (normalized == null) {
            layers.clear()
        } else if (layers.lastOrNull()?.model != normalized || layers.lastOrNull()?.refreshRevision != refreshRevision) {
            layers.removeAll { it.alpha.value == 0f && !it.alpha.isRunning }
            layers.add(BackdropLayer(nextId++, normalized, refreshRevision, scope))
        }
    }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val constrainDecode = LocalPerformanceProfile.current.constrainImageDecodeSize
    Box(if (focusCrop) modifier.onSizeChanged { containerSize = it } else modifier) {
        layers.filter { it.scope == scope }.forEach { layer ->
            androidx.compose.runtime.key(layer.id) {
                val layerNetwork = rememberArtworkNetworkAccess(layer.model, artworkHeadersForModel(layer.model, headers))
                var successfulNetwork by remember { mutableStateOf<com.ambient.tv.network.ArtworkNetworkState?>(null) }
                val requestNetwork = successfulNetwork ?: layerNetwork
                val request = remember(layer.model, context, headers, constrainDecode, decodeSize, requestNetwork) {
                    ImageRequest.Builder(context).data(layer.model).crossfade(false)
                        .artworkRefreshRevision(layer.refreshRevision)
                        .artworkNetworkAccess(requestNetwork)
                        .apply {
                            requestHeaders(artworkHeadersForModel(layer.model, headers))
                            if (decodeSize != null) {
                                size(decodeSize.width, decodeSize.height)
                                scale(if (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside) coil3.size.Scale.FIT else coil3.size.Scale.FILL)
                            }
                            if (constrainDecode) {
                                bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                            }
                        }
                        .build()
                }
                var visibleFraction by remember(headers) { mutableStateOf<Float?>(null) }
                var focusRevision by remember(headers) { mutableStateOf<String?>(null) }
                val focusModel = (layer.model as? String)?.takeIf { focusCrop && contentScale == ContentScale.Crop }
                val focus = if (focusModel != null) rememberArtworkFocusCrop(focusModel, artworkHeadersForModel(focusModel, headers), visibleFraction, focusRevision) else null
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(loaded, focus?.ready) {
                    // With focusCrop, fade in only once the crop is final so it never shifts on screen.
                    if (!loaded || focus?.ready == false) return@LaunchedEffect
                    layer.alpha.animateTo(1f, tween(durationMillis, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    // Everything underneath is now fully covered.
                    val index = layers.indexOf(layer)
                    if (index > 0) layers.removeRange(0, index)
                }
                val hdrFilter = com.ambient.tv.ui.display.rememberUiHdrBitmapColorFilter()
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    // Own memory budget: backdrops must never evict the browse cards' bitmaps.
                    imageLoader = com.ambient.tv.backdropImageLoader(context),
                    contentScale = contentScale,
                    alignment = focus?.alignment ?: alignment,
                    colorFilter = hdrFilter,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        // A single image draw has no overlapping children. Modulating it avoids
                        // a full-screen intermediate texture for every crossfade layer.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = layer.alpha.value
                    },
                    onSuccess = {
                        successfulNetwork = requestNetwork
                        if (focusCrop) {
                            focusRevision = artworkFocusRevision(it.result.diskCacheKey, it.result.memoryCacheKey?.key, it.result.memoryCacheKey?.extras.orEmpty())
                            visibleFraction = cropVisibleFraction(it.painter.intrinsicSize, containerSize) ?: 1f
                        }
                        loaded = true
                    },
                    onError = {
                        successfulNetwork = null
                        // Backdrops retain their last valid artwork, but changing radio
                        // stations must never leave the previous station's cover indefinitely.
                        if (!retainPreviousOnError) layers.clear()
                        else if (layers.size > 1) layers.remove(layer)
                    },
                )
            }
        }
    }
}

@Composable
internal fun PulsingArtworkPlaceholder() {
    // Sparsam stays a flat fill and never touches the pulse clock.
    val animate = !LocalPerformanceProfile.current.reducedEffects
    val pulse = if (animate) rememberSharedPulse(SharedArtworkPulse) else null
    val fill = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                // Updating this RenderNode keeps the pulse visible through cached parent
                // layers without re-recording the whole Home list on every animation frame.
                alpha = pulse?.value ?: 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawBehind { drawRect(fill) },
    )
}

/**
 * The one card face for artwork that is missing or failed: the same gradient fill on every
 * system. An optional [icon] (e.g. a game system's emblem) sits above the title; tiny frames
 * keep only the icon, which then identifies the card on its own.
 */
@Composable
internal fun ArtworkFallback(
    title: String,
    icon: androidx.compose.ui.graphics.painter.Painter? = null,
    iconModifier: Modifier = Modifier,
    preserveIconColors: Boolean = false,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val compact = maxWidth < 96.dp || maxHeight < 64.dp
        if (icon == null) {
            Text(
                text = title.ifBlank { "?" }.take(42),
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(if (compact) 2.dp else 14.dp),
            )
            return@BoxWithConstraints
        }
        // Room for an emblem plus at least one readable title line; otherwise the emblem alone.
        val withTitle = title.isNotBlank() && maxWidth >= 88.dp && maxHeight >= 112.dp
        val iconSize = minOf(maxWidth, maxHeight) * if (withTitle) .34f else .5f
        // Read the constraints here: the Column's own scope cannot reach BoxWithConstraints'.
        val titleLines = if (maxHeight >= 170.dp) 3 else 2
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize).then(iconModifier),
                tint = if (preserveIconColors) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (withTitle) Text(
                text = title.take(42),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StatePanel(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    centered: Boolean = false,
    phase: com.ambient.tv.notifications.FeedbackPhase? = null,
    actionLoading: Boolean = false,
) {
    // Callers often hand in fillMaxSize/fillMaxWidth for placement; the panel itself only takes
    // the width its text needs and wraps long text at a readable line length.
    Box(modifier, contentAlignment = Alignment.Center) {
        FeedbackContent(
            title = title, body = body, phase = phase, modifier = Modifier.widthIn(max = if (centered) 520.dp else 620.dp), layout = FeedbackLayout.PAGE,
            actionLabel = actionLabel, onAction = onAction,
            secondaryActionLabel = secondaryActionLabel, onSecondaryAction = onSecondaryAction,
            requestActionFocus = onAction != null && actionLabel != null,
            icon = icon, centered = centered, actionLoading = actionLoading,
        )
    }
}

/**
 * Keep custom slots consistent across TV and mobile Material. Press feedback comes from the
 * shared dark control surface: a second, light Material ripple would wash out its contrast.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProvideControlContent(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        androidx.tv.material3.LocalContentColor provides color,
        androidx.compose.material3.LocalContentColor provides color,
        androidx.compose.material3.LocalRippleConfiguration provides null,
        content = content,
    )
}

private fun headerKeyAction(icon: ImageVector?): com.ambient.tv.ui.keyboard.KeyAction? = when (icon) {
    null -> null
    Icons.Filled.Refresh -> com.ambient.tv.ui.keyboard.KeyAction.REFRESH
    Icons.Filled.Tune -> com.ambient.tv.ui.keyboard.KeyAction.VIEW_OPTIONS
    Icons.Filled.Search -> com.ambient.tv.ui.keyboard.KeyAction.SEARCH
    else -> null
}

/**
 * Compact control for tabs, filters and page-header actions (see [PageHeader]).
 *
 * [loading] marks the chip's own action as running: it keeps its look and focus, ignores clicks
 * and shows a spinner in the icon slot. [loadingOnlyAfterClick] leaves the Reload icon still
 * during automatic entry/cache loads, and shows progress only for a click initiated here.
 *
 * [iconOnly] is a handheld layout hint for page-header actions (sign in, search, view, and the
 * same kind of toolbar button on other screens). Phones drop the label and keep [text] as the
 * button content description. Television always draws the label.
 */
@Composable
fun TvChip(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false,
    iconOnly: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    textMaxLines: Int = Int.MAX_VALUE,
    /** For Reload, automatic entry/cache work must not animate the action icon. */
    loadingOnlyAfterClick: Boolean = false,
    onClick: () -> Unit,
) {
    var clickStartedLoad by remember { mutableStateOf(false) }
    val showLoading = loading && (!loadingOnlyAfterClick || clickStartedLoad)
    LaunchedEffect(loadingOnlyAfterClick, loading, clickStartedLoad) {
        if (!loadingOnlyAfterClick) clickStartedLoad = false
        else if (clickStartedLoad && !loading) {
            // A no-op action cannot arm an unrelated background refresh later on.
            androidx.compose.runtime.withFrameNanos { }
            if (!loading) clickStartedLoad = false
        }
    }
    val clickable = enabled && !loading
    // Selected can still point to the old category during an asynchronous refresh.
    // Retain the exact chip the user confirmed rather than restoring the first chip.
    val chipHold = if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) null else LocalPageChipFocusHold.current
    val chipFocusRequester = if (chipHold != null) remember { FocusRequester() } else null
    val chipModifier = if (chipHold != null && chipFocusRequester != null) {
        modifier.focusRequester(chipFocusRequester).onFocusChanged {
            if (it.isFocused) chipHold.focused(chipFocusRequester)
            else chipHold.blurred(chipFocusRequester)
        }
    } else modifier
    val uiSounds = LocalUiSoundPlayer.current
    val activate = rememberUiSoundAction(enabled = clickable) {
        if (loadingOnlyAfterClick) clickStartedLoad = true
        onClick()
    }
    // A page header's Refresh/View/Search answers its key binding on every page alike.
    if (LocalPageHeaderActions.current) headerKeyAction(icon)?.let { action ->
        com.ambient.tv.ui.keyboard.RegisterKeyAction(action, enabled = enabled) {
            // Still loading: the refresh is already running, nothing else should happen.
            if (clickable) activate()
            true
        }
    }
    val scheme = MaterialTheme.colorScheme
    val foreground = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = .55f)
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        val interactions = remember { MutableInteractionSource() }
        val focused by interactions.collectIsFocusedAsState()
        val pressed by interactions.collectIsPressedAsState()
        val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
        if (iconOnly && (icon != null || leadingContent != null)) {
            val buttonModifier = chipModifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .appControlSurface(focused, selected, enabled, shape, pressed = pressed,
                    selectionIndicator = if (iconOnly) SelectionIndicator.DOT else SelectionIndicator.NONE)
                .semantics { contentDescription = text }
            ProvideControlContent(foreground) {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = activate,
                    enabled = enabled,
                    modifier = buttonModifier,
                    shape = shape,
                    interactionSource = interactions,
                    colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = foreground,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = foreground,
                    ),
                ) {
                    if (showLoading) {
                        ButtonProgress(foreground, Modifier.size(20.dp))
                    } else if (leadingContent != null) {
                        leadingContent()
                    } else {
                        androidx.compose.material3.Icon(checkNotNull(icon), contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            return
        }
        ProvideControlContent(foreground) {
            androidx.compose.material3.FilterChip(
                selected = selected, onClick = activate, enabled = enabled,
                modifier = chipModifier.sizeIn(minHeight = 48.dp)
                    .appControlSurface(focused, selected, enabled, shape, pressed = pressed,
                    selectionIndicator = if (iconOnly) SelectionIndicator.DOT else SelectionIndicator.NONE),
                shape = shape,
                border = null,
                interactionSource = interactions,
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = foreground,
                    iconColor = foreground,
                    disabledContainerColor = Color.Transparent,
                    disabledLabelColor = foreground,
                    disabledLeadingIconColor = foreground,
                    disabledTrailingIconColor = foreground,
                    selectedContainerColor = Color.Transparent,
                    disabledSelectedContainerColor = Color.Transparent,
                    selectedLabelColor = foreground,
                    selectedLeadingIconColor = foreground,
                    selectedTrailingIconColor = foreground,
                ),
                label = { androidx.compose.material3.Text(text, maxLines = textMaxLines, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (showLoading) {
                    { ButtonProgress(foreground, Modifier.size(18.dp)) }
                } else leadingContent ?: icon?.let { image -> { androidx.compose.material3.Icon(image, null, Modifier.size(18.dp)) } },
            )
        }
        return
    }

    var focused by remember { mutableStateOf(false) }
    var keyPressed by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val pointerPressed by interactions.collectIsPressedAsState()
    val uiScale = rememberUhdScale()
    val tokens = LocalUiTokens.current
    val chipShape = remember(tokens.controlRadius) { RoundedCornerShape(tokens.controlRadius) }
    Surface(
        onClick = activate,
        enabled = enabled,
        // See TvPolish/Sidebar note: Surface's own focus/press container color defaults to a
        // light color, which fights with the Row's own (much subtler) highlight below.
        colors = transparentSurfaceColors(),
        // Keep Surface focus geometry fixed; focus is represented by color/border only.
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f),
        border = noSurfaceBorder(),
        // Surface clips its content, so it must use the exact same shape as the visible frame.
        shape = ClickableSurfaceDefaults.shape(shape = chipShape),
        interactionSource = interactions,
        modifier = chipModifier
            // 52dp total TV target, 48dp visible chrome: the previous 44dp pill
            // looked shrunken beside the header clock and regular action buttons.
            .sizeIn(minHeight = 52.dp * uiScale)
            .padding(2.dp)
            .menuFocusMotion(focused, focusedScale = 1.01f)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Enter || event.key == Key.NumPadEnter ||
                    event.key == Key.DirectionCenter || event.key == Key.Spacebar) {
                    if (event.type == KeyEventType.KeyUp) keyPressed = false
                    else if (event.type == KeyEventType.KeyDown && clickable) keyPressed = true
                }
                false
            }
            .activateOnTvKey(clickable, activate)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) uiSounds?.playFocus() else keyPressed = false
            },
    ) {
        // The drawn chip is shorter than its focus target; centred, it lines up with titles,
        // rows and buttons next to it instead of sitting at the top of that slot.
        Box(Modifier.sizeIn(minHeight = 48.dp * uiScale), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .appControlSurface(focused, selected, enabled, chipShape, pressed = keyPressed || pointerPressed,
                        selectionIndicator = SelectionIndicator.NONE)
                    .padding(horizontal = 14.dp * uiScale, vertical = 9.dp * uiScale),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProvideControlContent(foreground) {
                    if (showLoading) {
                        ButtonProgress(foreground, Modifier.size(20.dp * uiScale))
                        Spacer(Modifier.width(6.dp * uiScale))
                    } else if (leadingContent != null) {
                        leadingContent()
                        Spacer(Modifier.width(6.dp * uiScale))
                    } else icon?.let {
                        androidx.compose.material3.Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier.size(20.dp * uiScale),
                        )
                        Spacer(Modifier.width(6.dp * uiScale))
                    }
                    Text(
                        text = text,
                        maxLines = textMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        color = foreground,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    requireExplicitEditing: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    inputFormatter: (androidx.compose.ui.text.input.TextFieldValue) -> androidx.compose.ui.text.input.TextFieldValue = { it },
) {
    var editorValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    val displayedEditorValue = if (editorValue.text == value) editorValue else editorValue.copy(text = value)
    androidx.compose.runtime.SideEffect { editorValue = displayedEditorValue }
    val onEditorValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit = { incoming ->
        val formatted = inputFormatter(incoming)
        editorValue = formatted
        if (formatted.text != value) onValueChange(formatted.text)
    }
    // PasswordVisualTransformation is deliberately detected here instead of at every call
    // site.  That keeps the reveal affordance consistent for server credentials, tokens,
    // file-source passwords, backup passphrases and other secret settings fields.
    val passwordField = visualTransformation is PasswordVisualTransformation
    var passwordVisible by remember { mutableStateOf(false) }
    val effectiveVisualTransformation = if (passwordField && passwordVisible) {
        VisualTransformation.None
    } else {
        visualTransformation
    }
    val visibilityContentDescription = stringResource(
        if (passwordVisible) R.string.settings_hide_secret else R.string.settings_show_secret,
    )
    val uiSounds = LocalUiSoundPlayer.current
    val togglePasswordVisibility = rememberUiSoundAction { passwordVisible = !passwordVisible }

    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current && !requireExplicitEditing) {
        androidx.compose.material3.OutlinedTextField(
            value = displayedEditorValue, onValueChange = onEditorValueChange, label = if (label.isEmpty()) null else ({ androidx.compose.material3.Text(label) }),
            modifier = modifier, singleLine = singleLine, visualTransformation = effectiveVisualTransformation,
            keyboardOptions = keyboardOptions, shape = RoundedCornerShape(LocalUiTokens.current.controlRadius),
            trailingIcon = if (passwordField) {
                {
                    androidx.compose.material3.IconButton(onClick = togglePasswordVisibility) {
                        androidx.compose.material3.Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = visibilityContentDescription,
                        )
                    }
                }
            } else null,
        )
        return
    }

    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editorHadFocus by remember { mutableStateOf(false) }
    var ownedInputSession by remember { mutableStateOf(false) }
    var editingBackArmed by remember { mutableStateOf(false) }
    var visibilityFocused by remember { mutableStateOf(false) }
    // The OK press that opens a page/dialog can move focus here between its KeyDown and KeyUp.
    // Only a KeyDown received by this field arms activation, so that stray KeyUp is ignored.
    var activationArmed by remember { mutableStateOf(false) }
    val containerFocusRequester = remember { FocusRequester() }
    val editFocusRequester = remember { FocusRequester() }
    val visibilityFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val uiScale = rememberUhdScale()
    val tokens = LocalUiTokens.current
    val visibilityShape = remember(tokens.controlRadius) { RoundedCornerShape(tokens.controlRadius) }

    TvTextFieldFocus.Report(focused || editing)
    val beginEditing = rememberUiSoundAction { editing = true }
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, editing) {
        if (!editing) imeWasVisible = false
        else if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) {
            // Android may consume Back in the IME before Compose sees it.
            editing = false
            runCatching { containerFocusRequester.requestFocus() }
        }
    }
    BackHandler(enabled = editing) {
        val close = { editing = false; runCatching { containerFocusRequester.requestFocus() }; Unit }
        if (uiSounds != null) uiSounds.perform(com.ambient.tv.ui.sound.UiSound.BACK, close) else close()
    }
    // Browse mode contains text only and cannot create an input session.
    // Mount the editor only after OK/Enter or a tap; handle its keys before the focus target.
    LaunchedEffect(editing) {
        if (editing) {
            ownedInputSession = true
            runCatching { editFocusRequester.requestFocus() }
            delay(40)
            if (editorHadFocus) keyboardController?.show()
        } else {
            editorHadFocus = false
            // Newly composed browse fields must not dismiss another field's keyboard.
            if (ownedInputSession) keyboardController?.hide()
            ownedInputSession = false
        }
    }

    val textFieldShape = remember(tokens.controlRadius) { RoundedCornerShape(tokens.controlRadius) }

    Box(
        modifier = modifier
            .focusOnTvPointer(pointerIcon = androidx.compose.ui.input.pointer.PointerIcon.Text)
            .focusRequester(containerFocusRequester)
            .semantics { onClick { beginEditing(); true } }
            .pointerInput(editing) {
                if (!editing) detectTapGestures(onTap = {
                    runCatching { containerFocusRequester.requestFocus() }
                    beginEditing()
                })
            }
            .onFocusChanged { state ->
                if (state.isFocused) uiSounds?.playFocus()
                focused = state.hasFocus
                if (!state.hasFocus) activationArmed = false
                // Leaving the field (and its eye) masks the secret again, so it is not left on
                // screen in clear text while the user moves on.
                if (!state.hasFocus) passwordVisible = false
            }
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                if (event.key != Key.Back || (!editing && !editingBackArmed)) false
                else {
                    if (event.type == KeyEventType.KeyDown) editingBackArmed = true
                    if (event.type == KeyEventType.KeyUp && editingBackArmed) {
                        editingBackArmed = false
                        keyboardController?.hide()
                        editing = false
                        runCatching { containerFocusRequester.requestFocus() }
                    }
                    true
                }
            }
            .onPreviewKeyEvent { event ->
                val activationKey = event.key == Key.Enter ||
                    event.key == Key.NumPadEnter ||
                    event.key == Key.DirectionCenter ||
                    event.key == Key.Spacebar
                val navigationDirection = when (event.key) {
                    Key.DirectionUp -> FocusDirection.Up.takeIf { singleLine }
                    Key.DirectionDown -> FocusDirection.Down.takeIf { singleLine }
                    else -> null
                }
                when {
                    passwordField && visibilityFocused && event.key == Key.DirectionLeft -> {
                        // The eye sits inside the field's own focus target; a plain focus search
                        // from it skips the field (it encloses the eye) and leaves the page.
                        if (event.type == KeyEventType.KeyDown) runCatching { containerFocusRequester.requestFocus() }
                        true
                    }
                    passwordField && visibilityFocused && activationKey -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            togglePasswordVisibility()
                        }
                        true
                    }
                    !editing && passwordField && event.key == Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) {
                            runCatching { visibilityFocusRequester.requestFocus() }
                        }
                        true
                    }
                    !editing && activationKey -> {
                        when (event.type) {
                            KeyEventType.KeyDown -> if (event.nativeKeyEvent.repeatCount == 0) activationArmed = true
                            KeyEventType.KeyUp -> if (activationArmed) { activationArmed = false; beginEditing() }
                        }
                        true
                    }
                    editing && event.key == Key.DirectionCenter -> {
                        // Re-open the IME after it was dismissed with Back while still editing.
                        if (event.type == KeyEventType.KeyDown) activationArmed = true
                        if (event.type == KeyEventType.KeyUp && activationArmed) { activationArmed = false; uiSounds?.playSelect(); keyboardController?.show() }
                        true
                    }
                    editing && navigationDirection != null -> {
                        if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(navigationDirection)
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .sizeIn(minHeight = 48.dp * uiScale)
            // While the eye has focus it carries the highlight alone, not field and eye at once.
            .appControlSurface(focused && !visibilityFocused, shape = textFieldShape)
            .padding(horizontal = 16.dp * uiScale, vertical = 12.dp * uiScale),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f),
            ) {
                if (editing) androidx.compose.foundation.text.BasicTextField(
                    value = displayedEditorValue,
                    onValueChange = onEditorValueChange,
                    enabled = editing,
                    singleLine = singleLine,
                    visualTransformation = effectiveVisualTransformation,
                    keyboardOptions = keyboardOptions,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(editFocusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) editorHadFocus = true
                            else if (editorHadFocus) editing = false
                        },
                    decorationBox = { inner ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                ) else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            effectiveVisualTransformation.filter(androidx.compose.ui.text.AnnotatedString(value)).text.let {
                                if (it.isEmpty()) androidx.compose.ui.text.AnnotatedString(" ") else it
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        )
                    }
                }
            }
            if (passwordField) {
                androidx.compose.material3.IconButton(
                    onClick = togglePasswordVisibility,
                    modifier = Modifier
                        .size(48.dp * uiScale)
                        .appControlSurface(visibilityFocused, shape = visibilityShape)
                        .focusRequester(visibilityFocusRequester)
                        .onFocusChanged { visibilityFocused = it.isFocused; if (it.isFocused) uiSounds?.playFocus() },
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = visibilityContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp * uiScale),
                    )
                }
            }
        }
    }
}

@Composable
fun TvSettingRow(
    title: String,
    body: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Optional identity of the item this switch shows or hides; the row owns its interaction. */
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val uiSounds = LocalUiSoundPlayer.current
    val toggle = rememberUiSoundAction(enabled = enabled,
        kind = if (checked) com.ambient.tv.ui.sound.UiSound.TOGGLE_OFF else com.ambient.tv.ui.sound.UiSound.TOGGLE_ON,
    ) { onCheckedChange(!checked) }
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        val scheme = androidx.compose.material3.MaterialTheme.colorScheme
        val interactions = remember { MutableInteractionSource() }
        val focused by interactions.collectIsFocusedAsState()
        val pressed by interactions.collectIsPressedAsState()
        val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
        androidx.compose.material3.ListItem(
            headlineContent = { androidx.compose.material3.Text(title) },
            supportingContent = body?.let { description -> { androidx.compose.material3.Text(description) } },
            leadingContent = leadingContent,
            trailingContent = {
                androidx.compose.material3.Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = scheme.onPrimary,
                        checkedTrackColor = scheme.primary,
                        checkedBorderColor = scheme.primary,
                        uncheckedThumbColor = scheme.onSurface,
                        uncheckedTrackColor = scheme.surfaceVariant,
                        uncheckedBorderColor = scheme.onSurfaceVariant,
                        disabledCheckedThumbColor = scheme.onPrimary.copy(alpha = 0.55f),
                        disabledCheckedTrackColor = scheme.primary.copy(alpha = 0.45f),
                        disabledCheckedBorderColor = scheme.primary.copy(alpha = 0.45f),
                        disabledUncheckedThumbColor = scheme.onSurface.copy(alpha = 0.55f),
                        disabledUncheckedTrackColor = scheme.surfaceVariant.copy(alpha = 0.55f),
                        disabledUncheckedBorderColor = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                    ),
                )
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = modifier
                .background(scheme.surface)
                .appControlSurface(focused, enabled = enabled, shape = shape, pressed = pressed)
                .activateOnTvKey(enabled, onClick = toggle)
                .clickable(enabled = enabled, interactionSource = interactions, indication = null) { toggle() },
        )
        return
    }

    var focused by remember { mutableStateOf(false) }
    val uiScale = rememberUhdScale()
    val tokens = LocalUiTokens.current
    val settingRowShape = remember(tokens.controlRadius) { RoundedCornerShape(tokens.controlRadius) }
    Surface(
        onClick = toggle,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        border = noSurfaceBorder(),
        shape = ClickableSurfaceDefaults.shape(shape = settingRowShape),
        modifier = modifier
            .padding(2.dp)
            .activateOnTvKey(enabled, onClick = toggle)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) uiSounds?.playFocus()
            }
            // Stays focusable so D-pad users can still read why the row is locked.
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .appControlSurface(focused, selected = checked, shape = settingRowShape)
                .padding(horizontal = 20.dp * uiScale, vertical = 16.dp * uiScale),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingContent?.let { leading ->
                Box(Modifier.size(20.dp * uiScale), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(tokens.itemGap))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                body?.takeIf { it.isNotBlank() && (focused || !enabled) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp * uiScale))
            Box(
                modifier = Modifier
                    .width(46.dp * uiScale)
                    .height(26.dp * uiScale)
                    .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                    .border(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                    .padding(4.dp * uiScale),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp * uiScale)
                        .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .background(if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(999.dp)),
                )
            }
        }
    }
}

/** The one progress indicator for buttons whose action is running; it takes the icon's slot. */
@Composable
private fun ButtonProgress(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.material3.CircularProgressIndicator(
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        strokeWidth = 2.5.dp,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        modifier = modifier.padding(2.dp),
    )
}

/**
 * The one spinner for a whole screen or content area that is loading: accent coloured, so it
 * follows the user's accent setting on every page and in every player.
 */
@Composable
fun FullscreenSpinner(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    androidx.compose.material3.CircularProgressIndicator(
        color = accent,
        trackColor = accent.copy(alpha = 0.18f),
        strokeWidth = 3.dp,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        modifier = modifier.size(com.ambient.tv.ui.mobile.deviceDp(40.dp, 32.dp)),
    )
}

/**
 * For several buttons sharing one busy flag: remembers which one started the work, so only that
 * button shows [TvActionButton]'s `loading` state while the others are merely disabled.
 * Usage: `var pending by rememberPendingAction(busy)`, set `pending = key` in the button's onClick
 * and pass `loading = busy && pending == key`.
 */
@Composable
fun rememberPendingAction(busy: Boolean): androidx.compose.runtime.MutableState<Any?> {
    val pending = remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(busy) { if (!busy) pending.value = null }
    return pending
}

/**
 * Shared dark action surface with the same stable light content as [TvChip]. [prominent]
 * gives a primary action a stronger accent tint without inverting its text on focus changes.
 *
 * [loading] marks this button's own action as running: it keeps its normal (not dimmed) look and
 * its focus, ignores clicks and shows a spinner in the icon slot. Pass it instead of disabling a
 * button while its work runs; [enabled] stays for buttons blocked by something else.
 */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    fillWidth: Boolean = false,
    loading: Boolean = false,
    prominent: Boolean = selected,
) {
    val visuallyEnabled = enabled || loading
    val clickable = enabled && !loading
    val uiSounds = LocalUiSoundPlayer.current
    val activate = rememberUiSoundAction(enabled = clickable, onClick = onClick)
    // An empty [text] with an [icon] draws a square icon button.
    val iconOnly = text.isEmpty() && icon != null
    val scheme = MaterialTheme.colorScheme
    val foreground = if (visuallyEnabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = .55f)
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) {
        val interactions = remember { MutableInteractionSource() }
        val focused by interactions.collectIsFocusedAsState()
        val pressed by interactions.collectIsPressedAsState()
        val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
        ProvideControlContent(foreground) {
            androidx.compose.material3.FilledTonalButton(
                onClick = activate, enabled = visuallyEnabled,
                modifier = (if (fillWidth) modifier.fillMaxWidth() else modifier).sizeIn(minHeight = 48.dp)
                    .appControlSurface(focused, selected, visuallyEnabled, shape, prominent = prominent, pressed = pressed,
                        selectionIndicator = if (iconOnly) SelectionIndicator.DOT else SelectionIndicator.NONE),
                shape = shape,
                interactionSource = interactions,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = foreground,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = foreground,
                ),
            ) {
                if (loading) ButtonProgress(foreground, Modifier.padding(end = 6.dp).size(20.dp))
                else if (icon != null) androidx.compose.material3.Icon(icon, null, Modifier.size(20.dp).padding(end = if (text.isEmpty()) 0.dp else 4.dp))
                if (text.isNotEmpty()) androidx.compose.material3.Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }

    var focused by remember { mutableStateOf(false) }
    var keyPressed by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val pointerPressed by interactions.collectIsPressedAsState()
    val uiScale = rememberUhdScale()
    val tokens = LocalUiTokens.current
    // One shared geometry for shadow, background and border. Do not clip the border
    // to the same outer path: anti-aliased edge pixels can otherwise be cut off at
    // rounded corners even when the nominal corner radius is identical.
    val buttonShape = remember(tokens.controlRadius) { RoundedCornerShape(tokens.controlRadius) }
    Surface(
        onClick = activate,
        enabled = visuallyEnabled,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        border = noSurfaceBorder(),
        interactionSource = interactions,
        // Surface itself clips its slot. Matching its interaction shape to the frame avoids
        // truncating anti-aliased border pixels at the rounded corners.
        shape = ClickableSurfaceDefaults.shape(shape = buttonShape),
        modifier = modifier
            .dialogButtonFocus(visuallyEnabled || focused)
            .sizeIn(minHeight = 48.dp * uiScale)
            .padding(2.dp)
            .menuFocusMotion(focused)
            // Do not evict DPAD focus when an action temporarily disables itself while its
            // asynchronous work is running. Disabled actions which were not already focused
            // are still skipped, so they cannot become navigation dead ends.
            .focusProperties { canFocus = visuallyEnabled || focused }
            .onPreviewKeyEvent { event ->
                val activationKey = event.key == Key.Enter ||
                    event.key == Key.NumPadEnter ||
                    event.key == Key.DirectionCenter ||
                    event.key == Key.Spacebar
                if (!activationKey) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (clickable && !keyPressed && event.nativeKeyEvent.repeatCount == 0) {
                            keyPressed = true
                            activate()
                        }
                    }
                    KeyEventType.KeyUp -> keyPressed = false
                    else -> Unit
                }
                true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) uiSounds?.playFocus()
                else keyPressed = false
            },
    ) {
        Row(
            modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .appControlSurface(focused, selected, visuallyEnabled, buttonShape,
                    prominent = prominent, pressed = keyPressed || pointerPressed,
                    selectionIndicator = if (iconOnly) SelectionIndicator.DOT else SelectionIndicator.NONE)
                .padding(horizontal = (if (iconOnly) 11.dp else 16.dp) * uiScale, vertical = 12.dp * uiScale),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Spinner and icon share one slot. Without an icon the slot grows in smoothly, so
            // the label and neighbouring controls slide instead of jumping. The slot stays close
            // to the label's line height, so icon buttons match text-only buttons in a row.
            AnimatedVisibility(
                visible = icon != null || loading,
                enter = fadeIn(tween(tokens.animationFastMs)) + expandHorizontally(tween(tokens.animationFastMs)),
                exit = fadeOut(tween(tokens.animationFastMs)) + shrinkHorizontally(tween(tokens.animationFastMs)),
            ) {
                Crossfade(loading, animationSpec = tween(tokens.animationFastMs), label = "action progress") { showProgress ->
                    Box(Modifier.padding(end = if (iconOnly) 0.dp else 8.dp * uiScale).size(20.dp * uiScale), contentAlignment = Alignment.Center) {
                        if (showProgress) {
                            ButtonProgress(foreground, Modifier.fillMaxSize())
                        } else if (icon != null) {
                            androidx.compose.material3.Icon(imageVector = icon, contentDescription = null, tint = foreground, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (!iconOnly) Text(text = text, color = foreground, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Shared focus contract for every modal, dialog and side panel (and long lists):
 * 1. [initialFocus]: once shown, focus enters the group's first focusable item.
 * 2. D-pad Down on the last reachable item wraps back to the first item; [scrollToTop] runs first
 *    so a scrolled list/column brings its top back into view.
 * 3. D-pad Up on the first item wraps to the last one, so a vertical list has no dead end in
 *    either direction. Vertical focus search stays inside this group; external actions remain
 *    reachable laterally through [footerFocus] or the owning screen's navigation.
 *    [allowUpExit] lets page grids reach controls above them before wrapping.
 *    Disabling [wrapVerticalFocus] consumes Up/Down at the group's endpoints without leaving it.
 *    [scrollToEnd] composes a lazy tail and reveals a regular scrolling column's last row.
 *    [wrapOnRepeat] false: a held key (repeatCount > 0) stops at the endpoint; only a fresh
 *    press wraps, so holding Down does not circle through a short list endlessly.
 * 4. [footerFocus]: D-pad Right with nothing further right jumps to the footer's last button
 *    (Back/Close/Cancel). The footer group should cancel Right exits so the walk stops there.
 * Uses [onKeyEvent], so children that handle Up/Down/Right themselves (open selectors, text
 * fields, steppers) keep priority.
 *
 * [composed] on purpose: a Dialog's `modifier` argument is built in the page's composition, but the
 * group lives in the dialog window. Resolved at the call site, [LocalFocusManager] would be the
 * page's and every focus move would drive the page behind the dialog instead.
 */
fun Modifier.tvModalFocus(
    initialFocus: Boolean = true,
    scrollToTop: (suspend () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    footerFocus: FocusRequester? = null,
    scrollToEnd: (suspend () -> Unit)? = null,
    focusActionsOnRight: Boolean = false,
    reverseTraversal: Boolean = false,
    scrollPastEmptyEdge: (suspend (last: Boolean) -> Boolean)? = null,
    allowUpExit: Boolean = false,
    wrapVerticalFocus: Boolean = true,
    wrapOnRepeat: Boolean = true,
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val uiSounds = LocalUiSoundPlayer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ownRequester = remember { FocusRequester() }
    val first = focusRequester ?: ownRequester
    val endpoint = remember { FocusRequester() }
    // Park outside the content group to release the old lazy item's focus pin before scrolling.
    // The inner group then selects its own endpoint without a window-wide focus search.
    val parking = remember { mutableStateOf(false) }
    var blockExit by remember { mutableStateOf(false) }
    var changingFocus by remember { mutableStateOf(false) }
    var wrapping by remember { mutableStateOf(false) }
    var wrapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val actionFocus = LocalScrollListActionFocus.current
    val actions = LocalScrollListActions.current
    val view = androidx.compose.ui.platform.LocalView.current
    fun focusActions(): Boolean {
        val target = footerFocus ?: actionFocus
        return if (target != null) runCatching { target.requestFocus() }.getOrDefault(false)
        else actions?.requestFocus(view) ?: false
    }
    fun focusUpExit(): Boolean {
        val target = footerFocus ?: actionFocus
        return if (target != null) runCatching { target.requestFocus() }.getOrDefault(false)
        else actions?.requestUpFocus(view) ?: false
    }
    fun moveInside(direction: FocusDirection): Boolean {
        blockExit = true
        changingFocus = true
        return try { focusManager.moveFocus(direction) } finally {
            changingFocus = false
            blockExit = false
        }
    }
    suspend fun focusEndpoint(last: Boolean): Boolean {
        parking.value = true
        try {
            androidx.compose.runtime.withFrameNanos { }
            changingFocus = true
            val parked = try { first.requestFocus() } finally { changingFocus = false }
            if (!parked) return false
            if (last) scrollToEnd?.invoke() else scrollToTop?.invoke()
            // Request a child directly from the opposite visual edge. Global Next/Previous
            // can wrap through the window and its page chrome when no child is mounted.
            do {
                androidx.compose.runtime.withFrameNanos { }
                if (last != reverseTraversal) {
                    changingFocus = true
                    blockExit = true
                    val entered = try {
                        endpoint.requestFocus(if (last) FocusDirection.Up else FocusDirection.Down)
                    } finally {
                        changingFocus = false
                        blockExit = false
                    }
                    if (entered) {
                        // Geometry gets us near the end; reading order includes controls outside
                        // that vertical beam and every enabled control in the final nested row.
                        while (moveInside(FocusDirection.Next)) kotlinx.coroutines.yield()
                        return true
                    }
                } else if (moveInside(FocusDirection.Next)) return true
                // A whole endpoint viewport may consist of labels/disabled rows. Compose
                // cannot search beyond bounds without a focused lazy child: move inward
                // without skipping any item and retry until a reachable endpoint is found.
            } while (scrollPastEmptyEdge?.invoke(last) == true)
            return false
        } finally {
            parking.value = false
        }
    }
    if (initialFocus) {
        LaunchedEffect(Unit) {
            // Dialog windows and slide-in panels attach a few frames late; retry briefly.
            repeat(10) { if (focusEndpoint(last = false)) return@LaunchedEffect }
        }
    }
    Modifier
        // A permanent boundary also contains the parking target itself. Otherwise Next
        // from an empty endpoint viewport invokes Compose's root-level Tab wrap and can
        // leave the list without calling the parking target's own onExit.
        .focusProperties {
            canFocus = false
            onExit = { if (blockExit) cancelFocusChange() }
        }
        .focusTarget()
        .focusRequester(first)
        .focusProperties {
            canFocus = parking.value
            onExit = { if (blockExit) cancelFocusChange() }
        }
        .onFocusChanged {
            // Compose reports a transient Inactive when moving from a child to the parking
            // target (and back). Only an external focus departure cancels an ongoing wrap.
            if (!it.hasFocus && !changingFocus) wrapJob?.cancel()
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (wrapping && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) return@onKeyEvent true
            if (event.key == Key.DirectionRight && footerFocus != null) {
                if (focusManager.moveFocus(FocusDirection.Right)) {
                    uiSounds?.playFocus()
                    return@onKeyEvent true
                }
                if (!runCatching { footerFocus.requestFocus() }.getOrDefault(false)) return@onKeyEvent false
                uiSounds?.playFocus()
                // Entry lands on the footer's first button; walk to its last (Back sits rightmost).
                repeat(8) { if (!focusManager.moveFocus(FocusDirection.Right)) return@onKeyEvent true }
                return@onKeyEvent true
            }
            if (event.key == Key.DirectionRight && focusActionsOnRight) {
                if (focusManager.moveFocus(FocusDirection.Right) ||
                    focusActions()) {
                    uiSounds?.playFocus()
                    return@onKeyEvent true
                }
                return@onKeyEvent false
            }
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> return@onKeyEvent false
            }
            if (moveInside(direction)) {
                uiSounds?.playFocus()
                return@onKeyEvent true
            }
            val held = !wrapOnRepeat && event.nativeKeyEvent.repeatCount > 0
            wrapping = true
            wrapJob = scope.launch {
                try {
                    // A focused lazy row can arrive before the next viewport is laid out.
                    // Do not interpret that transient edge as the end of the whole list.
                    androidx.compose.runtime.withFrameNanos { }
                    if (moveInside(direction)) {
                        uiSounds?.playFocus()
                        return@launch
                    }
                    while (scrollPastEmptyEdge?.invoke(direction == FocusDirection.Up) == true) {
                        androidx.compose.runtime.withFrameNanos { }
                        if (moveInside(direction)) {
                            uiSounds?.playFocus()
                            return@launch
                        }
                    }
                    if (allowUpExit && direction == FocusDirection.Up && focusUpExit()) return@launch
                    if (!wrapVerticalFocus || held) return@launch
                    if (allowUpExit && direction == FocusDirection.Up && footerFocus == null && actionFocus == null &&
                        focusManager.moveFocus(FocusDirection.Up)) {
                        uiSounds?.playFocus()
                        return@launch
                    }
                    if (focusEndpoint(last = direction == FocusDirection.Up) || focusActions()) uiSounds?.playFocus()
                } finally { wrapping = false }
            }
            true
        }
        // The key handler precedes the parking target so held D-pad keys are also handled
        // during the frames in which the group itself owns focus.
        .focusTarget()
        .focusRequester(endpoint)
        .focusGroup()
}

/**
 * Vertical wrap for a long list: Down on the last row continues at the first, Up on the first row
 * continues at the last, even with a header above it. Uses [onKeyEvent], so children that handle Up/Down
 * themselves (fields, selectors) keep priority; only an unconsumed key with nowhere to go wraps.
 */
fun Modifier.wrapFocusDownToTop(listState: androidx.compose.foundation.lazy.LazyListState): Modifier = composed {
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) Modifier else Modifier.tvModalFocus(
        initialFocus = false,
        scrollToTop = { if (listState.layoutInfo.totalItemsCount > 0) listState.scrollToItem(0) },
        scrollToEnd = { if (listState.layoutInfo.totalItemsCount > 0) listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) },
        scrollPastEmptyEdge = { listState.scrollPastUnfocusableEdge(it) },
    )
}

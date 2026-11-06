package com.ambient.tv.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.DeviceActionRow

@Composable
internal fun SegmentSkipOverlay(
    metadata: PlaybackMetadata,
    mediaKey: String,
    navigation: PlaybackNavigationState,
    onSeek: ((Long) -> Unit)?,
    nextUpVisible: Boolean,
    onPlayNext: ((Boolean) -> Unit)?,
    canAutoAdvance: Boolean,
    creditsHandledByNextUp: Boolean = false,
    content: @Composable () -> Unit,
) {
    val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val controls = LocalSegmentSkipControls.current
    val policy = remember(mediaKey) { SegmentSkipPolicy() }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember(mediaKey) { mutableStateOf<com.ambient.tv.domain.model.PlaybackSegment?>(null) }
    val currentSeek by rememberUpdatedState(onSeek)
    val valid = snapshot.active && snapshot.metadata?.route == metadata.route && metadata.hasVideo && !metadata.isLive
    val position = snapshot.status.positionMs + metadata.timelineOffsetMs
    val overlayContext = androidx.compose.ui.platform.LocalContext.current
    val sponsorModes = remember(mediaKey) { videoImagePreferences(overlayContext).youtubeSponsorBlockEffectiveModes }
    fun mode(segment: com.ambient.tv.domain.model.PlaybackSegment): String = when (segment.type) {
        "intro" -> metadata.introSkipMode
        "credits" -> metadata.creditsSkipMode
        // SponsorBlock "auto" runs exclusively in PlaybackService (also in the background);
        // only categories set to "button" get the overlay's button here.
        else -> com.ambient.tv.youtube.sponsorCategoryOf(segment.type)
            ?.takeIf { sponsorModes[it] == "button" }?.let { "button" } ?: "off"
    }
    val segments = navigation.timeline.segments.filter {
        mode(it) != "off" && (it.endMs >= 0 || onPlayNext != null)
    }
    val segment = policy.active(segments, position).takeIf { valid }
        ?.takeUnless { creditsHandledByNextUp && it.type == "credits" }
    fun skip(target: com.ambient.tv.domain.model.PlaybackSegment, automatic: Boolean = false) {
        policy.handled(target)
        dismissed = target
        navigation.interact()
        if (target.endMs < 0) {
            onPlayNext?.invoke(automatic)
            return
        }
        val duration = metadata.durationMs.takeIf { it > 0 }
            ?: snapshot.status.durationMs.takeIf { it > 0 }?.plus(metadata.timelineOffsetMs) ?: 0L
        val terminal = duration > 0 && target.endMs >= duration - 750L
        // A manual final-credits skip is an explicit Next request. Auto SponsorBlock must
        // still respect Next Up / still-watching confirmation and never force that handoff.
        if (terminal && !automatic && onPlayNext != null) {
            onPlayNext.invoke(false)
            return
        }
        val seekTarget = safeSegmentSkipTarget(target.endMs, position, duration) ?: return
        currentSeek?.invoke(seekTarget)
            ?: BackgroundPlaybackSession.seekTo((seekTarget - metadata.timelineOffsetMs).coerceAtLeast(0))
    }
    LaunchedEffect(mediaKey, valid, position, snapshot.status.playing, segments, metadata.introSkipMode, metadata.creditsSkipMode, canAutoAdvance, creditsHandledByNextUp) {
        if (valid) {
            val duration = metadata.durationMs.takeIf { it > 0 }
                ?: snapshot.status.durationMs.takeIf { it > 0 }?.plus(metadata.timelineOffsetMs) ?: 0L
            val finalCredits = finalCreditsAt(position, duration, segments)
            val automatic = segments.filter {
                val nextUpOwnsEnd = creditsHandledByNextUp && it == finalCredits &&
                    metadata.nextUpConfig.creditsDelaySeconds > 0 &&
                    metadata.nextUpConfig.autoplay && metadata.nextUpConfig.showOverlay
                !nextUpOwnsEnd && mode(it) == "auto" && (it.endMs >= 0 || canAutoAdvance)
            }
            policy.automaticTarget(automatic, position, snapshot.status.playing)?.let { skip(it, automatic = true) }
        }
    }
    // Leaving the segment re-arms the manual button, but never automatic skipping.
    LaunchedEffect(segment) { if (segment != dismissed) dismissed = null }
    // SponsorBlock never displays a manual button; the service owns its automatic seek.
    val visible = segment != null && segment != dismissed &&
        !navigation.skipOverlayBlocked && !navigation.chaptersOpen && !nextUpVisible
    // The shared OSD header reserves an action slot only while this transient prompt exists.
    SideEffect { navigation.skipActionVisible = visible }
    DisposableEffect(navigation) {
        onDispose {
            navigation.skipActionVisible = false
            navigation.skipActionSize = IntSize.Zero
            navigation.skipContextBoundsInRoot = null
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var viewportOrigin by remember { mutableStateOf(Offset.Zero) }
    val actionSize = navigation.skipActionSize
    val ready = viewport.width > 0 && viewport.height > 0 && actionSize.width > 0 && actionSize.height > 0
    val density = LocalDensity.current
    val marginPx = with(density) { (if (handheld) 12.dp else 32.dp).roundToPx() }
    val osdVisible = navigation.chromeVisible && LocalPlayerChromeState.current?.osd?.show != false
    val target = skipOverlayPosition(
        viewport, actionSize, viewportOrigin, navigation.skipContextBoundsInRoot,
        contextVisible = visible && osdVisible, marginPx = marginPx,
    )
    // Compose the row once, not a floating copy plus an inline copy: this preserves D-pad
    // focus and avoids double clicks while its measured destination changes.
    val animatedPosition = if (visible && ready) animateIntOffsetAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "Skip action floats into OSD",
    ).value else IntOffset.Zero
    val restorePlayerFocus = !visible && focused
    LaunchedEffect(visible) {
        if (restorePlayerFocus && !nextUpVisible) {
            focused = false
            navigation.returnToControls()
        }
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { if (viewport != it) viewport = it }
            .onGloballyPositioned {
                val origin = it.positionInRoot()
                if (viewportOrigin != origin) viewportOrigin = origin
            },
    ) {
        content()
        // Register after the player's Back handlers: dismiss the skip action before
        // leaving playback or closing the OSD, even if its button lost focus.
        TransientPlayerActionFocus(
            visible = visible,
            requester = focus,
            focusIdentity = segment,
            hasActionFocus = focused,
            onDismiss = {
                dismissed = segment
                navigation.interact()
                navigation.returnToControls()
            },
        )
        if (visible) {
            val sponsorCategory = com.ambient.tv.youtube.sponsorCategoryOf(segment.type)
            val actionText = stringResource(when {
                segment.endMs == -1L -> R.string.player_credits_next
                segment.type == "credits" -> R.string.player_skip_credits
                sponsorCategory != null -> sponsorSkipLabel(sponsorCategory)
                else -> R.string.player_skip_intro
            })
            val setAutomatic = when (segment.type) {
                "intro" -> controls?.onIntroModeChanged?.takeIf { controls.introMode == "button" }
                "credits" -> controls?.onCreditsModeChanged?.takeIf { controls.creditsMode == "button" }
                else -> null
            }
            // The same focus group is placed at either measured OSD context coordinates
            // or a safe bottom-right floating position. Never lay out two action instances.
            val focusBehavior = if (handheld) Modifier.fillMaxWidth() else Modifier
            Box(
                modifier = Modifier.align(Alignment.TopStart)
                    .then(if (handheld) Modifier.fillMaxWidth() else Modifier)
                    .offset { animatedPosition }
                    .onSizeChanged {
                        if (navigation.skipActionSize != it) navigation.skipActionSize = it
                    }
                    .graphicsLayer { alpha = if (ready) 1f else 0f }
                    .zIndex(PLAYER_SETTINGS_PANEL_Z_INDEX + 1f)
                    .onFocusChanged { focused = it.hasFocus }
                    .focusProperties {
                        onExit = {
                            when (requestedFocusDirection) {
                                FocusDirection.Left, FocusDirection.Right,
                                FocusDirection.Up, FocusDirection.Down -> cancelFocusChange()
                                else -> Unit
                            }
                        }
                    }
                    .focusGroup(),
            ) {
                DeviceActionRow(
                    modifier = if (handheld) Modifier.fillMaxWidth().padding(horizontal = 12.dp) else Modifier,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    stack = handheld,
                ) {
                    setAutomatic?.let { changeMode ->
                        TvActionButton(
                            stringResource(if (segment.type == "intro") R.string.player_always_skip_intros else R.string.player_always_skip_credits),
                            {
                                changeMode("auto")
                                skip(segment)
                                navigation.returnToControls()
                            },
                            focusBehavior,
                            fillWidth = handheld,
                        )
                    }
                    TvActionButton(actionText, {
                        skip(segment)
                        navigation.returnToControls()
                    }, focusBehavior.focusRequester(focus), fillWidth = handheld)
                }
            }
        }
    }
}

/** "Skip sponsor", "Skip intro", … for a SponsorBlock category. */
internal fun sponsorSkipLabel(category: String): Int = when (category) {
    "sponsor" -> R.string.player_skip_sponsor
    "selfpromo" -> R.string.player_skip_selfpromo
    "interaction" -> R.string.player_skip_interaction
    "intro" -> R.string.player_skip_intro
    "outro" -> R.string.player_skip_outro
    "preview" -> R.string.player_skip_preview
    "filler" -> R.string.player_skip_filler
    "music_offtopic" -> R.string.player_skip_music_offtopic
    else -> R.string.player_skip_sponsor
}

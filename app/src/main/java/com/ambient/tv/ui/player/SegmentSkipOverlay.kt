package com.ambient.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
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
    val segments = navigation.timeline.segments.filter {
        (if (it.type == "intro") metadata.introSkipMode else metadata.creditsSkipMode) != "off" &&
            (it.endMs >= 0 || onPlayNext != null)
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
        currentSeek?.invoke(target.endMs)
            ?: BackgroundPlaybackSession.seekTo((target.endMs - metadata.timelineOffsetMs).coerceAtLeast(0))
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
                val mode = if (it.type == "intro") metadata.introSkipMode else metadata.creditsSkipMode
                !nextUpOwnsEnd && mode == "auto" && (it.endMs >= 0 || canAutoAdvance)
            }
            policy.automaticTarget(automatic, position, snapshot.status.playing)?.let { skip(it, automatic = true) }
        }
    }
    // Leaving the segment re-arms the manual button, but never automatic skipping.
    LaunchedEffect(segment) { if (segment != dismissed) dismissed = null }
    val visible = segment != null && segment != dismissed && !navigation.skipOverlayBlocked && !navigation.chaptersOpen && !nextUpVisible
    val restorePlayerFocus = !visible && focused
    LaunchedEffect(visible) {
        if (restorePlayerFocus && !nextUpVisible) {
            focused = false
            navigation.returnToControls()
        }
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (visible && !navigation.chromeVisible && !focused && event.key == Key.DirectionUp) {
            if (event.type == KeyEventType.KeyDown) focus.requestFocus()
            true
        } else false
    }) {
        content()
        // Registered after player Back handlers so Back dismisses this focused action first.
        BackHandler(enabled = visible && focused) {
            dismissed = segment
            navigation.returnToControls()
        }
        if (visible) {
            val actionText = stringResource(when {
                segment.endMs == -1L -> R.string.player_credits_next
                segment.type == "credits" -> R.string.player_skip_credits
                else -> R.string.player_skip_intro
            })
            val setAutomatic = when (segment.type) {
                "intro" -> controls?.onIntroModeChanged?.takeIf { controls.introMode == "button" }
                "credits" -> controls?.onCreditsModeChanged?.takeIf { controls.creditsMode == "button" }
                else -> null
            }
            val focusBehavior = Modifier.onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown) {
                        if (event.type == KeyEventType.KeyDown) {
                            dismissed = segment
                            navigation.returnToControls()
                        }
                        true
                    } else false
                }
            DeviceActionRow(
                modifier = Modifier.align(Alignment.BottomEnd)
                    .then(if (handheld) Modifier.fillMaxWidth() else Modifier)
                    .padding(
                        start = if (handheld) 12.dp else 0.dp,
                        end = if (handheld) 12.dp else 32.dp,
                        bottom = if (navigation.chromeVisible) { if (handheld) 196.dp else 220.dp } else { if (handheld) 12.dp else 32.dp },
                    ),
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
                        focusBehavior.then(if (handheld) Modifier.fillMaxWidth() else Modifier),
                        fillWidth = handheld,
                    )
                }
                TvActionButton(actionText, {
                    skip(segment)
                    navigation.returnToControls()
                }, focusBehavior.focusRequester(focus).then(if (handheld) Modifier.fillMaxWidth() else Modifier), fillWidth = handheld)
            }
        }
    }
}

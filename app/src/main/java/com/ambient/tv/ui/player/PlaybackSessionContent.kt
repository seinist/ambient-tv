package com.ambient.tv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlin.math.ceil

/** Shared title actions for renderer controls and the persistent OSD above their layout. */
internal data class PlaybackItemControls(
    val restart: (() -> Unit)? = null,
    val next: (() -> Unit)? = null,
    /** Other video players may use start-over; YouTube requires an actual history item. */
    val previous: (() -> Unit)? = restart,
    val hasPreviousItem: Boolean = false,
)

internal val LocalPlaybackItemControls = compositionLocalOf<PlaybackItemControls?> { null }

@Composable
fun rememberPlaybackSleepTimer(owner: Any): MutableState<SleepTimerConfig> = remember(owner) {
    object : MutableState<SleepTimerConfig> {
        override var value: SleepTimerConfig
            get() = BackgroundPlaybackSession.sleepTimer
            set(value) {
                BackgroundPlaybackSession.setSleepTimer(value)
            }
        override fun component1() = value
        override fun component2(): (SleepTimerConfig) -> Unit = { value = it }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun PlaybackSessionContent(
    metadata: PlaybackMetadata,
    onStopped: () -> Unit,
    onPlayNext: (NextUpCandidate) -> Unit = {},
    onIntroSkipModeChanged: ((String) -> Unit)? = null,
    onCreditsSkipModeChanged: ((String) -> Unit)? = null,
    onAutoplayChanged: ((Boolean) -> Unit)? = null,
    timelineKey: String = metadata.route,
    timeline: com.ambient.tv.domain.model.PlaybackTimeline = com.ambient.tv.domain.model.PlaybackTimeline(),
    timelineLoader: (suspend () -> com.ambient.tv.domain.model.PlaybackTimeline)? = null,
    onTimelineSeek: ((Long) -> Unit)? = null,
    /** A failed new engine must be reported by its screen, not mistaken for stopping the old one. */
    acquisitionKey: Any = metadata.route,
    onPlayPrevious: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val chrome = LocalPlayerChromeState.current
    var introSkipMode by remember(metadata.route) { mutableStateOf(metadata.introSkipMode) }
    var creditsSkipMode by remember(metadata.route) { mutableStateOf(metadata.creditsSkipMode) }
    var autoplay by remember(metadata.route, metadata.nextUpConfig.autoplay) {
        mutableStateOf(metadata.nextUpConfig.autoplay)
    }
    LaunchedEffect(metadata.introSkipMode) { introSkipMode = metadata.introSkipMode }
    LaunchedEffect(metadata.creditsSkipMode) { creditsSkipMode = metadata.creditsSkipMode }
    fun normalizedSkipMode(value: String) = value.takeIf { it in setOf("off", "button", "auto") } ?: "button"
    val navigation = remember(timelineKey) { PlaybackNavigationState() }
    val segments = navigation.timeline.segments
    val skipControls = SegmentSkipControls(
        introMode = introSkipMode,
        creditsMode = creditsSkipMode,
        onIntroModeChanged = onIntroSkipModeChanged?.let { persist ->
            { value -> normalizedSkipMode(value).let { introSkipMode = it; persist(it) } }
        },
        onCreditsModeChanged = onCreditsSkipModeChanged?.let { persist ->
            { value -> normalizedSkipMode(value).let { creditsSkipMode = it; persist(it) } }
        },
        hasIntro = segments.any { it.type == "intro" },
        // Open-ended credits only matter when there is a next item to hand over to.
        hasCredits = segments.any { it.type == "credits" && (it.endMs >= 0 || metadata.nextUp != null) },
    )
    val activeMetadata = metadata.copy(
        playbackSegments = segments,
        introSkipMode = introSkipMode,
        creditsSkipMode = creditsSkipMode,
        nextUpConfig = metadata.nextUpConfig.copy(autoplay = autoplay),
    )
    SideEffect { BackgroundPlaybackSession.refreshMetadata(activeMetadata) }
    val observedSnapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    // acquire() updates synchronously during composition; collectAsState may still hold the old item.
    val snapshot = remember(observedSnapshot, BackgroundPlaybackSession.activeHandle()) { BackgroundPlaybackSession.snapshot.value }
    var acquired by remember(metadata.route, acquisitionKey) { mutableStateOf(false) }
    var dismissedKey by remember(metadata.route) { mutableStateOf<String?>(null) }
    var handledKey by remember(metadata.route) { mutableStateOf<String?>(null) }
    var previousHandled by remember(metadata.route, snapshot.generation) { mutableStateOf(false) }
    // A leaving navigation destination must not render a handle replaced by the next screen.
    if (acquired && snapshot.active && snapshot.metadata?.route != metadata.route) return
    if (acquired && !snapshot.active) {
        LaunchedEffect(snapshot.generation) {
            val current = BackgroundPlaybackSession.snapshot.value
            if (!current.active && current.generation == snapshot.generation) onStopped()
        }
        return
    }
    val audioSuccessor = metadata.companionNext.takeIf { !metadata.hasVideo && !metadata.isLive }
    val candidate = metadata.nextUp ?: audioSuccessor
    val config = activeMetadata.nextUpConfig
    val candidateKey = candidate?.let { "${it.source}:${it.mediaId}" }
    var knownDurationMs by remember(metadata.route) { mutableStateOf(0L) }
    val runtimeDurationMs = snapshot.takeIf { it.metadata?.route == metadata.route }
        ?.status?.durationMs?.takeIf { it > 0 }?.plus(metadata.timelineOffsetMs)
    val durationMs = metadata.durationMs.takeIf { it > 0 }
        ?: runtimeDurationMs ?: knownDurationMs
    // Some native backends clear their media length before reporting Stopped/EOF.
    // Keep the last known length so the pending Next Up choice survives that event.
    SideEffect { if (durationMs > 0) knownDurationMs = durationMs }
    val positionMs = snapshot.status.positionMs + metadata.timelineOffsetMs
    val timing = nextUpTiming(positionMs, durationMs, navigation.timeline.segments,
        activeMetadata.creditsSkipMode != "off", config.leadSeconds)
    val creditsCountdown = remember(metadata.route) { CreditsCountdown() }
    val finalCredits = finalCreditsAt(positionMs, durationMs, segments)
    val earlyCreditsEnabled = acquired && snapshot.metadata?.route == metadata.route &&
        !snapshot.replacingSource && !LocalPlayerChangingItem.current &&
        !com.ambient.tv.companion.CompanionQueuePlayback.pending && config.enabled && config.showOverlay && config.autoplay &&
        activeMetadata.creditsSkipMode != "off" && !metadata.isLive &&
        BackgroundPlaybackSession.sleepTimer.kind != SleepTimerKind.END_OF_ITEM &&
        candidateKey != dismissedKey && !navigation.skipOverlayBlocked && !navigation.chaptersOpen
    val creditsRemainingMs = remember(positionMs, snapshot.status.playing, finalCredits, earlyCreditsEnabled, config.creditsDelaySeconds) {
        creditsCountdown.update(
            finalCredits.takeIf { earlyCreditsEnabled }, positionMs, snapshot.status.playing,
            config.creditsDelaySeconds,
        )
    }
    val secondsRemaining = ceil((creditsRemainingMs ?: timing.remainingMs) / 1_000.0).toInt()
    // Metadata duration is only a countdown hint. A seek/restart can temporarily leave the
    // old clock at that value; only the current engine may declare an actual EOF.
    val atEnd = snapshot.status.ended
    val stillWatching = config.autoplay &&
        BackgroundPlaybackSession.shouldConfirmNextUp(config.stillWatchingEvery)
    // Content without a known final end (live, open-ended streams) must never offer Next Up.
    val linkCreditsToNextUp = candidate != null && config.enabled && !metadata.isLive &&
        durationMs > 0L &&
        BackgroundPlaybackSession.sleepTimer.kind != SleepTimerKind.END_OF_ITEM
    val queuePending = com.ambient.tv.companion.CompanionQueuePlayback.pending
    val changingItem = LocalPlayerChangingItem.current || snapshot.replacingSource
    val ownsSnapshot = snapshot.metadata?.route == metadata.route
    val canOfferNext = acquired && ownsSnapshot && !changingItem && linkCreditsToNextUp && !queuePending
    val nextUpDue = canOfferNext && !navigation.skipOverlayBlocked && !navigation.chaptersOpen && candidateKey != dismissedKey && candidateKey != handledKey &&
        (atEnd || timing.offer)
    // A hidden overlay still has to ask the still-watching question at the end.
    val showNextUp = nextUpDue && (config.showOverlay || (atEnd && stillWatching))

    // A new title (autoplay, Next Up, Next/Previous, gapless music) shows the OSD briefly once
    // it is ready; the normal auto-hide timeout follows. The chrome outlives items and
    // renderers, so its first title and a renderer switch of the same title stay quiet.
    val titleIdentity = playbackTitleIdentity(metadata.source, metadata.mediaId, metadata.isLive)
    val titleReady = acquired && ownsSnapshot && !changingItem && snapshot.active
    val panelActivity = chrome?.panels?.activity
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val titleOsdBlocked = chrome == null || chrome.panels.panel != com.ambient.tv.ui.common.UnifiedPlayerPanel.NONE ||
        chrome.panels.dialogFocusOwner != null || panelActivity?.blocked == true ||
        panelActivity?.popupWindowFocused == true || !windowInfo.isWindowFocused || showNextUp
    val currentTitleOsdBlocked by androidx.compose.runtime.rememberUpdatedState(titleOsdBlocked)
    LaunchedEffect(titleIdentity, titleReady) {
        if (chrome == null || titleIdentity == null || !titleReady) return@LaunchedEffect
        val previous = chrome.readyTitleIdentity
        chrome.readyTitleIdentity = titleIdentity
        if (titleChangeRevealsOsd(previous, titleIdentity, titleReady, currentTitleOsdBlocked)) chrome.osd.reveal()
    }

    var wasNextUpVisible by remember(timelineKey) { mutableStateOf(false) }
    LaunchedEffect(showNextUp) {
        if (wasNextUpVisible && !showNextUp && handledKey != candidateKey) navigation.returnToControls()
        wasNextUpVisible = showNextUp
    }

    fun playNext(automatic: Boolean) {
        val next = candidate ?: return
        val current = BackgroundPlaybackSession.snapshot.value
        if (changingItem || current.replacingSource || com.ambient.tv.companion.CompanionQueuePlayback.pending ||
            current.metadata?.route != metadata.route || current.generation != snapshot.generation) return
        if (handledKey == candidateKey) return
        handledKey = candidateKey
        if (automatic) BackgroundPlaybackSession.recordAutomaticNextUp(next)
        else {
            BackgroundPlaybackSession.registerUserInteraction()
            BackgroundPlaybackSession.recordManualNextUp(next)
        }
        onPlayNext(next)
    }

    fun restart() {
        val current = BackgroundPlaybackSession.snapshot.value
        if (metadata.isLive || changingItem || current.replacingSource || com.ambient.tv.companion.CompanionQueuePlayback.pending ||
            current.metadata?.route != metadata.route || current.generation != snapshot.generation) return
        if (onTimelineSeek == null && BackgroundPlaybackSession.activeHandle()?.canSeekNow() != true) return
        if (onTimelineSeek != null) {
            BackgroundPlaybackSession.userSeek()
            onTimelineSeek(0L)
        } else {
            BackgroundPlaybackSession.registerUserInteraction()
            BackgroundPlaybackSession.seekTo(0L)
        }
        chrome?.osd?.reveal()
    }

    fun playPrevious() {
        val previous = onPlayPrevious
        if (previous == null) {
            if (metadata.source != "youtube") restart()
            return
        }
        val current = BackgroundPlaybackSession.snapshot.value
        if (metadata.isLive || changingItem || current.replacingSource || com.ambient.tv.companion.CompanionQueuePlayback.pending ||
            current.metadata?.route != metadata.route || current.generation != snapshot.generation || previousHandled) return
        previousHandled = true
        BackgroundPlaybackSession.registerUserInteraction()
        previous()
    }

    val itemActionsReady = ownsSnapshot && !changingItem && !queuePending
    val restartAction = if (itemActionsReady && (onTimelineSeek != null || BackgroundPlaybackSession.activeHandle()?.canSeekNow() == true)) ::restart else null
    val itemControls = if (metadata.hasVideo && !metadata.isLive && !metadata.isTrailer) PlaybackItemControls(
        restart = restartAction,
        next = if (itemActionsReady && candidate != null) ({ playNext(automatic = false) }) else null,
        previous = when {
            onPlayPrevious != null -> if (itemActionsReady && !previousHandled) ::playPrevious else null
            metadata.source == "youtube" -> null
            else -> restartAction
        },
        hasPreviousItem = onPlayPrevious != null,
    ) else null

    val creditsDue = earlyCreditsEnabled && finalCredits != null && creditsRemainingMs == 0L && snapshot.status.playing
    // The countdown runs on the known duration; autoplay used to wait for the engine's EOF only.
    // A stream that stalls in its last segment, or ends without reporting EOF, left Next Up at
    // "0" forever. Once the countdown has reached 0, hand over after a short grace for a real EOF.
    val countdownExpired = !atEnd && !creditsDue && nextUpDue && config.autoplay && !stillWatching &&
        creditsRemainingMs == null && timing.remainingMs <= 0L && snapshot.wantsToPlay
    LaunchedEffect(acquired, atEnd, creditsDue, countdownExpired, candidateKey, showNextUp, nextUpDue, config.autoplay, stillWatching, queuePending, changingItem, metadata.successorsLoading, snapshot.generation) {
        if (countdownExpired) {
            kotlinx.coroutines.delay(NEXT_UP_EOF_GRACE_MS)
            val current = BackgroundPlaybackSession.snapshot.value
            if (!acquired || !ownsSnapshot || changingItem || metadata.successorsLoading ||
                current.replacingSource || current.generation != snapshot.generation ||
                current.metadata?.route != metadata.route ||
                com.ambient.tv.companion.CompanionQueuePlayback.pending ||
                (handledKey != null && handledKey == candidateKey)) return@LaunchedEffect
            // Only the autoplay hand-over: never stop playback before a real end here.
            playNext(automatic = true)
            return@LaunchedEffect
        }
        val current = BackgroundPlaybackSession.snapshot.value
        if (!acquired || (!atEnd && !creditsDue) || !ownsSnapshot || changingItem || metadata.successorsLoading ||
            current.replacingSource || current.generation != snapshot.generation || current.metadata?.route != metadata.route ||
            com.ambient.tv.companion.CompanionQueuePlayback.pending || (handledKey != null && handledKey == candidateKey)) return@LaunchedEffect
        when {
            audioSuccessor != null && BackgroundPlaybackSession.sleepTimer.kind != SleepTimerKind.END_OF_ITEM -> playNext(automatic = true)
            nextUpDue && config.autoplay && !stillWatching -> playNext(automatic = true)
            // Keep the choice visible at EOF when autoplay is off or confirmation is required.
            showNextUp -> Unit
            candidateKey == dismissedKey || !canOfferNext || nextUpDue -> BackgroundPlaybackSession.stopIfCurrent(metadata.route, snapshot.generation)
        }
    }

    CompositionLocalProvider(
        LocalPlaybackMetadata provides activeMetadata,
        LocalSegmentSkipControls provides skipControls,
        LocalPlaybackItemControls provides itemControls,
    ) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            // Next Up is a sibling of the renderer's controls. Handle media keys here so its
            // focused buttons cannot bypass Next/Previous, especially after native EOF.
            if (metadata.isLive || !ownsSnapshot ||
                BackgroundPlaybackSession.snapshot.value.metadata?.route != metadata.route ||
                event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.MediaNext -> if (candidate != null && !queuePending) {
                    playNext(automatic = false)
                    true
                } else false
                Key.MediaPrevious -> {
                    playPrevious()
                    true
                }
                else -> false
            }
        }) {
            PlaybackNavigationProvider(activeMetadata, timelineKey, timeline, timelineLoader, onTimelineSeek,
                nextUpVisible = showNextUp,
                onPlayNext = if (candidate != null) ({ automatic -> playNext(automatic) }) else null,
                // With Next Up enabled, open-ended credits must not bypass its autoplay/confirmation rules.
                canAutoAdvance = !changingItem && !queuePending && !linkCreditsToNextUp && !BackgroundPlaybackSession.shouldConfirmNextUp(config.stillWatchingEvery) &&
                    BackgroundPlaybackSession.sleepTimer.kind != SleepTimerKind.END_OF_ITEM,
                navigationState = navigation,
                creditsHandledByNextUp = linkCreditsToNextUp,
            ) { content() }
            if (showNextUp) {
                NextUpOverlay(
                    candidate = candidate,
                    secondsRemaining = secondsRemaining,
                    creditsCountdown = creditsRemainingMs != null,
                    leadSeconds = if (creditsRemainingMs != null) config.creditsDelaySeconds else timing.progressWindowSeconds,
                    autoplay = config.autoplay,
                    stillWatching = stillWatching,
                    onPlayNow = { playNext(automatic = false) },
                    onAutoplayChanged = onAutoplayChanged?.let { persist ->
                        { enabled ->
                            persist(enabled)
                            autoplay = enabled
                        }
                    },
                    onDismiss = {
                        dismissedKey = candidateKey
                        navigation.returnToControls()
                    },
                    onSkipCredits = timing.credits?.takeIf { it.endMs > positionMs }?.let { credits ->
                        {
                            navigation.interact()
                            if (onTimelineSeek != null) onTimelineSeek(credits.endMs)
                            else BackgroundPlaybackSession.seekTo((credits.endMs - metadata.timelineOffsetMs).coerceAtLeast(0))
                            navigation.returnToControls()
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(if (handheld) 12.dp else 32.dp),
                )
            }
        }
    }
    SideEffect {
        if (BackgroundPlaybackSession.snapshot.value.metadata?.route == activeMetadata.route) {
            acquired = true
            BackgroundPlaybackSession.updateMetadata(activeMetadata)
        }
    }
}

/** How long Next Up waits at a countdown of 0 for the engine's own end before handing over. */
internal const val NEXT_UP_EOF_GRACE_MS = 2_000L

package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.ambient.tv.ui.common.projectm.LocalProjectMBrowser
import com.ambient.tv.ui.common.projectm.ProjectMPresetPanel
import com.ambient.tv.ui.common.projectm.rememberProjectMBrowser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.player.SleepTimerConfig
import com.ambient.tv.ui.player.SleepTimerKind
import com.ambient.tv.ui.player.LocalSegmentSkipControls
import com.ambient.tv.ui.player.offeredButtons
import com.ambient.tv.ui.player.playbackSubtitleSelection
import com.ambient.tv.ui.player.playerTechBadgesVisible
import com.ambient.tv.ui.player.setPlayerTechBadgesVisible
import com.ambient.tv.ui.player.teletextSubtitleChoice
import com.ambient.tv.ui.player.teletext.ProbeTeletextSubtitlePages
import com.ambient.tv.ui.player.teletext.TeletextUi
import com.ambient.tv.ui.player.teletext.teletextSubtitleOptions
import com.ambient.tv.ui.player.videoImagePreferences


private val missingPlayerInfoValues = setOf("—", "-", "N/A", "n/a")

internal fun visiblePlayerInfoLines(infoLines: List<Pair<String, String>>): List<Pair<String, String>> =
    infoLines.filter { (_, value) ->
        val normalized = value.trim()
        normalized.isNotEmpty() && normalized !in missingPlayerInfoValues
    }

@Composable
fun UnifiedBackendPlayerPanel(
    panel: UnifiedPlayerPanel,
    onPanelChange: (UnifiedPlayerPanel) -> Unit,
    hasVideo: Boolean,
    isLive: Boolean,
    scalingKey: String,
    aspectKey: String,
    zoomFactor: Float,
    speed: Float,
    onScalingChanged: ((String) -> Unit)?,
    onAspectChanged: ((String) -> Unit)?,
    onZoomChanged: ((Float) -> Unit)?,
    onSpeedChanged: ((Float) -> Unit)?,
    audioOptions: List<Pair<String, String>>,
    selectedAudio: String,
    onAudioSelected: (String) -> Unit,
    subtitleOptions: List<Pair<String, String>>,
    selectedSubtitle: String,
    onSubtitleSelected: (String) -> Unit,
    sleepTimer: SleepTimerConfig,
    onSleepChanged: (SleepTimerConfig) -> Unit,
    infoLines: List<Pair<String, String>>,
    /** Stream qualities (key, label); empty hides the entry. The first entry is "automatic". */
    qualityOptions: List<Pair<String, String>> = emptyList(),
    selectedQuality: String = "auto",
    onQualitySelected: ((String) -> Unit)? = null,
    rendererKey: String? = null,
    onRendererSelected: ((String, Boolean) -> Unit)? = null,
    showRenderer: Boolean = true,
    showVideo: Boolean = true,
    showAudio: Boolean = true,
    showSubtitles: Boolean = true,
    showVisualizer: Boolean = false,
    settingsExtrasTitle: String? = null,
    hasVideoExtras: Boolean = false,
    videoExtrasTitle: String? = null,
    audioExtrasTitle: String? = null,
    audioSession: com.ambient.tv.ui.player.AudioSession? = null,
    onDeinterlaceChanged: ((String, String) -> Unit)? = null,
    settingsExtras: @Composable ColumnScope.() -> Unit = {},
    videoExtras: @Composable ColumnScope.() -> Unit = {},
    audioExtras: @Composable ColumnScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val displayedAudioOptions = remember(audioOptions, displayLocale) {
        audioOptions.map { (id, label) ->
            id to com.ambient.tv.ui.player.expandAudioLanguageCodes(label, displayLocale)
        }
    }
    val teletextOptions = if (isLive) teletextSubtitleOptions() else emptyList()
    val subtitleSelection = playbackSubtitleSelection(
        nativeOptions = subtitleOptions,
        selectedNative = selectedSubtitle,
        teletextOptions = teletextOptions,
        teletextChoice = TeletextUi.subtitleChoice,
        offLabel = stringResource(R.string.player_subtitles_off),
    )
    val subtitleSummary = subtitleSelection.options.first { it.first == subtitleSelection.selectedKey }.second
    val teletextSelected = teletextSubtitleChoice(subtitleSelection.selectedKey) != null
    val techBadgesVisible = playerTechBadgesVisible()
    val nerdStatsVisible = audioSession?.performanceHint == true
    val fpsOverlayVisible = audioSession?.showFpsOverlay == true
    val navigation = LocalPlayerPanelNavigation.current
    val segmentSkip = LocalSegmentSkipControls.current
    // Back follows the click path: a level opened straight from the action bar closes the panel.
    val back: () -> Unit = {
        navigation?.back(panel) ?: onPanelChange(if (panel == UnifiedPlayerPanel.SETTINGS) UnifiedPlayerPanel.NONE else UnifiedPlayerPanel.SETTINGS)
    }
    when (panel) {
        UnifiedPlayerPanel.NONE -> Unit
        UnifiedPlayerPanel.SETTINGS -> UnifiedPlayerSidePanel(
            title = stringResource(R.string.player_playback_settings),
            modifier = modifier,
            onBack = back,
        ) {
            if (isLive && com.ambient.tv.ui.tvheadend.LocalLiveTimeshiftChanged.current != null) {
                com.ambient.tv.ui.settings.LiveTvFeatureSwitches(
                    onTimeshiftChanged = com.ambient.tv.ui.tvheadend.LocalLiveTimeshiftChanged.current,
                )
            }
            if (isLive) com.ambient.tv.ui.player.teletext.TeletextPanelEntries()
            if (showVideo && hasVideo) {
                PlayerPanelEntry(
                    icon = Icons.Filled.Movie,
                    title = stringResource(R.string.player_video),
                    value = videoSummary(scalingKey, aspectKey, zoomFactor, speed, isLive),
                    onClick = { onPanelChange(UnifiedPlayerPanel.VIDEO) },
                )
            }
            if (!hasVideo && showRenderer && rendererKey != null && onRendererSelected != null) {
                PlayerPanelEntry(
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.player_renderer),
                    value = com.ambient.tv.ui.player.rendererLabel(rendererKey),
                    onClick = { onPanelChange(UnifiedPlayerPanel.RENDERER) },
                )
            }
            if (hasVideo && qualityOptions.size > 1 && onQualitySelected != null) {
                PlayerDropUpSelector(
                    icon = Icons.Filled.HighQuality,
                    label = stringResource(R.string.player_quality),
                    value = qualityOptions.firstOrNull { it.first == selectedQuality }?.second ?: qualityOptions.first().second,
                    options = qualityOptions,
                    selectedKey = selectedQuality,
                    onSelected = onQualitySelected,
                )
            }
            if (showAudio) {
                PlayerPanelEntry(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    title = stringResource(R.string.player_audio),
                    value = displayedAudioOptions.firstOrNull { it.first == selectedAudio }?.second ?: stringResource(R.string.player_default_track),
                    onClick = { onPanelChange(UnifiedPlayerPanel.AUDIO) },
                )
            }
            if (showSubtitles || teletextOptions.isNotEmpty()) {
                PlayerPanelEntry(
                    icon = Icons.Filled.Subtitles,
                    title = stringResource(R.string.player_subtitles),
                    value = subtitleSummary,
                    onClick = { onPanelChange(UnifiedPlayerPanel.SUBTITLES) },
                )
            }
            if (hasVideo || !isLive) {
                PlayerPanelEntry(
                    icon = Icons.Filled.Build,
                    title = stringResource(R.string.player_tools),
                    value = stringResource(when {
                        com.ambient.tv.ui.player.AbRepeat.active -> R.string.player_tools_ab_on
                        hasVideo -> R.string.player_tools_summary
                        else -> R.string.player_tools_summary_audio
                    }),
                    onClick = { onPanelChange(UnifiedPlayerPanel.TOOLS) },
                )
            }
            if (hasVideo) {
                PlayerPanelEntry(
                    icon = Icons.Filled.Save,
                    title = stringResource(R.string.playback_profile_actions),
                    value = stringResource(R.string.playback_profile_summary),
                    onClick = { onPanelChange(UnifiedPlayerPanel.CONFIGURATION) },
                )
            }
            segmentSkip?.onIntroModeChanged?.takeIf { segmentSkip.hasIntro }?.let { onChanged ->
                PlayerDropUpSelector(
                    icon = Icons.Filled.FastForward,
                    label = stringResource(R.string.settings_intro_skip),
                    value = skipModeLabel(segmentSkip.introMode, intro = true),
                    options = skipModeOptions(intro = true),
                    selectedKey = segmentSkip.introMode,
                    onSelected = onChanged,
                )
            }
            segmentSkip?.onCreditsModeChanged?.takeIf { segmentSkip.hasCredits }?.let { onChanged ->
                PlayerDropUpSelector(
                    icon = Icons.Filled.SkipNext,
                    label = stringResource(R.string.settings_credits_skip),
                    value = skipModeLabel(segmentSkip.creditsMode, intro = false),
                    options = skipModeOptions(intro = false),
                    selectedKey = segmentSkip.creditsMode,
                    onSelected = onChanged,
                )
            }
            if (showVisualizer) {
                PlayerPanelEntry(
                    icon = Icons.Filled.GraphicEq,
                    title = stringResource(R.string.player_visualizer),
                    value = stringResource(R.string.settings_projectm_title),
                    onClick = { onPanelChange(UnifiedPlayerPanel.VISUALIZER) },
                )
            }
            // A hidden sleep button would leave the timer without any entry point.
            com.ambient.tv.ui.player.activeOsdPlayer
                ?.takeIf { !com.ambient.tv.ui.player.osdButtonVisible(com.ambient.tv.ui.player.OsdButton.SLEEP, it) }
                ?.let {
                    PlayerPanelEntry(
                        icon = Icons.Filled.Bedtime,
                        title = stringResource(R.string.player_sleep_timer),
                        value = stringResource(if (sleepTimer.kind == SleepTimerKind.OFF) R.string.player_off else R.string.player_on),
                        onClick = { onPanelChange(UnifiedPlayerPanel.SLEEP) },
                    )
                }
            settingsExtrasTitle?.let { RightPanelSectionTitle(it) }
            settingsExtras()
            PlayerPanelEntry(
                icon = Icons.Filled.Visibility,
                title = stringResource(R.string.player_tech_badges),
                value = stringResource(if (techBadgesVisible) R.string.player_on else R.string.player_off),
                onClick = { setPlayerTechBadgesVisible(videoImagePreferences(context), !techBadgesVisible) },
            )
            com.ambient.tv.ui.player.activeOsdPlayer?.let { player ->
                val hidden = com.ambient.tv.ui.player.osdHiddenButtons()
                val offered = player.offeredButtons()
                PlayerPanelEntry(
                    icon = Icons.Filled.ViewWeek,
                    title = stringResource(R.string.osd_buttons),
                    value = stringResource(R.string.osd_buttons_summary,
                        offered.count { com.ambient.tv.ui.player.osdButtonVisible(it, player, hidden) }, offered.size),
                    onClick = { onPanelChange(UnifiedPlayerPanel.OSD_BUTTONS) },
                )
            }
            audioSession?.let { session ->
                PlayerPanelEntry(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.player_stats_for_nerds),
                    value = stringResource(if (nerdStatsVisible) R.string.player_on else R.string.player_off),
                    onClick = { session.performanceHint = !session.performanceHint },
                )
                PlayerPanelEntry(
                    icon = Icons.Filled.Speed,
                    title = stringResource(R.string.player_fps_overlay),
                    value = stringResource(if (fpsOverlayVisible) R.string.player_on else R.string.player_off),
                    onClick = { session.showFpsOverlay = !session.showFpsOverlay },
                )
            }
        }
        UnifiedPlayerPanel.CONFIGURATION -> UnifiedPlayerSidePanel(
            title = stringResource(R.string.playback_profile_actions),
            modifier = modifier,
            onBack = back,
        ) {
            com.ambient.tv.ui.player.PlaybackProfileControls(onRendererSelected)
        }
        UnifiedPlayerPanel.RENDERER,
        UnifiedPlayerPanel.VIDEO,
        UnifiedPlayerPanel.VIDEO_SECTION,
        UnifiedPlayerPanel.VIDEO_SVP,
        UnifiedPlayerPanel.VIDEO_SVP_ADVANCED -> if (!hasVideo) UnifiedPlayerSidePanel(
            title = stringResource(R.string.player_renderer),
            modifier = modifier,
            onBack = back,
        ) {
            // Audio-only: picture options do not apply, only the engine choice does.
            if (showRenderer && rendererKey != null && onRendererSelected != null) {
                com.ambient.tv.ui.player.PlayerRendererSelector(rendererKey, onRendererSelected)
            }
        } else UnifiedPlayerSidePanel(
            // The list of areas, or one area titled with its name.
            title = when (panel) {
                UnifiedPlayerPanel.VIDEO_SECTION ->
                    stringResource(com.ambient.tv.ui.player.videoSectionTitle(navigation?.videoState?.section.orEmpty()))
                UnifiedPlayerPanel.VIDEO_SVP -> stringResource(R.string.svp_title)
                UnifiedPlayerPanel.VIDEO_SVP_ADVANCED -> stringResource(R.string.svp_advanced_title)
                else -> stringResource(R.string.player_video_settings)
            },
            modifier = modifier,
            onBack = back,
        ) {
            if (panel == UnifiedPlayerPanel.VIDEO || panel == UnifiedPlayerPanel.RENDERER) com.ambient.tv.ui.player.HdrMedia3Hint()
            com.ambient.tv.ui.player.PlayerVideoSettingsGroups(
                sectionView = panel != UnifiedPlayerPanel.VIDEO && panel != UnifiedPlayerPanel.RENDERER,
                subPage = when (panel) {
                    UnifiedPlayerPanel.VIDEO_SVP -> "svp"
                    UnifiedPlayerPanel.VIDEO_SVP_ADVANCED -> "svp_advanced"
                    else -> null
                },
                onOpenSubPage = { page ->
                    onPanelChange(if (page == "svp_advanced") UnifiedPlayerPanel.VIDEO_SVP_ADVANCED else UnifiedPlayerPanel.VIDEO_SVP)
                },
                onOpenSection = { onPanelChange(UnifiedPlayerPanel.VIDEO_SECTION) },
                motionSummary = if (!isLive && onSpeedChanged != null && kotlin.math.abs(speed - 1f) >= .01f) "${speed}×" else null,
                isLive = isLive,
                rendererKey = rendererKey,
                onRendererSelected = onRendererSelected,
                showRenderer = showRenderer,
                onDeinterlaceChanged = onDeinterlaceChanged,
                hasPresentationControls = onScalingChanged != null || onAspectChanged != null || onZoomChanged != null,
                hasPlaybackControls = !isLive && onSpeedChanged != null,
                presentationControls = {
                    if (onScalingChanged != null && aspectKey == "auto") {
                        PlayerDropUpSelector(
                            label = stringResource(R.string.player_scaling_mode),
                            value = when (scalingKey) {
                                "zoom" -> stringResource(R.string.player_fill_screen)
                                "stretch" -> stringResource(R.string.player_stretch)
                                else -> stringResource(R.string.player_fit_screen)
                            },
                            options = listOf(
                                "fit" to stringResource(R.string.player_fit_screen),
                                "zoom" to stringResource(R.string.player_fill_screen),
                                "stretch" to stringResource(R.string.player_stretch),
                            ),
                            selectedKey = scalingKey,
                            onSelected = onScalingChanged,
                        )
                    }
                    if (onAspectChanged != null) {
                        PlayerDropUpSelector(
                            label = stringResource(R.string.player_aspect_ratio),
                            value = if (aspectKey == "auto") stringResource(R.string.player_aspect_auto) else aspectKey,
                            options = listOf(
                                "auto" to stringResource(R.string.player_aspect_auto),
                                "16:9" to "16:9",
                                "4:3" to "4:3",
                                "21:9" to "21:9",
                            ),
                            selectedKey = aspectKey,
                            onSelected = { key ->
                                if (key != "auto") {
                                    onScalingChanged?.invoke("fit")
                                    onZoomChanged?.invoke(1f)
                                }
                                onAspectChanged(key)
                            },
                        )
                    }
                    if (aspectKey != "auto") {
                        Text(
                            stringResource(R.string.player_forced_aspect_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (onZoomChanged != null && aspectKey == "auto") {
                        val zoomKey = when {
                            kotlin.math.abs(zoomFactor - 1.5f) < .01f -> "1.5"
                            kotlin.math.abs(zoomFactor - 1.25f) < .01f -> "1.25"
                            kotlin.math.abs(zoomFactor - 1.1f) < .01f -> "1.1"
                            else -> "1.0"
                        }
                        PlayerDropUpSelector(
                            label = stringResource(R.string.player_zoom_level),
                            value = "${(zoomFactor * 100).toInt()}%",
                            options = listOf("1.0" to "100%", "1.1" to "110%", "1.25" to "125%", "1.5" to "150%"),
                            selectedKey = zoomKey,
                            onSelected = { onZoomChanged(it.toFloatOrNull() ?: 1f) },
                        )
                    }
                },
                playbackControls = {
                    if (!isLive && onSpeedChanged != null) {
                        PlayerDropUpSelector(
                            label = stringResource(R.string.player_speed),
                            value = if (kotlin.math.abs(speed - 1f) < .01f) "1×" else "${speed}×",
                            options = listOf("0.5" to "0.5×", "0.75" to "0.75×", "1.0" to "1×", "1.25" to "1.25×", "1.5" to "1.5×", "2.0" to "2×"),
                            selectedKey = speed.toString(),
                            onSelected = { it.toFloatOrNull()?.let(onSpeedChanged) },
                        )
                    }
                },
                hasOutputExtras = hasVideoExtras || videoExtrasTitle != null,
                outputExtras = {
                    videoExtrasTitle?.let { RightPanelSectionTitle(it) }
                    videoExtras()
                },
            )
        }
        UnifiedPlayerPanel.AUDIO, UnifiedPlayerPanel.AUDIO_SECTION, UnifiedPlayerPanel.AUDIO_CODECS -> UnifiedPlayerSidePanel(
            title = stringResource(if (panel == UnifiedPlayerPanel.AUDIO) R.string.player_audio
                else com.ambient.tv.ui.player.audioSectionTitle(if (panel == UnifiedPlayerPanel.AUDIO_CODECS) "codecs" else navigation?.audioState?.section ?: "output")),
            modifier = modifier,
            onBack = back,
        ) {
            if (panel == UnifiedPlayerPanel.AUDIO) PlayerDropUpSelector(
                label = stringResource(R.string.player_audio_track_title),
                value = displayedAudioOptions.firstOrNull { it.first == selectedAudio }?.second ?: stringResource(R.string.player_default_track),
                options = displayedAudioOptions,
                selectedKey = selectedAudio,
                onSelected = { onAudioSelected(it); back() },
            )
            val state = navigation?.audioState ?: remember { VideoPanelState().apply { section = "output" } }
            androidx.compose.runtime.CompositionLocalProvider(LocalVideoPanelState provides state) {
                audioSession?.let { session ->
                    com.ambient.tv.ui.player.AudioControls(session,
                        section = when (panel) {
                            UnifiedPlayerPanel.AUDIO_CODECS -> "codecs"
                            UnifiedPlayerPanel.AUDIO_SECTION -> state.section
                            else -> null
                        },
                        onOpenSection = { section ->
                            if (section == "codecs") onPanelChange(UnifiedPlayerPanel.AUDIO_CODECS)
                            else {
                                state.section = section
                                onPanelChange(UnifiedPlayerPanel.AUDIO_SECTION)
                            }
                        },
                    )
                }
            }
            if (panel == UnifiedPlayerPanel.AUDIO) {
                audioExtrasTitle?.let { RightPanelSectionTitle(it) }
                audioExtras()
            }
        }
        UnifiedPlayerPanel.SUBTITLES -> UnifiedPlayerSidePanel(
            title = stringResource(R.string.player_subtitles),
            modifier = modifier,
            onBack = back,
        ) {
            if (isLive) ProbeTeletextSubtitlePages()
            PlayerDropUpSelector(
                label = stringResource(R.string.player_subtitles),
                value = subtitleSummary,
                options = subtitleSelection.options,
                selectedKey = subtitleSelection.selectedKey,
                onSelected = { key ->
                    val teletextChoice = teletextSubtitleChoice(key)
                    if (teletextChoice != null) {
                        TeletextUi.setSubtitleChoice(context, teletextChoice)
                    } else {
                        com.ambient.tv.ui.player.BackgroundPlaybackSession.manualSubtitleSelection()
                        onSubtitleSelected(key)
                    }
                    back()
                },
            )
            // OpenSubtitles.com, when a key is set and the title can be searched (movies/episodes, direct play).
            val searchInfo = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.collectAsState().value.metadata?.subtitleSearch
            com.ambient.tv.ui.player.OnlineSubtitleSearchButton(searchInfo, onLoaded = back)
            // A loaded subtitle file can be timed against a subtitle the server has for the title.
            val playbackMetadata = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.collectAsState().value.metadata
            val syncTarget = com.ambient.tv.ui.player.SubtitleAutoSync.target(playbackMetadata)
            if (syncTarget != null && com.ambient.tv.ui.player.SubtitleAutoSync.references(playbackMetadata, syncTarget).isNotEmpty()) {
                val syncing = com.ambient.tv.ui.player.SubtitleAutoSync.running
                PlayerPanelEntry(
                    icon = Icons.Filled.Sync,
                    title = stringResource(R.string.subtitle_sync_auto),
                    value = stringResource(if (syncing) R.string.subtitle_sync_running else R.string.subtitle_sync_auto_value),
                    onClick = { if (!syncing) com.ambient.tv.ui.player.PlayerToolsWork.scope.launch { com.ambient.tv.ui.player.SubtitleAutoSync.run() } },
                )
            }
            // MPV and VLC timing applies to native tracks, not the app's teletext overlay.
            (com.ambient.tv.ui.player.BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.SubtitleDelayTarget)
                ?.takeUnless { teletextSelected }?.let { target ->
                PlayerDropUpSelector(
                    label = stringResource(R.string.subtitle_delay),
                    value = "${target.subtitleDelayMs} ms",
                    options = listOf(
                        "-500" to "−500 ms", "-100" to "−100 ms", "0" to stringResource(R.string.subtitle_delay_reset),
                        "+100" to "+100 ms", "+500" to "+500 ms",
                    ),
                    selectedKey = "0",
                    onSelected = { target.setSubtitleDelay(if (it == "0") 0 else target.subtitleDelayMs + it.toInt()) },
                )
                Text(stringResource(R.string.subtitle_delay_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        UnifiedPlayerPanel.TOOLS -> UnifiedPlayerSidePanel(
            title = stringResource(R.string.player_tools),
            modifier = modifier,
            onBack = back,
        ) {
            val handle = com.ambient.tv.ui.player.BackgroundPlaybackSession.activeHandle()
            val title = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.collectAsState().value.metadata?.title
            if (handle != null && !isLive) {
                val ab = com.ambient.tv.ui.player.AbRepeat
                val a = ab.pointA
                val b = ab.pointB
                RightPanelSectionTitle(stringResource(R.string.player_tools_ab))
                PlayerPanelEntry(
                    icon = Icons.Filled.Repeat,
                    title = stringResource(R.string.player_tools_set_a),
                    value = a?.let(::formatPlaybackClock) ?: stringResource(R.string.player_off),
                    onClick = { ab.setA(handle) },
                )
                PlayerPanelEntry(
                    icon = Icons.Filled.RepeatOne,
                    title = stringResource(R.string.player_tools_set_b),
                    value = b?.let(::formatPlaybackClock) ?: stringResource(if (a == null) R.string.player_tools_set_a_first else R.string.player_off),
                    onClick = { ab.setB(handle) },
                )
                if (a != null) PlayerPanelEntry(
                    icon = Icons.Filled.Clear,
                    title = stringResource(R.string.player_tools_ab_clear),
                    value = "",
                    onClick = { ab.clear() },
                )
                if (hasVideo) RightPanelSectionTitle(stringResource(R.string.player_tools_frame))
                if (hasVideo) PlayerPanelEntry(
                    icon = Icons.Filled.ChevronLeft,
                    title = stringResource(R.string.player_tools_frame_back),
                    value = "",
                    onClick = { com.ambient.tv.ui.player.stepFrame(handle, forward = false) },
                )
                if (hasVideo) PlayerPanelEntry(
                    icon = Icons.Filled.ChevronRight,
                    title = stringResource(R.string.player_tools_frame_forward),
                    value = "",
                    onClick = { com.ambient.tv.ui.player.stepFrame(handle, forward = true) },
                )
            }
            val metadata = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.collectAsState().value.metadata
            val bookmarkKey = com.ambient.tv.ui.player.PlaybackBookmarks.keyFor(metadata)
            if (handle != null && bookmarkKey != null) {
                val revision = com.ambient.tv.ui.player.PlaybackBookmarks.revision.intValue
                val marks = androidx.compose.runtime.remember(bookmarkKey, revision) { com.ambient.tv.ui.player.PlaybackBookmarks.list(context, bookmarkKey) }
                RightPanelSectionTitle(stringResource(R.string.player_tools_bookmarks))
                PlayerPanelEntry(
                    icon = Icons.Filled.BookmarkAdd,
                    title = stringResource(R.string.player_tools_bookmark_add),
                    value = formatPlaybackClock(handle.status().positionMs),
                    onClick = { com.ambient.tv.ui.player.PlaybackBookmarks.add(context, bookmarkKey, handle.status().positionMs) },
                )
                marks.forEach { mark ->
                    PlayerPanelEntry(
                        icon = Icons.Filled.Bookmark,
                        title = stringResource(R.string.player_tools_bookmark_jump),
                        value = formatPlaybackClock(mark),
                        onClick = { handle.seekTo(mark) },
                    )
                }
                if (marks.isNotEmpty()) {
                    val all = "all"
                    PlayerDropUpSelector(
                        icon = Icons.Filled.Clear,
                        label = stringResource(R.string.player_tools_bookmark_delete),
                        value = "",
                        options = marks.map { it.toString() to formatPlaybackClock(it) } + (all to stringResource(R.string.player_tools_bookmark_delete_all)),
                        selectedKey = "",
                        onSelected = { key ->
                            if (key == all) com.ambient.tv.ui.player.PlaybackBookmarks.clear(context, bookmarkKey)
                            else key.toLongOrNull()?.let { com.ambient.tv.ui.player.PlaybackBookmarks.remove(context, bookmarkKey, it) }
                        },
                    )
                }
            }
            if (!hasVideo && !isLive) {
                val revision = com.ambient.tv.ui.player.LyricsSettings.revision
                val preferences = androidx.compose.runtime.remember { com.ambient.tv.ui.player.videoImagePreferences(context) }
                val showLyrics = androidx.compose.runtime.remember(revision.intValue) { preferences.lyricsVisible }
                val lyricsOnline = androidx.compose.runtime.remember(revision.intValue) { preferences.lyricsOnlineEnabled }
                RightPanelSectionTitle(stringResource(R.string.player_tools_lyrics))
                PlayerPanelEntry(
                    icon = Icons.AutoMirrored.Filled.Subject,
                    title = stringResource(R.string.player_tools_lyrics_show),
                    value = stringResource(if (showLyrics) R.string.player_on else R.string.player_off),
                    onClick = { preferences.lyricsVisible = !showLyrics; revision.intValue++ },
                )
                PlayerPanelEntry(
                    icon = Icons.Filled.Public,
                    title = stringResource(R.string.player_tools_lyrics_online),
                    value = stringResource(if (lyricsOnline) R.string.player_on else R.string.player_off),
                    onClick = { preferences.lyricsOnlineEnabled = !lyricsOnline; revision.intValue++ },
                )
                Text(stringResource(R.string.player_tools_lyrics_online_hint), style = MaterialTheme.typography.bodySmall)
                val gapless = androidx.compose.runtime.remember(revision.intValue) { preferences.gaplessMusicEnabled }
                RightPanelSectionTitle(stringResource(R.string.player_tools_music))
                PlayerPanelEntry(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = stringResource(R.string.player_tools_gapless),
                    value = stringResource(if (gapless) R.string.player_on else R.string.player_off),
                    onClick = { preferences.gaplessMusicEnabled = !gapless; revision.intValue++ },
                )
                Text(stringResource(R.string.player_tools_gapless_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (hasVideo) RightPanelSectionTitle(stringResource(R.string.player_tools_screenshot))
            if (hasVideo) PlayerPanelEntry(
                icon = Icons.Filled.PhotoCamera,
                title = stringResource(R.string.player_tools_screenshot_save),
                value = stringResource(R.string.player_tools_screenshot_where),
                onClick = {
                    if (!com.ambient.tv.ui.player.PlayerToolsWork.savingScreenshot) {
                        com.ambient.tv.ui.player.PlayerToolsWork.savingScreenshot = true
                        com.ambient.tv.ui.player.PlayerToolsWork.scope.launch {
                            try { com.ambient.tv.ui.player.saveVideoScreenshot(context.applicationContext, title) }
                            finally { com.ambient.tv.ui.player.PlayerToolsWork.savingScreenshot = false }
                        }
                    }
                },
            )
        }
        UnifiedPlayerPanel.SLEEP -> UnifiedPlayerSidePanel(
            title = stringResource(R.string.player_sleep_timer),
            modifier = modifier,
            onBack = back,
        ) {
            Text(stringResource(R.string.player_sleep_action_hint), style = MaterialTheme.typography.bodySmall)
            SleepTimerControls(sleepTimer, isLive, onSleepChanged)
        }

        UnifiedPlayerPanel.OSD_BUTTONS -> UnifiedPlayerSidePanel(
            title = stringResource(R.string.osd_buttons),
            modifier = modifier,
            onBack = back,
        ) {
            val player = com.ambient.tv.ui.player.activeOsdPlayer ?: return@UnifiedPlayerSidePanel
            Text(stringResource(R.string.osd_buttons_hint), style = MaterialTheme.typography.bodySmall)
            player.offeredButtons().forEach { button ->
                val visible = com.ambient.tv.ui.player.osdButtonVisible(button, player)
                PlayerPanelEntry(
                    icon = button.icon,
                    title = stringResource(button.label),
                    value = stringResource(if (visible) R.string.player_on else R.string.player_off),
                    onClick = { com.ambient.tv.ui.player.setOsdButtonVisible(videoImagePreferences(context), player, button, !visible) },
                )
            }
        }

        UnifiedPlayerPanel.VISUALIZER, UnifiedPlayerPanel.VISUALIZER_SETTINGS -> {
            val browser = LocalProjectMBrowser.current ?: rememberProjectMBrowser("all")
            ProjectMPresetPanel(
                browser = browser,
                settingsView = panel == UnifiedPlayerPanel.VISUALIZER_SETTINGS,
                onOpenSettings = { onPanelChange(UnifiedPlayerPanel.VISUALIZER_SETTINGS) },
                onBack = back,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun skipModeOptions(intro: Boolean) = listOf(
    "off" to stringResource(if (intro) R.string.intro_skip_off else R.string.credits_skip_off),
    "button" to stringResource(if (intro) R.string.intro_skip_button else R.string.credits_skip_button),
    "auto" to stringResource(if (intro) R.string.intro_skip_auto else R.string.credits_skip_auto),
)

@Composable
private fun skipModeLabel(mode: String, intro: Boolean): String = skipModeOptions(intro)
    .firstOrNull { it.first == mode }?.second
    ?: stringResource(if (intro) R.string.intro_skip_button else R.string.credits_skip_button)

@Composable
private fun videoSummary(scalingKey: String, aspectKey: String, zoom: Float, speed: Float, isLive: Boolean): String {
    val scaling = when (if (aspectKey == "auto") scalingKey else "fit") {
        "zoom" -> stringResource(R.string.player_fill_screen)
        "stretch" -> stringResource(R.string.player_stretch)
        else -> stringResource(R.string.player_fit_screen)
    }
    val aspect = if (aspectKey == "auto") stringResource(R.string.player_aspect_auto) else aspectKey
    return buildString {
        append(scaling)
        append(" · ")
        append(aspect)
        if (aspectKey == "auto" && kotlin.math.abs(zoom - 1f) >= .01f) append(" · ${(zoom * 100).toInt()}%")
        if (!isLive && kotlin.math.abs(speed - 1f) >= .01f) append(" · ${speed}×")
    }
}


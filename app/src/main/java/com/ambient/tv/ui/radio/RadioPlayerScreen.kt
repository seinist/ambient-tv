package com.ambient.tv.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.align
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.R
import com.ambient.tv.ui.common.AudioPlaybackVisualizer
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.ProjectMVisualizer
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackArtworkCard
import com.ambient.tv.ui.player.ReferencePlayerBottomContent
import com.ambient.tv.ui.player.ReferencePlayerClock
import com.ambient.tv.ui.player.ReferencePlayerHeader
import com.ambient.tv.ui.player.ReferencePlayerOverlayBackground
import com.ambient.tv.ui.common.systemName

@Composable
fun RadioPlayerScreen(
    onBack: () -> Unit,
    viewModel: RadioPlayerViewModel = hiltViewModel(),
) {
    val session by BackgroundPlaybackSession.snapshot.collectAsState()
    val metadata = session.metadata

    BackHandler { onBack() }
    LaunchedEffect(metadata?.source) {
        if (metadata != null && metadata.source != "radio") onBack()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val analyzer = BackgroundPlaybackSession.currentAudioSpectrum()
        when {
            metadata == null || metadata.source != "radio" -> Unit
            viewModel.visualizerMode == "projectm" && ProjectMAvailability.isAvailable -> ProjectMVisualizer(
                analyzer = analyzer,
                intervalSeconds = viewModel.projectmPresetIntervalSeconds,
                transitionSeconds = viewModel.projectmTransitionSeconds,
                qualityKey = viewModel.projectmQualityKey,
                categoryKey = viewModel.projectmPresetCategoryKey,
                showControls = true,
                modifier = Modifier.fillMaxSize(),
            )
            viewModel.visualizerMode != "off" -> AudioPlaybackVisualizer(
                modifier = Modifier.fillMaxSize(),
                analyzer = analyzer,
            )
            else -> {
                if (!metadata.artworkUrl.isNullOrBlank()) {
                    PlaybackArtworkCard(
                        artworkUrl = metadata.artworkUrl,
                        fallbackUrls = metadata.artworkFallbackUrls,
                        title = metadata.title,
                        headers = metadata.artworkHeaders,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(280.dp),
                    )
                } else {
                    SystemLogo(MediaSystem.RADIO, Modifier.align(Alignment.Center), size = 120.dp)
                }
            }
        }

        ReferencePlayerOverlayBackground()

        if (metadata != null && metadata.source == "radio") {
            ReferencePlayerClock(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(28.dp),
                liveLabel = "LIVE",
            )
            ReferencePlayerBottomContent {
                ReferencePlayerHeader(
                    title = metadata.title,
                    contextLine = systemName(MediaSystem.RADIO),
                    subtitle = metadata.subtitle,
                    artworkUrl = metadata.artworkUrl,
                    artworkFallbackUrls = metadata.artworkFallbackUrls,
                    artworkHeaders = metadata.artworkHeaders,
                    artworkAspectRatio = 1f,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconButton(
                        Icons.Filled.SkipPrevious,
                        stringResource(R.string.companion_previous),
                        viewModel::previous,
                        enabled = viewModel.hasPrevious(),
                    )
                    PlayerIconButton(
                        if (session.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (session.playing) R.string.player_pause else R.string.player_play),
                        BackgroundPlaybackSession::userToggle,
                        primary = true,
                    )
                    PlayerIconButton(
                        Icons.Filled.SkipNext,
                        stringResource(R.string.companion_next),
                        viewModel::next,
                        enabled = viewModel.hasNext(),
                    )
                    PlayerIconButton(
                        Icons.Filled.Stop,
                        stringResource(R.string.player_stop),
                        {
                            BackgroundPlaybackSession.stop()
                            onBack()
                        },
                    )
                    PlayerIconButton(
                        Icons.Filled.FullscreenExit,
                        stringResource(R.string.player_exit_fullscreen),
                        onBack,
                    )
                }
            }
        }
    }
}

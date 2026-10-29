package com.ambient.tv.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AudioPlaybackVisualizer
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.ProjectMVisualizer
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.Media3PlaybackHandle
import com.ambient.tv.ui.player.PlaybackArtworkCard
import com.ambient.tv.ui.player.PlaybackEngineFactory
import com.ambient.tv.ui.player.videoImagePreferences

@Composable
fun RadioNowPlayingScreen(
    onBack: () -> Unit,
    viewModel: RadioNowPlayingViewModel = hiltViewModel(),
) {
    val session by BackgroundPlaybackSession.snapshot.collectAsState()
    val metadata = session.metadata
    BackHandler(onBack = onBack)

    LaunchedEffect(metadata?.source) {
        if (metadata?.source != "radio") onBack()
    }
    if (metadata?.source != "radio") return

    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val media3 = BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle
    val analyzer = media3?.player?.let(PlaybackEngineFactory::audioSpectrum)
    val visualizer = preferences.audioVisualizerModeKey

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            visualizer == "projectm" && ProjectMAvailability.isAvailable && analyzer != null -> {
                ProjectMVisualizer(
                    analyzer = analyzer,
                    intervalSeconds = preferences.projectmPresetIntervalSeconds,
                    transitionSeconds = preferences.projectmTransitionSeconds,
                    qualityKey = preferences.projectmQualityKey,
                    categoryKey = preferences.projectmPresetCategoryKey,
                    showControls = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            visualizer != "off" && analyzer != null -> {
                AudioPlaybackVisualizer(
                    modifier = Modifier.fillMaxSize(),
                    analyzer = analyzer,
                )
            }
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!metadata.artworkUrl.isNullOrBlank()) {
                        PlaybackArtworkCard(
                            artworkUrl = metadata.artworkUrl,
                            fallbackUrls = metadata.artworkFallbackUrls,
                            title = metadata.title,
                            headers = metadata.artworkHeaders,
                            modifier = Modifier.size(320.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        androidx.compose.material3.Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                metadata.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            metadata.subtitle.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvActionButton(
                    text = stringResource(R.string.companion_previous),
                    onClick = viewModel::previous,
                    enabled = viewModel.hasPrevious(),
                )
                TvActionButton(
                    text = stringResource(if (session.playing) R.string.player_pause else R.string.player_play),
                    onClick = BackgroundPlaybackSession::userToggle,
                )
                TvActionButton(
                    text = stringResource(R.string.companion_next),
                    onClick = viewModel::next,
                    enabled = viewModel.hasNext(),
                )
                TvActionButton(
                    text = stringResource(R.string.player_stop),
                    onClick = {
                        BackgroundPlaybackSession.stop()
                        onBack()
                    },
                )
                TvActionButton(
                    text = stringResource(R.string.back),
                    onClick = onBack,
                )
            }
        }
    }
}

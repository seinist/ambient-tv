package com.ambient.tv.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.overlayPanelBorder
import com.ambient.tv.ui.common.overlayPanelBrush
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.LocalUiTokens
import kotlin.math.roundToInt

internal fun gameLoadingProgress(value: Float?): Float? =
    value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

private data class LoadingPresentation(val coreName: String?, val progress: Float?)

/** Only the visual loading card: the player still owns preparation, cancellation and errors. */
@Composable
internal fun GameLoadingDialog(
    state: GameUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = when (state) {
        is GameUiState.Preparing -> LoadingPresentation(null, gameLoadingProgress(state.progress))
        is GameUiState.DownloadingCore -> LoadingPresentation(state.core.name, gameLoadingProgress(state.progress))
        else -> null
    }
    // Never leave a fading cancel control over a confirmation or error panel.
    if (current == null && state !is GameUiState.Ready) return
    val reduced = LocalPerformanceProfile.current.reducedEffects
    val duration = if (reduced) 0 else LocalUiTokens.current.animationFastMs
    var last by remember { mutableStateOf(current) }
    SideEffect { if (current != null) last = current }
    val visibility = remember { MutableTransitionState(reduced && current != null) }
    visibility.targetState = current != null
    val presentation = current ?: last ?: return
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier,
        enter = fadeIn(tween(duration)),
        exit = fadeOut(tween(duration)),
    ) {
        GameLoadingCard(presentation, active = current != null, onCancel = onCancel)
    }
}

@Composable
private fun GameLoadingCard(
    presentation: LoadingPresentation,
    active: Boolean,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tokens = LocalUiTokens.current
    val handheld = LocalHandheldUi.current
    val shape = RoundedCornerShape(tokens.panelRadius)
    val focus = remember { FocusRequester() }
    LaunchedEffect(active, handheld) {
        if (active && !handheld) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = if (handheld) 320.dp else 360.dp)
            .fillMaxWidth()
            .background(overlayPanelBrush(), shape)
            .border(1.dp, overlayPanelBorder(), shape)
            .padding(horizontal = if (handheld) 18.dp else 28.dp, vertical = if (handheld) 14.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (handheld) 8.dp else 12.dp),
    ) {
        Icon(Icons.Filled.SportsEsports, contentDescription = null,
            modifier = Modifier.size(if (handheld) 26.dp else 34.dp), tint = colors.primary)
        Text(stringResource(R.string.game_launch_loading_title),
            style = if (handheld) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = colors.onSurface, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    presentation.coreName?.let { stringResource(R.string.game_core_loading, it) }
                        ?: stringResource(R.string.game_preparing),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                presentation.progress?.let { fraction ->
                    Text(stringResource(R.string.game_progress, (fraction * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                }
            }
            GameLoadingProgressBar(presentation.progress, animate = active && !LocalPerformanceProfile.current.reducedEffects)
        }
        // The retiring card is purely visual; game input must not activate its old cancel action.
        TvActionButton(stringResource(R.string.player_cancel), onClick = { if (active) onCancel() },
            modifier = Modifier.focusRequester(focus).focusProperties { canFocus = active }, enabled = active)
    }
}

@Composable
private fun GameLoadingProgressBar(progress: Float?, animate: Boolean) {
    val colors = MaterialTheme.colorScheme
    val track = colors.onSurface.copy(alpha = .14f)
    val shape = RoundedCornerShape(2.dp)
    val bar = Modifier.fillMaxWidth().height(4.dp).clip(shape)
    if (progress == null && animate) {
        LinearProgressIndicator(modifier = bar, color = colors.primary, trackColor = track)
    } else {
        // No guessed progress, including in the reduced-effects profile.
        Box(bar.background(track).semantics {
            progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                ?: ProgressBarRangeInfo.Indeterminate
        }) {
            if (progress != null && progress > 0f) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(colors.primary, shape))
            }
        }
    }
}

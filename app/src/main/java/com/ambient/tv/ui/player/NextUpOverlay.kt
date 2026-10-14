package com.ambient.tv.ui.player

import com.ambient.tv.ui.common.requestHeaders
import coil3.request.crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.delay

@Composable
internal fun NextUpOverlay(
    candidate: NextUpCandidate,
    secondsRemaining: Int,
    leadSeconds: Int,
    autoplay: Boolean,
    stillWatching: Boolean,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSkipCredits: (() -> Unit)? = null,
    onAutoplayChanged: ((Boolean) -> Unit)? = null,
) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val playFocus = remember(candidate.source, candidate.mediaId) { FocusRequester() }
    LaunchedEffect(playFocus) {
        delay(100)
        runCatching { playFocus.requestFocus() }
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .then(if (handheld) Modifier.widthIn(max = 560.dp).fillMaxWidth() else Modifier.width(560.dp))
            .background(Color(0xF216181B), shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(if (handheld) 14.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (handheld) 10.dp else 14.dp),
    ) {
        val countdown = nextUpCountdown(autoplay, stillWatching, secondsRemaining)
        val countdownText = when (countdown) {
            NextUpCountdown.CONFIRMATION -> stringResource(R.string.next_up_confirm)
            // The header toggle already says that autoplay is off.
            NextUpCountdown.DISABLED -> if (onAutoplayChanged != null) "" else stringResource(R.string.next_up_autoplay_off)
            NextUpCountdown.READY -> stringResource(R.string.next_up_ready)
            NextUpCountdown.COUNTDOWN -> stringResource(
                if (candidate.mediaType == "episode") R.string.next_up_episode_starts_in else R.string.next_up_item_starts_in,
                secondsRemaining,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (stillWatching) R.string.next_up_still_watching else R.string.next_up_title),
                modifier = if (handheld) Modifier.weight(1f) else Modifier,
                color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            // Portrait phones are too narrow for title, countdown and toggle in one row; the countdown moves below.
            if (!handheld) {
                Text(
                    text = countdownText,
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            onAutoplayChanged?.let { change ->
                TvActionButton(
                    stringResource(if (autoplay) R.string.next_up_autoplay_on else R.string.next_up_autoplay_off),
                    { change(!autoplay) },
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        toggleableState = ToggleableState(autoplay)
                    },
                    selected = autoplay,
                )
            }
        }
        if (handheld && countdownText.isNotEmpty()) {
            Text(countdownText, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (countdown == NextUpCountdown.COUNTDOWN) {
            LinearProgressIndicator(
                progress = { (secondsRemaining.toFloat() / leadSeconds.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(if (handheld) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            NextUpImage(candidate, compact = handheld)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(candidate.title, color = Color.White, fontSize = if (handheld) 18.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (candidate.subtitle.isNotBlank()) Text(candidate.subtitle, color = Color.White.copy(alpha = 0.64f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val metadata = buildList {
                    if (candidate.mediaType == "episode") {
                        candidate.season?.takeIf { it >= 0 }?.let { add("S${it.toString().padStart(2, '0')}") }
                        candidate.episode?.takeIf { it > 0 }?.let { add("E${it.toString().padStart(2, '0')}") }
                    } else candidate.year?.takeIf { it > 0 }?.let { add(it.toString()) }
                    candidate.durationMs?.takeIf { it >= 60_000 }?.let {
                        add(stringResource(R.string.next_up_runtime_minutes, it / 60_000))
                    }
                    candidate.contentRating?.trim()?.takeIf(String::isNotBlank)?.let {
                        val fsk = com.ambient.tv.ui.common.parseGermanFskAge(it)
                        add(if (fsk != null) "FSK $fsk" else it)
                    }
                }.joinToString(" · ")
                if (metadata.isNotBlank()) Text(metadata, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Keep destructive/secondary actions at the outside edge and the selected
        // continuation action at the lower-right, where it is easy to find again.
        if (handheld) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onSkipCredits?.let {
                    TvActionButton(stringResource(R.string.player_skip_credits), it, fillWidth = true)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvActionButton(stringResource(R.string.next_up_close), onDismiss, Modifier.weight(1f), fillWidth = true)
                    TvActionButton(
                        stringResource(if (stillWatching) R.string.next_up_continue else R.string.next_up_play_now),
                        onPlayNow,
                        Modifier.weight(1f).focusRequester(playFocus),
                        fillWidth = true,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onSkipCredits?.let { TvActionButton(stringResource(R.string.player_skip_credits), it) }
                Spacer(Modifier.weight(1f))
                TvActionButton(stringResource(R.string.next_up_close), onDismiss)
                TvActionButton(
                    stringResource(if (stillWatching) R.string.next_up_continue else R.string.next_up_play_now),
                    onPlayNow,
                    Modifier.focusRequester(playFocus),
                )
            }
        }
    }
}

/** Changes geometry with the fallback, so a missing episode still never crops a season poster. */
@Composable
private fun NextUpImage(candidate: NextUpCandidate, compact: Boolean) {
    val choices = remember(candidate) { candidate.artworkChoices() }
    var index by remember(choices) { mutableIntStateOf(0) }
    val artwork = choices.getOrNull(index) ?: return
    val context = LocalContext.current
    val request = remember(artwork, candidate.artworkHeaders, context) {
        ImageRequest.Builder(context).data(artwork.url).apply {
            requestHeaders(candidate.artworkHeaders)
        }.crossfade(false).build()
    }
    AsyncImage(
        model = request, contentDescription = candidate.title,
        modifier = Modifier.width(if (artwork.poster) { if (compact) 64.dp else 88.dp } else { if (compact) 120.dp else 192.dp })
            .height(if (artwork.poster) { if (compact) 96.dp else 132.dp } else { if (compact) 68.dp else 108.dp }).clip(RoundedCornerShape(8.dp)),
        contentScale = if (artwork.poster) ContentScale.Fit else ContentScale.Crop,
        colorFilter = com.ambient.tv.ui.display.uiHdrBitmapColorFilter(
            com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f,
        ),
        onError = { index++ },
    )
}

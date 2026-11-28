package com.ambient.tv.ui.moonlight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import com.ambient.tv.moonlight.moonlightBoundedText
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.moonlight.MoonlightStreamProfile
import com.ambient.tv.moonlight.MoonlightStreamStatus
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightPanelInfoRow
import com.ambient.tv.ui.common.RightPanelOverlay
import com.ambient.tv.ui.common.RightPanelSectionTitle
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.common.SystemLogo
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField

/**
 * Moonlight shares Ambient TV's right-anchored Settings/Playback drawer and its DPAD focus,
 * scrolling and entrance/exit behavior. It does not pretend a GameStream desktop has a
 * media seekbar, pause button, skip chapters or a local decoder/renderer selector.
 */
internal enum class MoonlightPlaybackPage { NONE, ROOT, QUALITY, DISPLAY, TEXT, INFO, QUIT }

@Composable
internal fun BoxScope.MoonlightPlaybackPanel(
    page: MoonlightPlaybackPage,
    status: MoonlightStreamStatus,
    metricsSource: StateFlow<MoonlightStreamStatus>,
    streamProfile: MoonlightStreamProfile,
    activeProfile: MoonlightStreamProfile,
    displayResult: MoonlightDisplayResult,
    onUpdateProfile: (MoonlightStreamProfile) -> Unit,
    onPage: (MoonlightPlaybackPage) -> Unit,
    onBack: () -> Unit,
    onSendText: (String) -> Unit,
    onQuitRemote: () -> Unit,
    remoteQuitBusy: Boolean,
    remoteQuitError: Boolean,
) {
    if (page == MoonlightPlaybackPage.NONE) return
    RightPanelOverlay(onOutsideTap = { onPage(MoonlightPlaybackPage.NONE) }) {
        // The same right anchor as GameSidePanel; inner levels never slide in again.
        RightSidePanel(
            title = when (page) {
                MoonlightPlaybackPage.ROOT -> stringResource(R.string.moonlight_player_settings)
                MoonlightPlaybackPage.QUALITY -> stringResource(R.string.moonlight_quality_title)
                MoonlightPlaybackPage.DISPLAY -> stringResource(R.string.moonlight_display_title)
                MoonlightPlaybackPage.TEXT -> stringResource(R.string.moonlight_player_input)
                MoonlightPlaybackPage.INFO -> stringResource(R.string.moonlight_player_info)
                MoonlightPlaybackPage.QUIT -> stringResource(R.string.moonlight_quit_remote)
                MoonlightPlaybackPage.NONE -> ""
            },
            subtitle = status.title.takeIf { it.isNotBlank() },
            modifier = Modifier.align(Alignment.CenterEnd),
            onBack = onBack,
            backWithinPanel = page != MoonlightPlaybackPage.ROOT,
            closeInsteadOfBack = page == MoonlightPlaybackPage.ROOT,
            header = { SystemLogo(MediaSystem.MOONLIGHT) },
        ) {
            when (page) {
                MoonlightPlaybackPage.ROOT -> {
                    RightPanelSectionTitle(stringResource(R.string.moonlight_player_settings))
                    RightPanelChoice(
                        text = stringResource(R.string.moonlight_quality_title),
                        subtitle = stringResource(R.string.moonlight_player_apply_next),
                        selected = false,
                        onClick = { onPage(MoonlightPlaybackPage.QUALITY) },
                    )
                    RightPanelChoice(
                        text = stringResource(R.string.moonlight_display_title),
                        subtitle = stringResource(R.string.moonlight_player_apply_next),
                        selected = false,
                        onClick = { onPage(MoonlightPlaybackPage.DISPLAY) },
                    )
                    RightPanelChoice(
                        text = stringResource(R.string.moonlight_player_input),
                        subtitle = stringResource(R.string.moonlight_player_input_hint),
                        selected = false,
                        enabled = status.phase == com.ambient.tv.moonlight.MoonlightStreamPhase.STREAMING,
                        onClick = { onPage(MoonlightPlaybackPage.TEXT) },
                    )
                    RightPanelChoice(
                        text = stringResource(R.string.moonlight_player_info),
                        selected = false,
                        onClick = { onPage(MoonlightPlaybackPage.INFO) },
                    )
                    RightPanelChoice(
                        text = stringResource(R.string.moonlight_quit_remote),
                        selected = false,
                        enabled = status.phase == com.ambient.tv.moonlight.MoonlightStreamPhase.STREAMING,
                        onClick = { onPage(MoonlightPlaybackPage.QUIT) },
                    )
                }
                MoonlightPlaybackPage.QUALITY -> {
                    RightPanelInfoRow(stringResource(R.string.moonlight_player_apply_next))
                    MoonlightStreamSettingsSection(streamProfile, onUpdateProfile, includeDisplay = false)
                }
                MoonlightPlaybackPage.DISPLAY -> {
                    RightPanelInfoRow(stringResource(R.string.moonlight_player_apply_next))
                    MoonlightDisplaySettingsSection(streamProfile, onUpdateProfile)
                }
                MoonlightPlaybackPage.TEXT -> {
                    // Clipboard and typed text are transient, never saved in Activity state.
                    val context = LocalContext.current
                    var message by remember { mutableStateOf("") }
                    Text(stringResource(R.string.moonlight_player_input_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TvTextField(
                        value = message,
                        onValueChange = { message = moonlightBoundedText(it) },
                        label = stringResource(R.string.moonlight_player_input_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvActionButton(
                        text = stringResource(R.string.moonlight_player_paste_text),
                        onClick = {
                            // Read only after an explicit click in the foreground. Never coerce
                            // content URIs or intents to text, or send clipboard data automatically.
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val text = runCatching {
                                if (clipboard?.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true)
                                    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                                else null
                            }.getOrNull()
                            if (text != null) message = moonlightBoundedText(text)
                        },
                    )
                    TvActionButton(
                        text = stringResource(R.string.moonlight_player_send_text),
                        enabled = message.isNotBlank() && status.phase == com.ambient.tv.moonlight.MoonlightStreamPhase.STREAMING,
                        onClick = {
                            onSendText(message)
                            message = ""
                            onPage(MoonlightPlaybackPage.ROOT)
                        },
                    )
                }
                MoonlightPlaybackPage.INFO -> {
                    RightPanelSectionTitle(stringResource(R.string.moonlight_player_info))
                    status.title.takeIf(String::isNotBlank)?.let { RightPanelInfoRow(it) }
                    status.detail.takeIf(String::isNotBlank)?.let { RightPanelInfoRow(it) }
                    RightPanelInfoRow(stringResource(when (status.phase) {
                        com.ambient.tv.moonlight.MoonlightStreamPhase.STREAMING -> R.string.moonlight_stream_running
                        com.ambient.tv.moonlight.MoonlightStreamPhase.ENDED -> R.string.moonlight_stream_ended
                        com.ambient.tv.moonlight.MoonlightStreamPhase.FAILED -> R.string.moonlight_stream_failed
                        else -> R.string.moonlight_stream_connecting
                    }))
                    status.error?.let { RightPanelInfoRow(it) }
                    RightPanelInfoRow(stringResource(R.string.moonlight_display_source,
                        activeProfile.width, activeProfile.height, activeProfile.fps))
                    RightPanelInfoRow(stringResource(R.string.moonlight_display_actual,
                        displayResult.mode?.label ?: stringResource(R.string.moonlight_display_unknown)))
                    if (displayResult.unavailable) RightPanelInfoRow(stringResource(R.string.moonlight_display_fallback))
                    val detailed by metricsSource.collectAsState()
                    val metrics = if (detailed.owner === status.owner && detailed.generation == status.generation)
                        detailed.metrics else com.ambient.tv.moonlight.MoonlightStreamMetrics()
                    val video = metrics.video
                    if (video.width > 0) {
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_video, video.mime.substringAfter('/'),
                            video.width, video.height, video.fps,
                            stringResource(if (video.hdr) R.string.moonlight_stats_hdr else R.string.moonlight_stats_sdr)))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_output, metrics.outputFps))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_decoder, video.decoder.ifBlank { "–" }))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_color,
                            video.outputColorStandard?.toString() ?: "–", video.outputColorTransfer?.toString() ?: "–",
                            video.outputColorRange?.toString() ?: "–", video.outputHdrStaticBytes?.toString() ?: "–"))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_hdr_verification,
                            stringResource(when (video.hdrOutputEvidence) {
                                "confirmed" -> R.string.moonlight_hdr_output_confirmed
                                "mismatch" -> R.string.moonlight_hdr_output_mismatch
                                else -> R.string.moonlight_hdr_output_unknown
                            })))
                        if (video.presented > 0) RightPanelInfoRow(stringResource(R.string.moonlight_stats_presented, metrics.presentedFps))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_drops, video.missing, video.dropped))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_video_queue, metrics.pendingVideoFrames))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_latency_p95,
                            video.assemblyP95Ms?.toString() ?: "–", video.releaseP95Ms?.toString() ?: "–",
                            video.presentationP95Ms?.toString() ?: "–"))
                        if (video.rendered > 0)
                            RightPanelInfoRow(stringResource(R.string.moonlight_stats_decode,
                                video.decodeMicros / video.rendered / 1000.0))
                        if (video.hostSamples > 0)
                            RightPanelInfoRow(stringResource(R.string.moonlight_stats_host,
                                video.hostTenthsMs / video.hostSamples / 10.0))
                    }
                    if (metrics.rttMs != null && metrics.rttVarianceMs != null)
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_network,
                            metrics.rttMs, metrics.rttVarianceMs))
                    if (metrics.networkPoor) RightPanelInfoRow(stringResource(R.string.moonlight_stats_network_poor))
                    metrics.inputDispatchP95Ms?.let {
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_input_queue, it, metrics.pendingInputs))
                    }
                    val audio = metrics.audio
                    if (audio.rate > 0) RightPanelInfoRow(stringResource(R.string.moonlight_stats_audio,
                        audio.channels, audio.bufferFrames * 1000.0 / audio.rate, audio.underruns,
                        audio.droppedSamples))
                    audio.estimatedQueuedMs?.let {
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_audio_queue, it))
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_audio_native_queue, audio.nativeQueuedMs))
                    }
                    if (audio.recovering) RightPanelInfoRow(stringResource(R.string.moonlight_stats_audio_recovering))
                    metrics.avQueueDifferenceMs?.let {
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_av_queue_difference, it))
                    }
                    if (metrics.audioQueueReductionMs > 0)
                        RightPanelInfoRow(stringResource(R.string.moonlight_stats_audio_queue_reduction, metrics.audioQueueReductionMs))
                    val context = LocalContext.current
                    TvActionButton(
                        text = stringResource(R.string.moonlight_copy_diagnostics),
                        enabled = video.width > 0,
                        onClick = {
                            // Explicit local export: no host names, IDs, pairing data or text.
                            val report = com.ambient.tv.moonlight.moonlightDiagnosticReport(metrics,
                                displayResult.hdr10, displayResult.mode?.label.orEmpty())
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Moonlight diagnostics", report))
                        },
                    )
                }
                MoonlightPlaybackPage.QUIT -> {
                    Text(stringResource(R.string.moonlight_quit_confirm_hint),
                        style = MaterialTheme.typography.bodyMedium)
                    if (remoteQuitError) {
                        Text(stringResource(R.string.moonlight_remote_quit_failed),
                            color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvActionButton(
                            text = stringResource(R.string.files_cancel),
                            onClick = onBack,
                            enabled = !remoteQuitBusy,
                        )
                        TvActionButton(
                            text = stringResource(R.string.moonlight_quit_remote),
                            onClick = onQuitRemote,
                            enabled = !remoteQuitBusy,
                            loading = remoteQuitBusy,
                        )
                    }
                }
                MoonlightPlaybackPage.NONE -> Unit
            }
        }
    }
}

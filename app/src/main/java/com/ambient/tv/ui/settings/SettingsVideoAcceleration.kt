package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.player.HdrMedia3Hint
import com.ambient.tv.ui.player.ImageHint
import com.ambient.tv.ui.player.RuntimeCodecSupport
import com.ambient.tv.ui.player.mpvDecoderLabel
import com.ambient.tv.ui.player.backend.*
import kotlinx.coroutines.launch

@Composable
internal fun SettingsVideoAcceleration(state: SettingsUiState, model: SettingsViewModel, backend: String) {
    val context = LocalContext.current
    val caps by VideoAccelerationRuntime.capabilities.collectAsState()
    val checking by VideoAccelerationRuntime.checking.collectAsState()
    val evidence by VideoAccelerationRuntime.playbackEvidence.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(backend) {
        if (backend in setOf("mpv", "vlc")) VideoAccelerationRuntime.refresh(context)
    }
    key(backend) {
        RendererVideoSettings(
            backend = backend,
            output = if (backend == "mpv") state.mpvVideoOutputModeKey else state.vlcVideoOutputModeKey,
            decoder = state.mpvVideoDecoderKey,
            caps = caps, checking = checking, evidence = evidence[backend] ?: VulkanPlaybackEvidence(),
            changeOutput = { if (backend == "mpv") model.onMpvVideoOutputModeChanged(it) else model.onVlcVideoOutputModeChanged(it) },
            changeDecoder = model::onMpvVideoDecoderChanged,
            hdrGpu = state.mpvHdrGpuEnabled,
            changeHdrGpu = model::onMpvHdrGpuChanged,
            checkAgain = { scope.launch { VideoAccelerationRuntime.refresh(context, force = true) } },
        )
    }
}

/** Production content also used by the account-free debug UI test. */
@Composable
internal fun RendererVideoSettings(
    backend: String,
    output: String,
    decoder: String,
    caps: VideoAccelerationCapabilities,
    checking: Boolean,
    evidence: VulkanPlaybackEvidence,
    changeOutput: (String) -> Unit,
    changeDecoder: (String) -> Unit,
    checkAgain: () -> Unit,
    hdrGpu: Boolean = false,
    /** Null hides "HDR über GPU verarbeiten" (profile editors, catalog). */
    changeHdrGpu: ((Boolean) -> Unit)? = null,
) {
    val renderer = selectedRendererSettingsKey(backend)
    if (renderer == null || renderer == "media3") {
        ImageHint(stringResource(if (renderer == "media3") R.string.renderer_media3_hint else R.string.renderer_auto_hint))
        HdrMedia3Hint()
        return
    }
    val mpv = renderer == "mpv"
    val outputAvailable = if (mpv) caps.mpvVulkanOutput else caps.vlcVulkanOutput
    val built = if (mpv) caps.mpvOutputBuilt else caps.vlcOutputBuilt
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if (mpv) R.string.vulkan_mpv_output else R.string.vulkan_vlc_output),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        val choices = if (mpv) listOf(
            "auto" to stringResource(R.string.player_backend_auto),
            "direct" to stringResource(R.string.renderer_output_direct),
            "gpu" to stringResource(R.string.renderer_output_opengl),
            "vulkan" to stringResource(R.string.renderer_output_vulkan),
        ) else listOf(
            "auto" to stringResource(R.string.player_backend_auto),
            "android" to stringResource(R.string.renderer_output_android),
            "vulkan" to stringResource(R.string.renderer_output_vulkan),
        )
        SettingsSelect(options = buildList {
            val visible = choices.filter { it.first != "vulkan" || outputAvailable }
            val shownOutput = output.takeIf { selected -> visible.any { it.first == selected } } ?: "auto"
            visible.forEach { (value, label) ->
                add(SettingsSelectOption(text = label, selected = value == shownOutput,
                    onClick = { changeOutput(value) }))
            }
        })
        ImageHint(stringResource(when {
            mpv && outputAvailable -> R.string.renderer_mpv_output_hint
            mpv -> R.string.renderer_mpv_output_basic_hint
            outputAvailable -> R.string.renderer_vlc_output_hint
            else -> R.string.renderer_vlc_output_basic_hint
        }))
        HdrMedia3Hint()
        if (mpv) {
            Text(stringResource(R.string.vulkan_decoder), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            val hardware = RuntimeCodecSupport.hasHardwareVideoDecoder
            val vulkanDecoderAvailable = caps.mpvVulkanDecode && output in setOf("auto", "vulkan")
            val visible = mpvDecoderChoices(hardware).filter { it != "vulkan" || vulkanDecoderAvailable }
            val shownDecoder = effectiveMpvDecoder(decoder, hardware).takeIf { it in visible } ?: "auto"
            SettingsSelect(options = buildList {
                visible.forEach { value ->
                    add(SettingsSelectOption(text = mpvDecoderLabel(value), selected = value == shownDecoder,
                        onClick = { changeDecoder(value) }))
                }
            })
            if (vulkanDecoderAvailable) ImageHint(stringResource(R.string.vulkan_decoder_hint))
            // TV feature only ([mpvHdrGpuTvDevice]); phones keep the direct surface for HDR.
            val context = LocalContext.current
            val tvDevice = remember(context) { mpvHdrGpuTvDevice(context) }
            if (changeHdrGpu != null && tvDevice) {
                com.ambient.tv.ui.common.TvSettingRow(
                    title = stringResource(R.string.settings_mpv_hdr_gpu),
                    body = stringResource(R.string.settings_mpv_hdr_gpu_hint),
                    checked = hdrGpu,
                    onCheckedChange = changeHdrGpu,
                )
                // Device-level reason; per-title reasons (Dolby Vision, output, failures) show in the player.
                if (hdrGpu) mpvHdrGpuDeviceReason(caps, com.ambient.tv.ui.player.HdrSupport.displayStandards(context))
                    ?.let { ImageHint(stringResource(com.ambient.tv.ui.player.mpvHdrGpuReasonRes(it))) }
                // The factory output "Direkt" does not block HDR über GPU; say so where both meet.
                if (hdrGpu && output == "direct") ImageHint(stringResource(R.string.settings_mpv_hdr_gpu_direct_hint))
            }
        }
        if (outputAvailable) SettingsGroup(title = stringResource(R.string.renderer_test_title)) {
            if (checking || !caps.checked) ImageHint(stringResource(R.string.vulkan_checking))
            if (caps.device.device.isNotBlank()) {
                ImageHint(stringResource(R.string.renderer_device_result, caps.device.device, vulkanApiLabel(caps.device.version)))
            }
            TestResultLine(R.string.renderer_test_modules, stringResource(when {
                !caps.checked -> R.string.renderer_not_checked
                built -> R.string.renderer_modules_present
                else -> R.string.renderer_modules_missing
            }), failed = caps.checked && !built)
            val problem = vulkanOutputProblem(backend, caps)
            TestResultLine(R.string.renderer_test_output, stringResource(problem ?: R.string.renderer_preflight_pass),
                failed = caps.checked && problem != null)
            ImageHint(stringResource(if (mpv) R.string.renderer_mpv_requirements else R.string.renderer_vlc_requirements))
            if (mpv) {
                TestResultLine(R.string.renderer_test_decode, stringResource(when {
                    !caps.checked -> R.string.renderer_not_checked
                    !caps.mpvDecodeBuilt -> R.string.renderer_decoder_module_missing
                    caps.device.profiles.isEmpty() -> R.string.vulkan_decode_unavailable
                    !caps.mpvVulkanOutput -> R.string.renderer_decoder_needs_output
                    else -> R.string.renderer_decode_profiles_present
                }))
                if (caps.mpvVulkanDecode) ImageHint(caps.device.profiles.joinToString(" · ") {
                    "${it.codec.uppercase()} ${it.bits}-bit ≤ ${it.width}×${it.height}"
                })
            } else ImageHint(stringResource(R.string.renderer_vlc_decoder_hint))
            TestResultLine(R.string.renderer_test_playback, stringResource(when (evidence.stage) {
                VulkanPlaybackStage.NOT_TESTED -> R.string.renderer_playback_untested
                VulkanPlaybackStage.SURFACE_READY -> R.string.renderer_playback_surface
                VulkanPlaybackStage.OUTPUT_INITIALIZED -> R.string.renderer_playback_initialized
                VulkanPlaybackStage.FRAMES_REPORTED -> R.string.renderer_playback_frames
                VulkanPlaybackStage.FAILED -> R.string.renderer_playback_failed
            }), failed = evidence.stage == VulkanPlaybackStage.FAILED)
            if (evidence.stage == VulkanPlaybackStage.FAILED) ImageHint(stringResource(when (evidence.reason) {
                "surface" -> R.string.vulkan_surface_unavailable
                "no_video_frames" -> R.string.renderer_no_frames
                else -> R.string.renderer_runtime_error
            }))
            // Keep this D-pad target focusable during the asynchronous probe.
            // Disabling it moves focus back to the first output chip on TV.
            TvActionButton(stringResource(if (checking) R.string.renderer_test_running else R.string.renderer_test_again),
                onClick = { if (!checking) checkAgain() })
        }
        ImageHint(stringResource(R.string.vulkan_auto_hint))
        if (outputAvailable) ImageHint(stringResource(R.string.renderer_svp_compute_hint))
    }
}

@Composable
private fun TestResultLine(label: Int, result: String, failed: Boolean = false) {
    Text("${stringResource(label)}: $result", style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

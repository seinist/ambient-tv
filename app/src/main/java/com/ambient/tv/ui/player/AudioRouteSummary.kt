package com.ambient.tv.ui.player

import android.media.AudioDeviceInfo
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.AudioOptions

/** What kind of output the sound currently leaves through, for the plain-language summary. */
internal enum class AudioOutputKind(@StringRes val label: Int) {
    HDMI_EARC(R.string.audio_output_kind_hdmi_earc),
    HDMI_ARC(R.string.audio_output_kind_hdmi_arc),
    HDMI(R.string.audio_output_kind_hdmi),
    OPTICAL(R.string.audio_output_kind_optical),
    USB(R.string.audio_output_kind_usb),
    BLUETOOTH(R.string.audio_output_kind_bluetooth),
    HEADPHONES(R.string.audio_output_kind_headphones),
    SPEAKER(R.string.audio_output_kind_speaker),
    OTHER(R.string.audio_output_kind_other),
}

private fun kindOf(type: Int): AudioOutputKind = when (type) {
    AudioDeviceInfo.TYPE_HDMI_EARC -> AudioOutputKind.HDMI_EARC
    AudioDeviceInfo.TYPE_HDMI_ARC -> AudioOutputKind.HDMI_ARC
    AudioDeviceInfo.TYPE_HDMI -> AudioOutputKind.HDMI
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioOutputKind.OPTICAL
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputKind.USB
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputKind.BLUETOOTH
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> AudioOutputKind.HEADPHONES
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputKind.SPEAKER
    else -> AudioOutputKind.OTHER
}

/** The routed devices by priority: a digital link names the output even when the speaker is listed too. */
internal fun audioOutputKind(types: List<Int>): AudioOutputKind? =
    types.map(::kindOf).minByOrNull { it.ordinal }

/** Plain facts for "Lautsprecher 2ch · Bitstream aus"; kept free of resources so it can be tested. */
internal data class AudioRouteSummary(
    val kind: AudioOutputKind?,
    val channels: Int,
    val bitstream: Boolean,
    /** 6 or 8 when "Kanäle erzwingen" raised the width above what the output reports. */
    val forcedChannels: Int = 0,
    /** Passthrough is off although the output confirms encoded formats and PCM stays below 5.1. */
    val stereoMixHint: Boolean = false,
)

internal fun audioRouteSummary(
    route: AudioRouteCapabilities,
    options: AudioOptions,
    bitstreamPermitted: Boolean,
    sourceChannels: Int?,
    forcedChannelsFailed: Boolean = false,
    rejectedCodecs: Set<String> = emptySet(),
): AudioRouteSummary {
    val effective = if (forcedChannelsFailed) options.copy(forceChannels = 0) else options
    val forced = route.forcedBy(effective)
    val pcm = effective.pcmChannels(route.pcmChannels)
    val confirmed = route.passthrough.any { it !in rejectedCodecs }
    val bitstream = bitstreamPermitted && forced.passthrough.any { it in effective.allowedCodecs && it !in rejectedCodecs }
    return AudioRouteSummary(
        kind = audioOutputKind(route.outputTypes),
        channels = pcm,
        bitstream = bitstream,
        forcedChannels = if (forced.pcmChannels > route.pcmChannels) forced.pcmChannels else 0,
        stereoMixHint = !bitstreamPermitted && confirmed && pcm < 6 && (sourceChannels == null || sourceChannels > 2),
    )
}

@Composable
internal fun audioRouteSummaryText(summary: AudioRouteSummary): String {
    val output = stringResource((summary.kind ?: AudioOutputKind.OTHER).label)
    val bitstream = stringResource(if (summary.bitstream) R.string.audio_route_bitstream_on else R.string.audio_route_bitstream_off)
    val forced = if (summary.forcedChannels > 0) " " + stringResource(R.string.audio_route_forced,
        if (summary.forcedChannels >= 8) "7.1" else "5.1") else ""
    return "$output ${summary.channels}ch$forced · $bitstream"
}

@Composable
internal fun audioRouteSummaryText(session: AudioSession): String = audioRouteSummaryText(sessionRouteSummary(session))

internal fun sessionRouteSummary(session: AudioSession): AudioRouteSummary = audioRouteSummary(
    route = session.effectiveRoute,
    options = session.options,
    bitstreamPermitted = session.options.permitsBitstream(session.sourcePassthrough),
    sourceChannels = session.sourceChannels,
    forcedChannelsFailed = session.forcedChannelsFailed || session.forceStereoPcm,
    rejectedCodecs = AudioCodecRejects.snapshot(),
)

package com.ambient.tv.ui.player

import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.layout.fillMaxWidth
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.AudioSettings
import com.ambient.tv.data.local.AudioTrackPreference
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerValueSlider
import com.ambient.tv.ui.common.formatDbLabel
import com.ambient.tv.ui.player.backend.MpvBackendAvailability

@Composable
fun AudioControls(session: AudioSession, section: String? = null, onOpenSection: ((String) -> Unit)? = null) {
    val route = session.route
    AudioProfileEditor(
        session.displaySettings, session.backend, session.sourcePassthrough, session.supportsNight,
        session::selectProfile, session::update, session::trackPreference,
        route = route,
        sourceCodec = session.sourceCodec,
        sourceChannels = session.sourceChannels,
        actualPlan = session.outputPlan(),
        flatLive = true,
        panelSection = section,
        onOpenSection = onOpenSection,
        delayScopeHint = if (session.titleScoped) stringResource(R.string.audio_delay_title_scope) else null,
    )
    session.warning?.let { ImageHint(stringResource(R.string.audio_rejected, it)) }
}

@Composable
fun rememberAudioRoute(): AudioRouteCapabilities {
    val context = LocalContext.current
    var route by remember { mutableStateOf(AudioRouteCapabilities(mpvAvailable = MpvBackendAvailability.isAvailable)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(AudioManager::class.java)
        fun refresh() {
            route = AudioPassthroughPolicy.probe(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable)
        }
        refresh()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }
        manager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterAudioDeviceCallback(callback) }
    }
    return route
}

/** Shared editor for live playback and staged defaults; it never constructs a player. */
@Composable
internal fun AudioProfileEditor(
    settings: AudioSettings,
    backend: String,
    sourcePassthrough: Boolean,
    supportsNight: Boolean,
    selectProfile: (String) -> Unit,
    change: (AudioOptions) -> Unit,
    trackPreference: (AudioTrackPreference) -> Unit,
    route: AudioRouteCapabilities = AudioRouteCapabilities(mpvAvailable = backend == "mpv"),
    sourceCodec: String? = null,
    sourceChannels: Int? = null,
    actualPlan: AudioOutputPlan? = null,
    /** Player only: the delay belongs to the playing title, the profile keeps the default. */
    delayScopeHint: String? = null,
    flatLive: Boolean = false,
    panelSection: String? = null,
    onOpenSection: ((String) -> Unit)? = null,
) {
    val o = settings.currentForRoute(route.profile)
    val codecs = visibleBitstreamCodecs(route).filter { it !in AudioCodecRejects.snapshot() }
    if (!flatLive || panelSection == null) {
        ImageChoice(R.string.audio_profile, settings.selectedProfile, listOf(
            "auto" to stringResource(R.string.audio_profile_auto),
            "tv" to "TV", "avr" to "HDMI / AVR", "headphones" to stringResource(R.string.audio_headphones),
        ), selectProfile)
        if (settings.selectedProfile == "auto") ImageHint(stringResource(R.string.audio_profile_active,
            when (route.profile) {
                "avr" -> "HDMI / AVR"
                "headphones" -> stringResource(R.string.audio_headphones)
                else -> "TV"
            }))
        ImageHint(stringResource(R.string.audio_profile_hint))
    }
    var group by remember(backend, flatLive) { mutableStateOf("output") }
    val dspSuspended = (actualPlan ?: planAudioOutput(
        o, sourceCodec, route, backend, sourcePassthrough,
        rejectedCodecs = AudioCodecRejects.snapshot(),
        sourceChannels = sourceChannels,
    )).let { it.encoded && !it.applyDsp }
    val liveVisibility = if (flatLive) playerPanelAudioVisibility(
        supportsNight = supportsNight,
        routeOffersBitstream = routeOffersBitstream(route.passthrough, AudioCodecRejects.snapshot()),
        engine = backend,
        dspSuspended = dspSuspended,
    ) else null
    val available = buildList {
        add("output" to stringResource(R.string.audio_output))
        add("sound" to stringResource(R.string.audio_sound))
        if (!flatLive || !dspSuspended) {
            add("eq" to stringResource(R.string.audio_equalizer))
        }
        add("tracks" to stringResource(R.string.audio_track_preferences))
    }
    val activeGroup = panelSection ?: group
    val sectionUnavailable = flatLive && activeGroup == "eq" && dspSuspended
    if (flatLive && panelSection == null && onOpenSection != null) {
        available.forEach { (key, title) ->
            com.ambient.tv.ui.common.PlayerPanelEntry(
                icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.VolumeUp,
                title = title,
                value = when (key) {
                    "output" -> outputModeLabel((actualPlan ?: planAudioOutput(o, sourceCodec, route, backend, sourcePassthrough)).mode)
                    "eq" -> stringResource(if (o.eqEnabled) R.string.player_on else R.string.player_off)
                    "sound" -> channelLabel(o.channels)
                    else -> stringResource(R.string.audio_tracks_hint)
                },
                onClick = { onOpenSection(key) },
            )
        }
    } else if (!flatLive) {
        ImageChoice(R.string.audio_category, group, available) { group = it }
    }
    if (sectionUnavailable) ImageHint(stringResource(R.string.audio_requires_pcm))
    val sections = if (sectionUnavailable || (flatLive && panelSection == null && onOpenSection != null)) emptyList() else listOf(activeGroup)
    sections.forEach { section ->
        when (section) {
        "output" -> {
            val outputs = buildList {
                add("source" to stringResource(R.string.audio_source_default))
                add("auto" to stringResource(R.string.player_automatic))
                add("pcm" to "PCM")
                if (codecs.isNotEmpty() && liveVisibility?.bitstream != false) add("bitstream" to stringResource(R.string.audio_prefer_bitstream))
            }
            ImageChoice(R.string.audio_output, o.output, outputs) { change(o.copy(output = it, channels = if (it == "bitstream") "auto" else o.channels)) }
            ImageHint(stringResource(R.string.audio_output_hint))
            PlayerDropUpSelector(stringResource(R.string.audio_delay), "${o.delayMs} ms",
                listOf("-100" to "−100 ms", "-25" to "−25 ms", "0" to stringResource(R.string.audio_reset_delay), "+25" to "+25 ms", "+100" to "+100 ms"),
                selectedKey = "0", onSelected = { change(o.copy(delayMs = if (it == "0") 0 else o.delayMs + it.toInt())) })
            ImageHint(stringResource(R.string.audio_delay_hint))
            delayScopeHint?.let { ImageHint(it) }
            if (codecs.isNotEmpty() && liveVisibility?.bitstream != false && backend != "vlc" && o.permitsBitstream(sourcePassthrough)) {
                if (flatLive && onOpenSection != null) {
                    com.ambient.tv.ui.common.PlayerPanelEntry(
                        icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.VolumeUp,
                        title = stringResource(R.string.audio_codec),
                        value = codecs.filter { it in o.allowedCodecs }.joinToString(" · ", transform = ::codecLabel),
                        onClick = { onOpenSection("codecs") },
                    )
                } else AudioCodecRows(codecs, o, change)
                ImageHint(stringResource(R.string.audio_dts_fallback_hint))
            }
            BitstreamRouteHint(route)
            if (backend == "vlc") ImageHint(stringResource(R.string.audio_vlc_output_hint))
            ImageChoice(R.string.audio_conversion_location, o.conversionLocation, listOf(
                "local" to stringResource(R.string.audio_conversion_local),
                "server" to stringResource(R.string.audio_conversion_server),
            )) { change(o.copy(conversionLocation = it)) }
            ImageHint(stringResource(R.string.audio_conversion_location_hint))
            ImageChoice(R.string.audio_local_fallback, o.localFallback, listOf(
                "auto" to stringResource(R.string.player_automatic),
                "pcm" to stringResource(R.string.audio_fallback_pcm),
                "off" to stringResource(R.string.player_off),
            )) { change(o.copy(localFallback = it, localAc3 = false)) }
            ImageHint(stringResource(R.string.audio_local_fallback_hint))
            val planned = actualPlan ?: planAudioOutput(o, sourceCodec, route, backend, sourcePassthrough,
                rejectedCodecs = AudioCodecRejects.snapshot(), sourceChannels = sourceChannels)
            if (sourceCodec != null) ImageHint(outputModeLabel(planned.mode))
        }
        "codecs" -> {
            if (backend != "vlc" && o.permitsBitstream(sourcePassthrough) && liveVisibility?.bitstream != false) {
                AudioCodecRows(codecs, o, change)
            }
            ImageHint(stringResource(R.string.audio_dts_fallback_hint))
            BitstreamRouteHint(route)
        }
        "sound", "eq" -> {
            val suspended = (actualPlan ?: planAudioOutput(
                o, sourceCodec, route, backend, sourcePassthrough,
                rejectedCodecs = AudioCodecRejects.snapshot(),
                sourceChannels = sourceChannels,
            )).let { it.encoded && !it.applyDsp }
            if (suspended) ImageHint(stringResource(R.string.audio_requires_pcm))
            if (section == "sound") {
                audioDownmixLabel(sourceChannels, actualPlan ?: planAudioOutput(o, sourceCodec, route, backend, sourcePassthrough, sourceChannels = sourceChannels))?.let { ImageHint(it) }
                if (liveVisibility?.channels ?: true) {
                    val modes = if (backend == "vlc") listOf("auto", "stereo") else visibleChannelModes(route)
                    ImageChoice(R.string.audio_channels, o.channels, modes.map { it to channelLabel(it) }) { change(o.copy(channels = it)) }
                    if (!suspended && backend != "vlc" && upmixRowVisible(route.pcmChannels, o.channels)) {
                        ImageChoice(R.string.audio_upmix, if (o.upmix) "on" else "off", imageOnOff()) { change(o.copy(upmix = it == "on")) }
                        ImageHint(stringResource(R.string.audio_upmix_hint))
                    }
                    if (!suspended && backend != "vlc") ImageChoice(R.string.audio_maintain_volume, if (o.maintainDownmixVolume) "on" else "off", imageOnOff()) {
                        change(o.copy(maintainDownmixVolume = it == "on"))
                    }
                }
                if (liveVisibility?.night ?: supportsNight) ImageChoice(R.string.audio_night,
                    if (o.night) "on" else "off", imageOnOff()) { change(o.copy(night = it == "on")) }
                if (!dspSuspended) PlayerValueSlider(
                    label = stringResource(R.string.audio_dialogue),
                    value = o.dialogue,
                    valueRange = 0..6,
                    step = 1,
                    valueLabel = formatDbLabel(o.dialogue),
                ) { change(o.copy(dialogue = it)) }
                if (!dspSuspended) ImageHint(stringResource(R.string.audio_dialogue_hint))
                if (backend != "vlc" && !dspSuspended) ImageChoice(R.string.audio_normalize, if (o.normalize) "on" else "off", imageOnOff()) { change(o.copy(normalize = it == "on")) }
                if (backend == "mpv" && !dspSuspended) {
                    ImageChoice(R.string.audio_replaygain, o.replayGain, listOf(
                        "off" to stringResource(R.string.player_off), "track" to stringResource(R.string.audio_replay_track), "album" to "Album",
                    )) { change(o.copy(replayGain = it)) }
                    ImageHint(stringResource(R.string.audio_replaygain_hint))
                }
                if (backend == "vlc") ImageHint(stringResource(R.string.audio_vlc_effects_hint))
            } else {
                ImageChoice(R.string.audio_equalizer, if (o.eqEnabled) "on" else "off", imageOnOff()) { change(o.copy(eqEnabled = it == "on")) }
                ImageChoice(R.string.audio_eq_preset, "choose", listOf(
                    "choose" to stringResource(R.string.audio_choose_preset),
                    "flat" to stringResource(R.string.audio_flat), "tv" to "TV", "speech" to stringResource(R.string.audio_speech),
                    "headphones" to stringResource(R.string.audio_headphones),
                )) { if (it != "choose") change(o.preset(it)) }
                if (o.eqEnabled) {
                    PlayerValueSlider(
                        label = stringResource(R.string.audio_preamp),
                        value = o.preampDb,
                        valueRange = -12..0,
                        step = 1,
                        valueLabel = formatDbLabel(o.preampDb, showPlus = false),
                    ) { change(o.copy(preampDb = it)) }
                    var band by remember(backend) { mutableIntStateOf(4) }
                    ImageChoice(R.string.audio_band, band.toString(), AudioCatalog.frequencies.mapIndexed { i, hz -> i.toString() to "$hz Hz" }) { band = it.toInt() }
                    PlayerValueSlider(
                        label = stringResource(R.string.audio_gain),
                        value = o.eqDb[band],
                        valueRange = -12..12,
                        step = 1,
                        valueLabel = formatDbLabel(o.eqDb[band], showPlus = false),
                    ) { db ->
                        change(o.copy(eqDb = o.eqDb.mapIndexed { i, value -> if (i == band) db else value }))
                    }
                }
            }
        }
        "tracks" -> {
            val t = settings.tracks
            ImageChoice(R.string.audio_track_role, t.role, listOf(
                "auto" to stringResource(R.string.player_automatic), "main" to stringResource(R.string.audio_main),
                "original" to stringResource(R.string.audio_original), "commentary" to stringResource(R.string.audio_commentary),
                "description" to stringResource(R.string.audio_description),
            )) { trackPreference(t.copy(role = it)) }
            ImageChoice(R.string.audio_track_channels, t.channels, listOf(
                "auto" to stringResource(R.string.player_automatic), "stereo" to "Stereo", "surround" to "Surround",
            )) { trackPreference(t.copy(channels = it)) }
            ImageHint(stringResource(R.string.audio_tracks_hint))
        }
        }
    }
    if (!flatLive || panelSection == null) com.ambient.tv.ui.common.TvActionButton(
        text = stringResource(R.string.audio_reset_profile),
        onClick = { change(AudioOptions()) },
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        fillWidth = true,
    )
}

@Composable
private fun channelLabel(key: String): String = when (key) {
    "auto" -> stringResource(R.string.player_automatic)
    "stereo" -> "Stereo"
    "mono" -> "Mono"
    "left" -> stringResource(R.string.audio_left)
    "right" -> stringResource(R.string.audio_right)
    "5.1" -> stringResource(R.string.audio_channels_51)
    "7.1" -> stringResource(R.string.audio_channels_71)
    else -> key
}

private fun codecLabel(key: String): String = when (key) {
    "ac3" -> "AC3"
    "eac3" -> "E-AC3"
    "dts" -> "DTS"
    "dts-hd" -> "DTS-HD"
    "truehd" -> "TrueHD"
    else -> key.uppercase()
}

@Composable
private fun outputModeLabel(mode: AudioOutputMode): String = when (mode) {
    AudioOutputMode.PASSTHROUGH -> stringResource(R.string.audio_mode_passthrough)
    AudioOutputMode.DTS_CORE -> stringResource(R.string.audio_mode_dts_core)
    AudioOutputMode.LOCAL_AC3 -> stringResource(R.string.audio_mode_local_ac3)
    AudioOutputMode.PCM -> stringResource(R.string.audio_mode_pcm)
}

/** Upmix needs a surround route and is meaningless while a stereo or mono layout is forced. */
internal fun upmixRowVisible(routePcmChannels: Int, channels: String): Boolean =
    routePcmChannels >= 6 && channels !in setOf("stereo", "mono", "left", "right")

/** Which bitstream formats the current output confirmed; rows exist only for those. */
internal data class BitstreamRouteSummary(val confirmed: List<String>, val unconfirmed: List<String>)

internal fun bitstreamRouteSummary(route: AudioRouteCapabilities, rejected: Set<String> = emptySet()): BitstreamRouteSummary {
    val confirmed = visibleBitstreamCodecs(route).filter { it !in rejected }
    return BitstreamRouteSummary(confirmed, AudioCatalog.codecs.filter { it !in confirmed })
}

@Composable
private fun BitstreamRouteHint(route: AudioRouteCapabilities) {
    val summary = bitstreamRouteSummary(route, AudioCodecRejects.snapshot())
    if (summary.confirmed.isEmpty()) {
        ImageHint(stringResource(R.string.audio_route_none))
        return
    }
    val text = buildString {
        append(stringResource(R.string.audio_route_confirmed, summary.confirmed.joinToString(" · ", transform = ::codecLabel)))
        if (summary.unconfirmed.isNotEmpty()) {
            append(". ")
            append(stringResource(R.string.audio_route_unconfirmed, summary.unconfirmed.joinToString(" · ", transform = ::codecLabel)))
        }
    }
    ImageHint(text)
}

@Composable
private fun AudioCodecRows(codecs: List<String>, options: AudioOptions, change: (AudioOptions) -> Unit) {
    codecs.forEach { codec ->
        com.ambient.tv.ui.common.RightPanelChoice(
            text = codecLabel(codec),
            subtitle = stringResource(if (codec in options.allowedCodecs) R.string.player_on else R.string.player_off),
            selected = codec in options.allowedCodecs,
            onClick = { change(options.copy(allowedCodecs = toggledAudioCodec(options.allowedCodecs, codec))) },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        )
    }
}

internal fun toggledAudioCodec(allowed: List<String>, codec: String): List<String> {
    val enable = codec !in allowed
    val affected = when {
        codec == "dts-hd" && enable -> listOf("dts", "dts-hd")
        codec == "dts" && !enable -> listOf("dts", "dts-hd")
        else -> listOf(codec)
    }
    return if (enable) (allowed + affected).distinct() else allowed.filterNot { it in affected }
}

internal fun audioSectionTitle(section: String): Int = when (section) {
    "sound" -> R.string.audio_sound
    "eq" -> R.string.audio_equalizer
    "tracks" -> R.string.audio_track_preferences
    "codecs" -> R.string.audio_codec
    else -> R.string.audio_output
}

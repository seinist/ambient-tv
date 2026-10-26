package com.ambient.tv.ui.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

data class AudioPassthroughDecision(
    val requested: Boolean,
    val codec: String?,
    val encoding: Int?,
    val supported: Boolean,
    val outputName: String? = null,
    val reason: String,
)

/** Shared passthrough policy. The user setting only permits passthrough; the codec and the
 * current Android audio route decide whether compressed bitstream output is really available. */
object AudioPassthroughPolicy {
    private val bitstreamCodecs = setOf(
        "ac3", "ac-3", "eac3", "e-ac-3", "ec3", "ec-3",
        "eac3-joc", "e-ac-3-joc", "ec3-joc", "dolby-atmos", "atmos",
        "dts", "dca", "dts-hd", "dtshd", "dts-hra", "dca-ma",
        "truehd", "truehd-atmos", "mlp",
    )

    fun isBitstreamCodec(codec: String?): Boolean {
        val normalized = codec?.trim()?.lowercase()?.replace('_', '-') ?: return false
        return normalized in bitstreamCodecs ||
            normalized.startsWith("dts-") || normalized.contains("truehd") || normalized.contains("eac3")
    }

    /** Pure codec gate retained for unit tests and non-Android callers. */
    fun isBitstreamActive(settingEnabled: Boolean, codec: String?): Boolean =
        settingEnabled && isBitstreamCodec(codec)

    /** Conservative UI guard, not proof of the active output format. Capability queries
     * cannot identify the renderer's actual AudioTrack. Never allow speed processing or a
     * PCM visualizer merely because an HDMI probe failed while bitstream was requested. */
    @Suppress("UNUSED_PARAMETER")
    fun isBitstreamActive(context: Context, settingEnabled: Boolean, codec: String?): Boolean =
        isBitstreamActive(settingEnabled, codec)

    fun assess(context: Context, settingEnabled: Boolean, codec: String?): AudioPassthroughDecision {
        if (!settingEnabled) return AudioPassthroughDecision(false, codec, null, false, reason = "disabled")
        if (!isBitstreamCodec(codec)) return AudioPassthroughDecision(true, codec, null, false, reason = "decoded format")
        val encoding = encodingForCodec(codec)
            ?: return AudioPassthroughDecision(true, codec, null, false, reason = "unknown encoded format")
        return runCatching { routeSupportsDirectEncoding(context, encoding).copy(codec = codec) }
            .getOrElse { AudioPassthroughDecision(true, codec, encoding, false, reason = it.message ?: "route probe failed") }
    }

    internal fun encodingForCodec(codec: String?): Int? {
        val normalized = codec?.trim()?.lowercase()?.replace('_', '-') ?: return null
        return when {
            normalized in setOf("ac3", "ac-3") -> AudioFormat.ENCODING_AC3
            normalized.contains("joc") || normalized in setOf("dolby-atmos", "atmos") -> AudioFormat.ENCODING_E_AC3_JOC
            normalized in setOf("eac3", "e-ac-3", "ec3", "ec-3") || normalized.contains("eac3") -> AudioFormat.ENCODING_E_AC3
            normalized == "dts" || normalized == "dca" -> AudioFormat.ENCODING_DTS
            normalized.startsWith("dts-") || normalized == "dtshd" || normalized == "dca-ma" -> AudioFormat.ENCODING_DTS_HD
            normalized.contains("truehd") || normalized == "mlp" -> AudioFormat.ENCODING_DOLBY_TRUEHD
            else -> null
        }
    }

    private fun routeSupportsDirectEncoding(context: Context, encoding: Int): AudioPassthroughDecision {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val masks = intArrayOf(
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.CHANNEL_OUT_STEREO,
        )
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AudioPassthroughDecision(true, null, encoding, false, reason = "audio service unavailable")
        val direct = masks.any { mask ->
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(48_000)
                .setChannelMask(mask)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                AudioManager.getDirectPlaybackSupport(format, attrs) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
            } else @Suppress("DEPRECATION") AudioTrack.isDirectPlaybackSupported(format, attrs)
        }
        if (!direct) return AudioPassthroughDecision(true, null, encoding, false, reason = "Android direct playback rejected")

        val television = isTelevisionDevice(context)
        val devices = candidateDevices(manager, attrs, television)
        // API 33+: getDirectPlaybackSupport() above was asked for exactly these attributes, i.e.
        // for the routed device. On a TV that is the answer of the TV's own audio HAL, also when
        // it routes to the built-in speakers or ARC; trust it there.
        val routedDirect = android.os.Build.VERSION.SDK_INT >= 33
        val acceptingOutput = devices.firstOrNull()?.takeIf {
            routeAcceptsEncoding(devices, encoding, television = television, routedDirectSupport = routedDirect)
        }
        return if (acceptingOutput != null) {
            AudioPassthroughDecision(
                requested = true,
                codec = null,
                encoding = encoding,
                supported = true,
                outputName = acceptingOutput.name,
                reason = "direct output accepted",
            )
        } else {
            // API 29-32 may evaluate every available route in isDirectPlaybackSupported().
            // Connected sinks are a capability hint, not evidence of this player
            // actually using that route. Diagnostics must not label this as active bitstream.
            AudioPassthroughDecision(true, null, encoding, false, reason = "current digital output does not advertise encoding")
        }
    }

    /** Capabilities of the routed outputs on API 33+, or of the legacy candidate set on older
     * versions. PCM width is the widest relevant device, not the minimum across every output:
     * a built-in stereo sink must not collapse HDMI to a software stereo downmix. Passthrough
     * still requires every candidate to advertise the encoding. This is not proof of a
     * renderer's actual AudioTrack format. */
    @Volatile private var recentRoute: Pair<Long, AudioRouteCapabilities>? = null
    @Volatile private var invalidationRegistered = false

    /**
     * Route capabilities for hot paths (format support on the playback thread, playback start).
     * A full [probe] costs ~20 binder calls; its result is reused for [ROUTE_CACHE_MS] and dropped
     * as soon as an output device appears or disappears. The `mpvAvailable` flag is not part of
     * the probe; callers still add it.
     */
    fun probeCached(context: Context): AudioRouteCapabilities {
        watchDevices(context)
        val now = android.os.SystemClock.elapsedRealtime()
        recentRoute?.let { (at, route) -> if (now - at in 0 until ROUTE_CACHE_MS) return route }
        return probe(context)
    }

    private fun watchDevices(context: Context) {
        if (invalidationRegistered) return
        synchronized(this) {
            if (invalidationRegistered) return
            val manager = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
            manager.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { recentRoute = null }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { recentRoute = null }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            invalidationRegistered = true
        }
    }

    /** Always queries the route; device callbacks use this, and it refreshes [probeCached]. */
    fun probe(context: Context): AudioRouteCapabilities {
        val started = android.os.SystemClock.elapsedRealtime()
        val passthrough = com.ambient.tv.data.local.AudioCatalog.codecs.filter { codec ->
            runCatching { assess(context, true, codec).supported }.getOrDefault(false)
        }.toSet()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        val devices = manager?.let { candidateDevices(it, attrs, isTelevisionDevice(context)) }.orEmpty()
        return AudioRouteCapabilities(passthrough = passthrough, pcmChannels = routedPcmChannels(devices),
            profile = routedAudioProfile(devices), outputTypes = devices.map { it.type })
            .also { recentRoute = started to it }
            .also { logRoute(it, devices) }
    }

    @Volatile private var lastRouteLog: String? = null
    @Volatile private var lastRouteLogDiagnostics = false

    /**
     * One line per distinct route; repeated probes of the same route stay silent. The cache is
     * keyed on the diagnostics switch too, so turning diagnostics on writes the current route
     * into the file instead of waiting for the next route change.
     */
    private fun logRoute(route: AudioRouteCapabilities, devices: List<AudioRouteDevice>) {
        val line = audioRouteLogLine(route, devices)
        val diagnostics = com.ambient.tv.diagnostics.DiagnosticLog.enabled
        if (line == lastRouteLog && diagnostics == lastRouteLogDiagnostics) return
        lastRouteLog = line
        lastRouteLogDiagnostics = diagnostics
        logAudioRouteLine(line)
    }

    private const val ROUTE_CACHE_MS = 2_000L
    internal val PCM_ENCODINGS = setOf(
        AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT,
    )
    /** Format and channel masks of one sink profile, read once on API 31+ ([audioProfilesOf]). */
    private class SinkProfile(val format: Int, val channelMasks: List<Int>, val channelIndexMasks: List<Int>)

    @androidx.annotation.RequiresApi(31)
    private fun audioProfilesOf(device: AudioDeviceInfo): List<SinkProfile> =
        device.audioProfiles.map { SinkProfile(it.format, it.channelMasks.toList(), it.channelIndexMasks.toList()) }

    /** FEATURE_LEANBACK or UI_MODE_TYPE_TELEVISION: a TV or TV box, not a phone or tablet. */
    private fun isTelevisionDevice(context: Context): Boolean = runCatching {
        com.ambient.tv.ui.mobile.isTelevision(context) ||
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }.getOrDefault(false)

    private fun candidateDevices(manager: AudioManager, attrs: AudioAttributes, television: Boolean): List<AudioRouteDevice> {
        val known = android.os.Build.VERSION.SDK_INT >= 33
        val devices = if (known) manager.getAudioDevicesForAttributes(attrs) else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return selectAudioRouteDevices(devices.map { device ->
            // Sinks often list multichannel PCM only as float/24-bit profiles, or leave
            // audioProfiles empty while channelCounts/channelMasks still carry 6 or 8.
            val profiles = if (android.os.Build.VERSION.SDK_INT >= 31) audioProfilesOf(device) else null
            val profiled = profiledPcmChannels(profiles.orEmpty().map { it.format to (it.channelMasks + it.channelIndexMasks) })
            val legacy = (device.channelCounts.toList() + device.channelMasks.map(Integer::bitCount) +
                device.channelIndexMasks.map(Integer::bitCount)).maxOrNull()
            val pcmProfiles = profiles.orEmpty().filter { it.format in PCM_ENCODINGS }.joinToString(",") { profile ->
                audioEncodingName(profile.format) + "[" +
                    (profile.channelMasks.map(::maskText) + profile.channelIndexMasks.map { "idx:" + maskText(it) }).joinToString("/") + "]"
            }
            val detail = listOf(
                "profiles=" + (profiles?.let { if (it.isEmpty()) "empty" else "${it.size}" } ?: "n/a"),
                "pcmProfiles=" + pcmProfiles.ifEmpty { "none" },
                "legacyCounts=" + device.channelCounts.joinToString("/").ifEmpty { "-" },
                "legacyMasks=" + device.channelMasks.joinToString("/", transform = ::maskText).ifEmpty { "-" },
                "legacyIndexMasks=" + device.channelIndexMasks.joinToString("/", transform = ::maskText).ifEmpty { "-" },
            ).joinToString(" ")
            AudioRouteDevice(device.type, device.encodings.toSet(),
                routePcmChannels(profiled = profiled, legacy = legacy),
                device.productName?.toString(), detail)
        }, known, television)
    }

    private fun maskText(mask: Int): String = "0x" + Integer.toHexString(mask) + "(" + Integer.bitCount(mask) + ")"

    private fun audioEncodingName(format: Int): String = when (format) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM_32"
        else -> "enc$format"
    }

}

/**
 * PCM channels of one output. API 31+ PCM audioProfiles that carry channel masks are
 * authoritative: the legacy channelCounts/channelMasks union covers every encoding (a 7.1 mask
 * may belong to a compressed format only). Without such a PCM profile (API < 31, empty
 * audioProfiles, only compressed profiles, or PCM profiles without masks) the legacy fields are
 * the only evidence and count; with neither the route is stereo.
 */
internal fun routePcmChannels(profiled: Int?, legacy: Int?): Int =
    profiled?.takeIf { it > 0 } ?: legacy?.takeIf { it > 0 } ?: 2

/** Widest channel mask among PCM profiles (format to channel/index masks); null without any. */
internal fun profiledPcmChannels(profiles: List<Pair<Int, List<Int>>>): Int? =
    profiles.filter { it.first in AudioPassthroughPolicy.PCM_ENCODINGS }.flatMap { it.second }
        .maxOfOrNull(Integer::bitCount)?.takeIf { it > 0 }

internal const val AUDIO_ROUTE_LOG_TAG = "audio-route"

/** Writes an audio-route line to logcat (`adb logcat -s audio-route`) and, while diagnostics
 * are on, to the diagnostic file. */
internal fun logAudioRouteLine(line: String) {
    runCatching { android.util.Log.i(AUDIO_ROUTE_LOG_TAG, line) }
    com.ambient.tv.diagnostics.DiagnosticLog.log(com.ambient.tv.diagnostics.DiagnosticLevel.INFO, AUDIO_ROUTE_LOG_TAG, line,
        deduplicate = false)
}

/** Readable name of an AudioDeviceInfo type for logs ("HDMI_ARC", "BUILTIN_SPEAKER"). */
internal fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
    AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
    AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
    else -> "type$type"
}

/** One line per distinct playback decision: the user's switches next to what the route offers. */
internal fun audioPlanLogLine(
    backend: String, passthroughSwitch: Boolean, options: com.ambient.tv.data.local.AudioOptions,
    selectedProfile: String, route: AudioRouteCapabilities, sourceCodec: String?, sourceChannels: Int?,
    plan: AudioOutputPlan, forceFailed: Boolean = false, forceStereo: Boolean = false,
): String = "Audio plan backend=$backend passthroughSwitch=${if (passthroughSwitch) "on" else "off"} " +
    "output=${options.output} profile=$selectedProfile/${route.profile} channels=${options.channels} " +
    "pcmLimit=${options.pcmChannelLimit} forceChannels=${options.forceChannels}${if (forceFailed) "(failed)" else ""} " +
    "source=${sourceCodec ?: "?"}/${sourceChannels ?: "?"}ch routePcmChannels=${route.pcmChannels} " +
    "confirmed=${route.passthrough.sorted().joinToString(",").ifEmpty { "none" }} " +
    "devices=${route.outputTypes.joinToString(",", transform = ::audioDeviceTypeName).ifEmpty { "?" }} " +
    "-> mode=${plan.mode} channels=${plan.channels} spdif=${plan.spdifCodecs.joinToString(",").ifEmpty { "-" }}" +
    if (forceStereo) " forceStereo" else ""

/** Diagnostic text for one probe: the chosen route followed by every kept device. */
internal fun audioRouteLogLine(route: AudioRouteCapabilities, devices: List<AudioRouteDevice>): String =
    "Audio route pcmChannels=${route.pcmChannels} profile=${route.profile} " +
        "passthrough=${route.passthrough.sorted().joinToString(",").ifEmpty { "none" }} devices=${devices.size}" +
        devices.joinToString("") { device ->
            " | type=${audioDeviceTypeName(device.type)} name=${device.name ?: "?"} pcmChannels=${device.pcmChannels} ${device.detail}".trimEnd()
        }

/** On API 29–32 connected digital sinks are only hints. A personal output must not inherit
 * HDMI capabilities, and ambiguous/mirrored outputs use their common capabilities. */
internal data class AudioRouteDevice(
    val type: Int, val encodings: Set<Int>, val pcmChannels: Int, val name: String? = null,
    /** Diagnostics only: the profile and legacy fields behind [pcmChannels]. */
    val detail: String = "",
)

internal fun digitalAudioDevice(type: Int): Boolean = type in setOf(
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
    AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

private val WIRED_PERSONAL = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)

internal fun selectAudioRouteDevices(devices: List<AudioRouteDevice>, routingKnown: Boolean, television: Boolean = false): List<AudioRouteDevice> {
    if (routingKnown) return devices
    // Many TVs list their headphone/line-out jack permanently. Before API 33 an app cannot ask
    // which device is routed, so on a TV a wired output only counts as headphones when no
    // digital output (HDMI, ARC, eARC, optical) is there to take the sound instead.
    val candidates = if (television && devices.any { digitalAudioDevice(it.type) }) devices.filterNot { it.type in WIRED_PERSONAL } else devices
    val personal = candidates.filter { it.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    ) }
    if (personal.isNotEmpty()) return personal
    // Built-in speakers and an attached digital output can coexist. Without a route query,
    // choosing HDMI here would invent an active route; require common capabilities instead.
    return candidates
}

/**
 * Whether bitstream may be sent. Phones and tablets: every candidate must be a digital output
 * advertising the encoding. TVs: when Android confirmed direct playback for the routed device
 * (API 33+), that answer counts, also for built-in speakers or ARC, because the TV's audio HAL
 * handles the bitstream itself. Before API 33 a TV's built-in speakers do not veto a digital
 * output (ARC, eARC, HDMI) that advertises the encoding.
 */
internal fun routeAcceptsEncoding(
    devices: List<AudioRouteDevice>, encoding: Int,
    television: Boolean = false, routedDirectSupport: Boolean = false,
): Boolean {
    if (devices.isEmpty()) return false
    if (television && routedDirectSupport) return true
    val relevant = if (television) devices.filterNot { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else devices
    return relevant.isNotEmpty() && relevant.all {
        digitalAudioDevice(it.type) && (encoding in it.encodings ||
            (encoding == AudioFormat.ENCODING_E_AC3_JOC && AudioFormat.ENCODING_E_AC3 in it.encodings))
    }
}

/** Widest PCM layout among the devices [selectAudioRouteDevices] already kept.
 * Headphones stay stereo; speaker+HDMI must not report the stereo minimum. */
internal fun routedPcmChannels(devices: List<AudioRouteDevice>): Int =
    (devices.maxOfOrNull { it.pcmChannels } ?: 2).coerceAtLeast(2)

/** Choose a profile only when the current route (or legacy candidate set) is unambiguous. */
internal fun routedAudioProfile(devices: List<AudioRouteDevice>): String {
    if (devices.isEmpty()) return "tv"
    val personal = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    if (devices.all { it.type in personal }) return "headphones"
    val hdmi = setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_LINE_DIGITAL)
    return if (devices.all { it.type in hdmi }) "avr" else "tv"
}

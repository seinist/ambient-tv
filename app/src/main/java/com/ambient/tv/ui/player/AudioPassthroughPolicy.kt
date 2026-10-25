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

        val devices = candidateDevices(manager, attrs)
        val acceptingOutput = devices.firstOrNull()?.takeIf { routeAcceptsEncoding(devices, encoding) }
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
        val devices = manager?.let { candidateDevices(it, attrs) }.orEmpty()
        return AudioRouteCapabilities(passthrough = passthrough, pcmChannels = routedPcmChannels(devices),
            profile = routedAudioProfile(devices))
            .also { recentRoute = started to it }
    }

    private const val ROUTE_CACHE_MS = 2_000L
    private val PCM_ENCODINGS = setOf(
        AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT,
    )
    private fun candidateDevices(manager: AudioManager, attrs: AudioAttributes): List<AudioRouteDevice> {
        val known = android.os.Build.VERSION.SDK_INT >= 33
        val devices = if (known) manager.getAudioDevicesForAttributes(attrs) else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return selectAudioRouteDevices(devices.map { device ->
            // Sinks often list multichannel PCM only as float/24-bit profiles, or leave
            // audioProfiles empty while channelCounts/channelMasks still carry 6 or 8.
            val profiles = if (android.os.Build.VERSION.SDK_INT >= 31) device.audioProfiles else null
            val profiled = profiles?.filter { it.format in PCM_ENCODINGS }
                ?.flatMap { it.channelMasks.toList() + it.channelIndexMasks.toList() }?.maxOfOrNull(Integer::bitCount)
            val legacy = (device.channelCounts.toList() + device.channelMasks.map(Integer::bitCount) +
                device.channelIndexMasks.map(Integer::bitCount)).maxOrNull()
            AudioRouteDevice(device.type, device.encodings.toSet(),
                routePcmChannels(profilesReported = !profiles.isNullOrEmpty(), profiled = profiled, legacy = legacy),
                device.productName?.toString())
        }, known)
    }

}

/**
 * PCM channels of one output. API 31+ audioProfiles are authoritative when the device reports
 * any: the legacy channelCounts/channelMasks union over every encoding (a 7.1 mask may belong to
 * a compressed format only) and would announce multichannel PCM the sink cannot take. The legacy
 * fields count only before API 31 or when the device leaves audioProfiles empty.
 */
internal fun routePcmChannels(profilesReported: Boolean, profiled: Int?, legacy: Int?): Int =
    (if (profilesReported) profiled else legacy)?.takeIf { it > 0 } ?: 2

/** On API 29–32 connected digital sinks are only hints. A personal output must not inherit
 * HDMI capabilities, and ambiguous/mirrored outputs use their common capabilities. */
internal data class AudioRouteDevice(val type: Int, val encodings: Set<Int>, val pcmChannels: Int, val name: String? = null)

internal fun digitalAudioDevice(type: Int): Boolean = type in setOf(
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
    AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

internal fun selectAudioRouteDevices(devices: List<AudioRouteDevice>, routingKnown: Boolean): List<AudioRouteDevice> {
    if (routingKnown) return devices
    val personal = devices.filter { it.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    ) }
    if (personal.isNotEmpty()) return personal
    // Built-in speakers and an attached digital output can coexist. Without a route query,
    // choosing HDMI here would invent an active route; require common capabilities instead.
    return devices
}

internal fun routeAcceptsEncoding(devices: List<AudioRouteDevice>, encoding: Int): Boolean = devices.isNotEmpty() && devices.all {
    digitalAudioDevice(it.type) && (encoding in it.encodings ||
        (encoding == AudioFormat.ENCODING_E_AC3_JOC && AudioFormat.ENCODING_E_AC3 in it.encodings))
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

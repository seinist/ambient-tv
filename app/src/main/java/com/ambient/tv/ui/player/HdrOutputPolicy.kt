package com.ambient.tv.ui.player

enum class HdrStandard(val label: String) {
    HDR10("HDR10"),
    HLG("HLG"),
    HDR10_PLUS("HDR10+"),
    DOLBY_VISION("Dolby Vision"),
}

/** Dolby Vision profile from an RFC 6381 codec string such as `dvhe.08.06` or `dav1.10.09`. */
internal fun dolbyVisionProfile(codecs: String?): Int? =
    codecs.orEmpty().split(',').map(String::trim).firstNotNullOfOrNull { codec ->
        val parts = codec.split('.')
        if (parts.size >= 2 && parts[0].lowercase() in setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")) parts[1].toIntOrNull()
        else null
    }

/** Base layer named in a server label such as "Dolby Vision/HDR10" or "DoVi/HLG". */
internal fun dolbyVisionBaseLayer(dynamicRange: String?): HdrStandard? {
    val value = dynamicRange.orEmpty().lowercase()
    return when {
        value.contains("hlg") -> HdrStandard.HLG
        value.contains("hdr10") -> HdrStandard.HDR10
        else -> null
    }
}

/**
 * The HDR format the display actually receives. A source missing from [display] is not lost:
 * HDR10+ plays as its HDR10 base, Dolby Vision 7/8 as its HDR10 (8.1) or HLG (8.4) base layer.
 * Profile 5 has no compatible base layer and needs a Dolby Vision display. Null means the
 * renderer has to tone-map to SDR (or the picture looks washed out).
 */
internal fun hdrOutputStandard(
    source: HdrStandard?,
    dolbyVisionProfile: Int?,
    baseLayer: HdrStandard?,
    display: Set<HdrStandard>,
): HdrStandard? {
    if (source == null) return null
    if (source in display) return source
    val fallback = when (source) {
        HdrStandard.HDR10_PLUS -> HdrStandard.HDR10
        HdrStandard.DOLBY_VISION -> when (dolbyVisionProfile) {
            5 -> null
            // 7 and 8.1 carry HDR10, 8.4 HLG. Without a profile only a named base layer counts,
            // since a bare "Dolby Vision" label is typically profile 5.
            7, 8 -> baseLayer ?: HdrStandard.HDR10
            else -> baseLayer
        }
        else -> null
    }
    return fallback?.takeIf { it in display }
}

/** A compatible HDR display should receive the original decoder surface, not an SDR GPU copy. */
internal fun nativeHdrSurfacePreferred(source: HdrStandard?, display: Set<HdrStandard>): Boolean =
    source != null && hdrOutputStandard(source, null, null, display) != null

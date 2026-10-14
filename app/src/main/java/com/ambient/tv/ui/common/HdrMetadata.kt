package com.ambient.tv.ui.common

/** Canonical source-HDR label shared by heroes, cards and all player backends. */
fun formatHdrMetadata(value: String?, profile: String? = null): String? {
    val original = listOfNotNull(value, profile).joinToString(" ").trim()
    if (original.isBlank()) return null
    val text = original.lowercase()
    return when {
        "dolby vision" in text || "dolbyvision" in text || "dovi" in text || "dvhe" in text -> {
            val dvProfile = Regex("(?i)(?:profile\\s*|\\bp)(\\d+(?:\\.\\d+)?)")
                .find(original)?.groupValues?.getOrNull(1)
            if (dvProfile.isNullOrBlank()) "DOLBY VISION" else "DOLBY VISION P$dvProfile"
        }
        "hdr10+" in text || "hdr10plus" in text || "hdr10 plus" in text -> "HDR10+"
        "hlg" in text || "arib-std-b67" in text -> "HLG"
        "hdr10" in text || "smpte2084" in text || "st2084" in text || Regex("(^|\\s)pq($|\\s)").containsMatchIn(text) -> "HDR10"
        "hdr" in text -> "HDR"
        else -> null
    }
}

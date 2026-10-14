package com.ambient.tv.data.local

/** Algorithm keys are persisted; groups describe approximate cost, not device benchmarks. */
enum class DeinterlaceGroup { AUTOMATIC, LIGHT, BALANCED, DOUBLE_RATE, EXPERT, FILM }

data class DeinterlaceMethod(val key: String, val label: String, val group: DeinterlaceGroup)

object DeinterlaceCatalog {
    val mpv = listOf(
        DeinterlaceMethod("auto", "Auto", DeinterlaceGroup.AUTOMATIC),
        DeinterlaceMethod("bwdif", "BWDIF", DeinterlaceGroup.BALANCED),
        DeinterlaceMethod("yadif", "YADIF", DeinterlaceGroup.BALANCED),
        DeinterlaceMethod("w3fdif", "W3FDIF", DeinterlaceGroup.BALANCED),
        DeinterlaceMethod("bwdif2x", "BWDIF · 2×", DeinterlaceGroup.DOUBLE_RATE),
        DeinterlaceMethod("yadif2x", "YADIF · 2×", DeinterlaceGroup.DOUBLE_RATE),
        DeinterlaceMethod("w3fdif2x", "W3FDIF · 2×", DeinterlaceGroup.DOUBLE_RATE),
        DeinterlaceMethod("estdif", "ESTDIF", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("estdif2x", "ESTDIF · 2×", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("nnedi", "NNEDI", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("nnedi2x", "NNEDI · 2×", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("kerndeint", "KernelDeint", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("mcdeint", "MCDeint · 2× · TFF", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("mcdeint_bff", "MCDeint · 2× · BFF", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("ivtc", "IVTC · 3:2", DeinterlaceGroup.FILM),
    )
    val vlc = listOf(
        DeinterlaceMethod("auto", "Auto", DeinterlaceGroup.AUTOMATIC),
        DeinterlaceMethod("discard", "Discard", DeinterlaceGroup.LIGHT),
        DeinterlaceMethod("mean", "Mean", DeinterlaceGroup.LIGHT),
        DeinterlaceMethod("blend", "Blend", DeinterlaceGroup.LIGHT),
        DeinterlaceMethod("x", "X", DeinterlaceGroup.BALANCED),
        DeinterlaceMethod("yadif", "YADIF", DeinterlaceGroup.BALANCED),
        DeinterlaceMethod("bob", "Bob · 2×", DeinterlaceGroup.DOUBLE_RATE),
        DeinterlaceMethod("linear", "Linear · 2×", DeinterlaceGroup.DOUBLE_RATE),
        DeinterlaceMethod("yadif2x", "YADIF · 2×", DeinterlaceGroup.DOUBLE_RATE),
        DeinterlaceMethod("phosphor", "Phosphor · 2×", DeinterlaceGroup.EXPERT),
        DeinterlaceMethod("ivtc", "IVTC · 3:2", DeinterlaceGroup.FILM),
    )

    fun methods(backend: String) = if (backend == "mpv") mpv else vlc
    fun normalize(backend: String, key: String): String =
        key.takeIf { candidate -> methods(backend).any { it.key == candidate } } ?: "auto"

    /** Previous shared TV setting used VLC vocabulary, including Bob -> YADIF 2x. */
    fun migrateMpv(key: String): String = if (key == "bob") "yadif2x" else normalize("mpv", key)
}

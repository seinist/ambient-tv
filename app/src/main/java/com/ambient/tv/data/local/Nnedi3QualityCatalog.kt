package com.ambient.tv.data.local

/**
 * Stable NNEDI3 quality ladder used by settings, playback and diagnostics.
 *
 * The normal ladder keeps one win8x4 entry per neuron count. The explicit win8x6 Expert
 * entry is only exposed for the largest model, where the extra window is useful as a
 * deliberate quality/performance tradeoff instead of another near-duplicate preset.
 */
data class Nnedi3QualityPreset(
    val key: String,
    val neurons: Int,
    val displayName: String,
    val statsName: String,
    val sourceFileName: String,
    val bundledResourceName: String,
    val window: String = "win8x4",
) {
    val cachedFileName: String = sourceFileName.removeSuffix(".hook") + "-${Nnedi3QualityCatalog.REVISION_SHORT}.hook"
    val upstreamUrl: String =
        "https://raw.githubusercontent.com/bjin/mpv-prescalers/${Nnedi3QualityCatalog.REVISION}/$sourceFileName"
}

object Nnedi3QualityCatalog {
    const val REVISION = "b3f0a59d68f33b7162051ea5970a5169558f0ea2"
    const val REVISION_SHORT = "b3f0a59"

    val presets: List<Nnedi3QualityPreset> = listOf(
        Nnedi3QualityPreset(
            key = "nnedi3_nns16",
            neurons = 16,
            displayName = "NNEDI3 · 16 · Fast",
            statsName = "NNEDI3 Fast (nns16)",
            sourceFileName = "nnedi3-nns16-win8x4.hook",
            bundledResourceName = "nnedi3_nns16_win8x4_b3f0a59",
        ),
        Nnedi3QualityPreset(
            key = "nnedi3_nns32",
            neurons = 32,
            displayName = "NNEDI3 · 32 · Quality",
            statsName = "NNEDI3 Quality (nns32)",
            sourceFileName = "nnedi3-nns32-win8x4.hook",
            bundledResourceName = "nnedi3_nns32_win8x4_b3f0a59",
        ),
        Nnedi3QualityPreset(
            key = "nnedi3_nns64",
            neurons = 64,
            displayName = "NNEDI3 · 64 · High",
            statsName = "NNEDI3 High (nns64)",
            sourceFileName = "nnedi3-nns64-win8x4.hook",
            bundledResourceName = "nnedi3_nns64_win8x4_b3f0a59",
        ),
        Nnedi3QualityPreset(
            key = "nnedi3_nns128",
            neurons = 128,
            displayName = "NNEDI3 · 128 · Very High",
            statsName = "NNEDI3 Very High (nns128)",
            sourceFileName = "nnedi3-nns128-win8x4.hook",
            bundledResourceName = "nnedi3_nns128_win8x4_b3f0a59",
        ),
        Nnedi3QualityPreset(
            key = "nnedi3_nns256",
            neurons = 256,
            displayName = "NNEDI3 · 256 · Extreme",
            statsName = "NNEDI3 Extreme (nns256)",
            sourceFileName = "nnedi3-nns256-win8x4.hook",
            bundledResourceName = "nnedi3_nns256_win8x4_b3f0a59",
        ),
        Nnedi3QualityPreset(
            key = "nnedi3_nns256_win8x6",
            neurons = 256,
            displayName = "NNEDI3 · 256 · Ultra",
            statsName = "NNEDI3 Ultra (nns256, win8x6)",
            sourceFileName = "nnedi3-nns256-win8x6.hook",
            bundledResourceName = "nnedi3_nns256_win8x6_b3f0a59",
            window = "win8x6",
        ),
    )

    val byKey: Map<String, Nnedi3QualityPreset> = presets.associateBy { it.key }
}

internal fun validateNnedi3ShaderSource(source: String, preset: Nnedi3QualityPreset): Boolean {
    if (source.length < 1_000) return false
    if (!source.contains("GNU Lesser General Public License")) return false
    if (!source.contains("//!HOOK LUMA")) return false
    val suffix = "nns${preset.neurons}, ${preset.window})"
    return listOf("double_y", "combine_y", "double_x", "combine_x").all { stage ->
        source.contains("//!DESC NNEDI3 ($stage, $suffix")
    }
}

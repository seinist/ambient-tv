package com.ambient.tv.ui.common.projectm

import kotlin.random.Random

/** A new visualizer follows the same order setting and filtered pool as later changes. */
internal fun initialProjectMPreset(
    presets: List<ProjectMPreset>,
    currentPath: String?,
    shuffle: Boolean,
    chooseIndex: (Int) -> Int = { Random.nextInt(it) },
): ProjectMPreset? {
    // Changing the filter or rebuilding a GL surface must not replace an active/manual preset.
    currentPath?.let { return ProjectMPreset(it) }
    if (presets.isEmpty()) return null
    return presets[if (shuffle) chooseIndex(presets.size) else 0]
}

/** Sequential mode also starts at the first entry when the previous preset is outside the pool. */
internal fun nextProjectMPreset(
    presets: List<ProjectMPreset>,
    currentPath: String?,
    shuffle: Boolean,
    recent: Collection<String>,
    chooseIndex: (Int) -> Int = { Random.nextInt(it) },
): ProjectMPreset? {
    if (presets.isEmpty()) return null
    if (!shuffle || presets.size == 1) {
        val currentIndex = presets.indexOfFirst { it.assetPath == currentPath }
        return presets[(currentIndex + 1) % presets.size]
    }
    val alternatives = presets.filter { it.assetPath != currentPath }
    val candidates = alternatives.filter { it.assetPath !in recent }
        .ifEmpty { alternatives }
        .ifEmpty { presets }
    return candidates[chooseIndex(candidates.size)]
}

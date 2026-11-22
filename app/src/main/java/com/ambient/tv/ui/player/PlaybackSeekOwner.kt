package com.ambient.tv.ui.player

/** A reused decoder is not sufficient ownership after Next Up or a gapless item change. */
internal fun playbackSeekOwnerIsCurrent(
    owner: Any?,
    generation: Long,
    currentOwner: Any?,
    currentGeneration: Long,
    replacingSource: Boolean,
): Boolean = owner != null && owner === currentOwner && generation == currentGeneration && !replacingSource

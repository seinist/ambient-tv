package com.ambient.tv.ui.player

/** Last valid VOD duration, scoped to one media item/version rather than its decoder. */
internal data class PlayerTimelineDuration(
    val sourceIdentity: String? = null,
    val durationMs: Long = 0L,
) {
    fun update(
        sourceIdentity: String,
        durationMs: Long,
        retainDuration: Boolean = true,
        fallbackDurationMs: Long = 0L,
    ): PlayerTimelineDuration =
        PlayerTimelineDuration(
            sourceIdentity = sourceIdentity,
            durationMs = when {
                durationMs > 0L -> durationMs
                retainDuration && this.sourceIdentity == sourceIdentity && this.durationMs > 0L -> this.durationMs
                retainDuration && fallbackDurationMs > 0L -> fallbackDurationMs
                else -> 0L
            },
        )
}

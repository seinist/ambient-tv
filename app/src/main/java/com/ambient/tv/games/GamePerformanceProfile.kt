package com.ambient.tv.games

/** Explicit, reversible overhead choices. Never override the core, shader, HDR or display mode. */
enum class GamePerformanceProfile(
    val key: String,
    val allowsRewind: Boolean,
    val handheldBackdrop: Boolean,
    val reducedBackdropEffects: Boolean,
) {
    STANDARD("standard", true, true, false),
    REDUCED_BACKGROUND("reduced-background", true, true, true),
    LOW_OVERHEAD("low-overhead", false, false, true),
    ;

    val storedKey: String? get() = key.takeUnless { this == STANDARD }

    fun effectiveRewind(configured: Boolean): Boolean = configured && allowsRewind

    companion object {
        fun fromKey(key: String?): GamePerformanceProfile = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

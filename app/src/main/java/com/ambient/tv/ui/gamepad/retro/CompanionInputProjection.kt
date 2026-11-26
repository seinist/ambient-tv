package com.ambient.tv.ui.gamepad.retro

/**
 * Render-independent, main-thread-owned projection of a companion controller.
 * Profile objects are immutable snapshots. Rebuild trigger routing only on a new
 * profile and the digital projection only on a new effective button mask.
 * Pressure and stick samples remain live on every packet; no event is deferred.
 */
internal class CompanionInputProjection {
    private var profile: RetroInputProfile? = null
    private var sourceButtons = -1
    private var leftSources = RetroPadButton.L2.mask
    private var rightSources = RetroPadButton.R2.mask
    var buttons = 0
        private set
    var rewind = false
        private set

    fun update(nextProfile: RetroInputProfile?, nextButtons: Int) {
        if (profile !== nextProfile) {
            profile = nextProfile
            sourceButtons = -1
            leftSources = 0
            rightSources = 0
            for (source in RetroPadButton.entries) {
                val code = companionControllerKey(source)
                if (nextProfile != null && isHotkey(nextProfile, code)) continue
                val target = if (nextProfile == null) source else nextProfile.button(code)
                when (target) {
                    RetroPadButton.L2 -> leftSources = leftSources or source.mask
                    RetroPadButton.R2 -> rightSources = rightSources or source.mask
                    else -> Unit
                }
            }
        }
        if (sourceButtons == nextButtons) return
        sourceButtons = nextButtons
        if (nextProfile == null) {
            buttons = nextButtons
            rewind = false
            return
        }
        // A held hotkey removes its mapped target even if another source shares
        // that target. Preserve the existing union-then-subtract semantics.
        var mapped = 0
        var excluded = 0
        rewind = false
        for (source in RetroPadButton.entries) {
            if (nextButtons and source.mask == 0) continue
            val code = companionControllerKey(source)
            val target = nextProfile.button(code)?.mask ?: 0
            mapped = mapped or target
            if (isHotkey(nextProfile, code)) excluded = excluded or target
            if (nextProfile.rewinds(code)) rewind = true
        }
        buttons = mapped and excluded.inv()
    }

    fun leftPressure(left: Int, right: Int): Int = pressure(leftSources, left, right)
    fun rightPressure(left: Int, right: Int): Int = pressure(rightSources, left, right)

    private fun pressure(sources: Int, left: Int, right: Int): Int {
        val analogSources = RetroPadButton.L2.mask or RetroPadButton.R2.mask
        if (sourceButtons and sources and analogSources.inv() != 0) return RetroPadInput.ANALOG_MAX
        return maxOf(
            if (sources and RetroPadButton.L2.mask != 0) left else 0,
            if (sources and RetroPadButton.R2.mask != 0) right else 0,
        )
    }

    private fun isHotkey(profile: RetroInputProfile, code: Int): Boolean =
        profile.opensMenu(code) || profile.holdOpensMenu(code) ||
            profile.rewinds(code) || profile.fastForwards(code)
}

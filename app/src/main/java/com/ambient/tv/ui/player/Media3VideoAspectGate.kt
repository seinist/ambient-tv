package com.ambient.tv.ui.player

/**
 * PlayerView reports decoder geometry while a video is still being prepared. Those intermediate
 * sizes must not resize the visible letterbox several times before the first video frame.
 *
 * A provider hint owns the startup geometry. The first rendered frame makes the actual display
 * aspect authoritative; subsequent decoder changes may be applied after a short settle period.
 * Explicit user aspect presets always win immediately.
 */
internal class Media3VideoAspectGate {
    private var sourceHint: Float? = null
    private var forcedAspect: Float? = null
    private var decoderAspect: Float? = null

    var hasRenderedFirstFrame: Boolean = false
        private set

    val hasForcedAspect: Boolean get() = forcedAspect != null

    fun configure(forcedAspectRatio: Float?, sourceAspectRatioHint: Float?) {
        forcedAspect = validAspect(forcedAspectRatio)
        sourceHint = validAspect(sourceAspectRatioHint)
    }

    fun resetDecoder() {
        decoderAspect = null
        hasRenderedFirstFrame = false
    }

    fun onDecoderAspectRatio(aspectRatio: Float) {
        validAspect(aspectRatio)?.let { decoderAspect = it }
    }

    fun onFirstFrameRendered(currentAspectRatio: Float? = null) {
        currentAspectRatio?.let(::onDecoderAspectRatio)
        hasRenderedFirstFrame = true
    }

    fun displayedAspectRatio(): Float =
        forcedAspect ?: (if (hasRenderedFirstFrame) decoderAspect else null) ?: sourceHint ?: 0f

    private fun validAspect(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it > 0f }
}

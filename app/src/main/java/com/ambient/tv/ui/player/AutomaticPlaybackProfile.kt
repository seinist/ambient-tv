package com.ambient.tv.ui.player

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import java.util.WeakHashMap

/** A later, more specific profile may replace only the last automatically applied value. */
internal class AutomaticProfileValue<T>(initial: T) {
    private var last = initial
    fun apply(current: T, next: T, update: (T) -> T) {
        if (current == last && current != next) last = update(next)
    }
}

internal class AutomaticVideoProfileState<T>(image: T, deinterlace: Pair<String, String>) {
    val image = AutomaticProfileValue(image)
    val deinterlace = AutomaticProfileValue(deinterlace)
}

/** Main-thread state belongs to the handle, not a panel or fullscreen composition. */
internal object AutomaticVideoProfiles {
    private val mpv = WeakHashMap<MpvPlaybackController, AutomaticVideoProfileState<MpvImageOptions>>()
    private val vlc = WeakHashMap<VlcPlaybackController, AutomaticVideoProfileState<VlcImageOptions>>()
    fun state(handle: MpvPlaybackController) = mpv.getOrPut(handle) {
        AutomaticVideoProfileState(handle.imageOptions, handle.deinterlaceModeKey to handle.deinterlaceFilterKey)
    }
    fun state(handle: VlcPlaybackController) = vlc.getOrPut(handle) {
        AutomaticVideoProfileState(handle.imageOptions, handle.deinterlaceMode to handle.deinterlaceFilter)
    }
    fun transfer(previous: MpvPlaybackController?, next: MpvPlaybackController) {
        mpv[next] = previous?.let(::state) ?: state(next)
    }
    fun transfer(previous: VlcPlaybackController?, next: VlcPlaybackController) {
        vlc[next] = previous?.let(::state) ?: state(next)
    }
}

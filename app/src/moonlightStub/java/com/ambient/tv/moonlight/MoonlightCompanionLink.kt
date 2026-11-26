package com.ambient.tv.moonlight

import javax.inject.Inject

/** Disabled while native Moonlight is absent. */
class MoonlightCompanionLink @Inject constructor() {
    fun attach(owner: Any, title: String, usedPorts: () -> Set<Int>,
        profileContext: com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext? = null, onMenu: () -> Unit) {}
    fun interaction(playing: Boolean, menu: Boolean) {}
    fun reservedPorts(): Set<Int> = emptySet()
    fun republishReservations() {}
    fun rumble(event: MoonlightRumble) {}
    fun detach() {}
}

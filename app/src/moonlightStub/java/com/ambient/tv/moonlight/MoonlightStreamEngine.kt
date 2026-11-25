package com.ambient.tv.moonlight

import android.view.Surface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Production/default builds have no native Moonlight, so playback always fails closed. */
@Singleton
class MoonlightStreamEngine @Inject constructor() {
    private val mutable = MutableStateFlow(MoonlightStreamStatus(
        phase = MoonlightStreamPhase.FAILED, error = "Native Moonlight streaming is not included"))
    val status: StateFlow<MoonlightStreamStatus> = mutable.asStateFlow()

    fun start(hostId: String, appId: Int, surface: Surface, profile: MoonlightStreamProfile): Boolean = false
    fun stop() = Unit
    fun sendKeyboard(virtualKey: Short, pressed: Boolean, modifiers: Byte) = Unit
    fun sendMousePosition(x: Int, y: Int, width: Int, height: Int) = Unit
    fun sendMouseButton(pressed: Boolean) = Unit
    fun sendMouseScroll(clicks: Int) = Unit
    fun sendGamepad(
        port: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ) = Unit
}

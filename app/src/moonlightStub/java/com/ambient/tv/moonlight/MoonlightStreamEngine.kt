package com.ambient.tv.moonlight

import android.view.Surface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Production/default builds have no native Moonlight, so playback always fails closed. */
@Singleton
class MoonlightStreamEngine @Inject constructor() {
    private val mutable = MutableStateFlow(MoonlightStreamStatus(
        phase = MoonlightStreamPhase.FAILED, error = "Native Moonlight streaming is not included"))
    val status: StateFlow<MoonlightStreamStatus> = mutable.asStateFlow()
    private val mutableRumble = MutableSharedFlow<MoonlightRumble>()
    val rumbleEvents = mutableRumble.asSharedFlow()

    fun start(hostId: String, appId: Int, surface: Surface, profile: MoonlightStreamProfile,
              displayRefreshHz: Float? = null, displayHdr10: Boolean? = null): Boolean = false
    fun isBusy(): Boolean = false
    fun stop() = Unit
    fun sendKeyboard(virtualKey: Short, pressed: Boolean, modifiers: Byte) = Unit
    fun sendMousePosition(x: Int, y: Int, width: Int, height: Int) = Unit
    fun sendMouseButton(pressed: Boolean) = Unit
    fun sendMouseScroll(clicks: Int) = Unit
    fun sendTouchEvent(type: Byte, pointerId: Int, x: Float, y: Float,
        pressure: Float = 1f): Int = -5501
    fun sendMotion(port: Int, type: Byte, x: Float, y: Float, z: Float) = Unit
    fun sendMouseMove(dx: Int, dy: Int) = Unit
    fun sendText(text: String) = Unit
    fun sendGamepad(
        port: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ) = Unit
}

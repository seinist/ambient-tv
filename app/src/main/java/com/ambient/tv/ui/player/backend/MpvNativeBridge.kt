package com.ambient.tv.ui.player.backend

import android.os.Build
import android.view.Surface

internal object MpvNativeBridge {
    const val rendererImplemented: Boolean = true

    external fun nativeVersion(): String
    external fun nativeVulkanSupport(): Int
    external fun nativePlaybackError(handle: Long): Int
    external fun nativeEndReason(handle: Long): Int
    external fun nativeCreate(): Long
    external fun nativeInitialize(handle: Long): Int
    external fun nativeDestroy(handle: Long)
    external fun nativeSetOptionString(handle: Long, name: String, value: String): Int
    external fun nativeSetPropertyString(handle: Long, name: String, value: String): Int
    external fun nativeSetPropertyDouble(handle: Long, name: String, value: Double): Int
    external fun nativeGetPropertyString(handle: Long, name: String): String?
    external fun nativeGetPropertyDouble(handle: Long, name: String, fallback: Double): Double
    external fun nativeCommand(handle: Long, args: Array<out String>): Int
    external fun nativeAttachSurface(handle: Long, surface: Surface)
    external fun nativeDetachSurface(handle: Long)
    external fun nativePollEvent(handle: Long): Int

    // Event-driven state: mpv pushes changes; the waiter wakes the MPV owner thread.
    external fun nativeObserveProperty(handle: Long, id: Long, name: String, format: Int): Int
    external fun nativeWakeupFd(handle: Long): Int
    external fun nativeWaitWakeup(fd: Int, timeoutMs: Int): Int
    external fun nativeInterruptWakeup(handle: Long)
    external fun nativeEventPropertyId(handle: Long): Long
    external fun nativeEventPropertyFormat(handle: Long): Int
    external fun nativeEventPropertyFlag(handle: Long): Boolean
    external fun nativeEventPropertyDouble(handle: Long): Double
    external fun nativeEventPropertyString(handle: Long): String?

    // mpv_format values used with nativeObserveProperty.
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_DOUBLE = 5
}

object NativeArmRuntime {
    const val ARM64 = "arm64-v8a"
    const val ARM32 = "armeabi-v7a"
    const val X86 = "x86"
    const val X86_64 = "x86_64"

    /** ABIs for which app-owned native engines (mpv/projectM/Spotify) are packaged. */
    val packagedAbis: Set<String> = setOf(ARM64, ARM32, X86, X86_64)

    val supportedAbis: List<String> get() = Build.SUPPORTED_ABIS?.toList().orEmpty()
    val selectedAbi: String? get() = supportedAbis.firstOrNull { it in packagedAbis }
    val isSupportedDevice: Boolean get() = selectedAbi != null
}

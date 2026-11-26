package com.ambient.tv.ui.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.data.local.MousePointerSettings

/**
 * One app-wide idle deadline for native windows, embedded ViewRoots and the companion cursor.
 * Input is observed, never consumed or captured. Only visibility changes notify UI owners;
 * mouse movement does not recompose the app or allocate a new delayed task on every event.
 */
internal object PointerVisibility {
    // In-process events only; keeps companion hover delivery from waking a second native cursor.
    const val COMPANION_DEVICE_ID = -20_026

    private val idle = PointerIdleState()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val foregroundOwners = mutableSetOf<Any>()
    private val nativeListeners = mutableSetOf<(Boolean) -> Unit>()
    private var companionOwnsPointer = false
    private var publishedVisible = false
    private var scheduledDeadline: Long? = null
    private var lastEventTime = Long.MIN_VALUE
    private var lastDevice = 0
    private var lastAction = -1
    private var lastButtons = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastScrollX = 0f
    private var lastScrollY = 0f

    var hideNativePointer by mutableStateOf(true)
        private set
    var companionVisible by mutableStateOf(false)
        private set

    private val expire = Runnable {
        scheduledDeadline = null
        idle.onTimeout(SystemClock.uptimeMillis())
        publish()
        scheduleExpiry()
    }

    fun start(owner: Any, timeoutSeconds: Int) {
        foregroundOwners.add(owner)
        configure(timeoutSeconds)
    }

    fun stop(owner: Any) {
        if (!foregroundOwners.remove(owner) || foregroundOwners.isNotEmpty()) return
        idle.onFocusLost()
        companionOwnsPointer = false
        lastEventTime = Long.MIN_VALUE
        publish()
        cancelExpiry()
    }

    fun configure(timeoutSeconds: Int) {
        idle.setTimeoutMillis(MousePointerSettings.normalize(timeoutSeconds) * 1_000L, SystemClock.uptimeMillis())
        publish()
        scheduleExpiry()
    }

    /** Each attached native root refreshes its own icon, including SCVH's native carrier. */
    fun observeNativeVisibility(listener: (Boolean) -> Unit): () -> Unit {
        nativeListeners.add(listener)
        listener(hideNativePointer)
        return { nativeListeners.remove(listener) }
    }

    fun onKeyEvent(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled ||
            event.isFromSource(InputDevice.SOURCE_MOUSE)) return
        onNonPointerInput()
    }

    fun onNonPointerInput() {
        // Gameplay stick events can arrive hundreds of times per second. Once hidden,
        // there is no UI snapshot or timer work to repeat until actual pointer activity.
        if (!idle.onNonPointerInput()) return
        publish()
        scheduleExpiry()
    }

    /** Own dialogs transfer focus without stopping the app; release holds but keep its deadline. */
    fun onWindowFocusLost() {
        idle.onWindowFocusLost(SystemClock.uptimeMillis())
        publish()
        scheduleExpiry()
    }

    fun onMotionEvent(event: MotionEvent) {
        if (event.deviceId == COMPANION_DEVICE_ID || !event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.pointerCount == 0) return
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> PointerIdleAction.HOVER_ENTER
            MotionEvent.ACTION_HOVER_EXIT -> PointerIdleAction.HOVER_EXIT
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> PointerIdleAction.MOVE
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> PointerIdleAction.BUTTON_PRESS
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> PointerIdleAction.BUTTON_RELEASE
            MotionEvent.ACTION_CANCEL -> PointerIdleAction.CANCEL
            MotionEvent.ACTION_SCROLL -> PointerIdleAction.SCROLL
            else -> return
        }
        val scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (action == PointerIdleAction.SCROLL && scrollX == 0f && scrollY == 0f) return
        // All wrappers/spies see the same stream. Raw display coordinates stay independent of
        // the current control, window origin and Compose's local coordinate transformations.
        val x = event.rawX
        val y = event.rawY
        if (event.eventTime == lastEventTime && event.deviceId == lastDevice &&
            event.action == lastAction && event.buttonState == lastButtons &&
            x == lastX && y == lastY && scrollX == lastScrollX && scrollY == lastScrollY) return
        lastEventTime = event.eventTime
        lastDevice = event.deviceId
        lastAction = event.action
        lastButtons = event.buttonState
        lastX = x
        lastY = y
        lastScrollX = scrollX
        lastScrollY = scrollY
        val accepted = idle.onPointer(
            SystemClock.uptimeMillis(), x, y, action,
            buttonsDown = event.buttonState != 0 && event.actionMasked != MotionEvent.ACTION_UP,
            sourceId = event.deviceId,
        )
        if (accepted && companionOwnsPointer) {
            companionOwnsPointer = false
            com.ambient.tv.companion.CompanionRemoteInput.hide()
        }
        publish()
        scheduleExpiry()
    }

    /** Coordinates use a stable normalized space; moving to another window is not movement. */
    fun onCompanionInput(action: String, x: Float, y: Float, moved: Boolean) {
        val kind = when (action) {
            "move" -> if (moved) PointerIdleAction.MOVE else PointerIdleAction.HOVER_ENTER
            "click" -> PointerIdleAction.BUTTON_PRESS
            "scroll" -> PointerIdleAction.SCROLL
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        // Enough precision for short phone gestures while filtering sub-pixel numerical noise.
        val accepted = idle.onPointer(now, x * 10_000f, y * 10_000f, kind, sourceId = COMPANION_DEVICE_ID)
        if (accepted) companionOwnsPointer = true
        if (action == "click") {
            // A companion click is an atomic down/up, not a mouse button held by the phone.
            idle.onPointer(now, x * 10_000f, y * 10_000f,
                PointerIdleAction.BUTTON_RELEASE, sourceId = COMPANION_DEVICE_ID)
        }
        publish()
        scheduleExpiry()
    }

    private fun publish() {
        val wasVisible = publishedVisible
        publishedVisible = idle.visible
        companionVisible = idle.visible && companionOwnsPointer
        val hidden = !idle.visible || companionOwnsPointer
        if (hideNativePointer != hidden) {
            hideNativePointer = hidden
            nativeListeners.toList().forEach { it(hidden) }
        }
        if (wasVisible && !idle.visible) {
            // End hover without clearing the existing DPAD focus or moving the actual mouse.
            com.ambient.tv.companion.CompanionRemoteInput.hide()
            com.ambient.tv.ui.common.suppressTvPointerFocus()
        }
    }

    private fun scheduleExpiry() {
        val deadline = idle.deadlineMs?.takeIf { foregroundOwners.isNotEmpty() }
        if (deadline == null) {
            cancelExpiry()
        } else if (scheduledDeadline == null || deadline < scheduledDeadline!!) {
            cancelExpiry()
            scheduledDeadline = deadline
            handler.postAtTime(expire, deadline)
        }
        // A pending earlier callback checks the updated deadline and schedules the remainder.
    }

    private fun cancelExpiry() {
        if (scheduledDeadline != null) handler.removeCallbacks(expire)
        scheduledDeadline = null
    }
}

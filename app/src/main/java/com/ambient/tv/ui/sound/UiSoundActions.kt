package com.ambient.tv.ui.sound

import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Shared feedback contract; device tests can record real control events without speakers. */
interface UiSoundFeedback {
    fun play(kind: UiSound)
    fun prepare() = Unit
    fun navigationInput(keyCode: Int, eventTime: Long) = Unit
    fun preview(kind: UiSound, design: NavSoundDesign, volume: Float, onResult: (Boolean) -> Unit = {}): Boolean = false
    fun stopPreview() = Unit
    fun perform(kind: UiSound, action: () -> Unit)
    fun playFocus() = play(UiSound.FOCUS)
    fun playSelect() = play(UiSound.SELECT)
    fun playBack() = play(UiSound.BACK)
}

/** One accepted activation, even when its navigation callback also requests a Back sound. */
internal class UiSoundActions(private val emit: (UiSound) -> Unit) {
    private data class Action(var sound: UiSound)
    private val current = ThreadLocal<Action>()
    fun request(kind: UiSound) {
        val action = current.get()
        if (action == null) emit(kind)
        else if (kind == UiSound.BACK) action.sound = kind
    }
    fun perform(kind: UiSound, block: () -> Unit) {
        if (current.get() != null) { request(kind); block(); return }
        val action = Action(kind)
        current.set(action)
        try { block() } finally { current.remove(); emit(action.sound) }
    }
}

/** SoundPool.load is asynchronous. Keep only fresh feedback, never a navigation backlog. */
internal class UiSoundLoadGate(private val maxDelayMs: Long = 200) {
    private val ready = mutableSetOf<UiSound>()
    private var pending: Pair<UiSound, Long>? = null
    fun request(kind: UiSound, now: Long): UiSound? {
        // Focus transferred by a click must not overwrite that click while samples load.
        if (kind == UiSound.FOCUS && pending?.let { it.first != UiSound.FOCUS && now - it.second in 0..maxDelayMs } == true) return null
        pending = null
        if (kind in ready) return kind
        pending = kind to now
        return null
    }
    fun loaded(kind: UiSound, succeeded: Boolean, now: Long): UiSound? {
        if (succeeded) ready += kind
        val request = pending?.takeIf { it.first == kind } ?: return null
        pending = null
        return kind.takeIf { succeeded && now - request.second in 0..maxDelayMs }
    }
}

@Composable
fun rememberUiSoundAction(enabled: Boolean = true, kind: UiSound = UiSound.SELECT, onClick: () -> Unit): () -> Unit {
    val player = LocalUiSoundPlayer.current
    val click = rememberUpdatedState(onClick)
    return remember(player, enabled, kind) { {
        if (enabled) {
            if (player != null) player.perform(kind) { click.value() } else click.value()
        }
    } }
}

/** A focus sound belongs to one actual direction press, never to automatic screen entry.
 * The deadline only expires abandoned input; it does not debounce independent clicks.
 */
internal class UiSoundEventGate {
    private var input: Pair<Int, Long>? = null
    private var focusUntil = Long.MIN_VALUE
    fun input(keyCode: Int, eventTime: Long, now: Long) {
        val identity = keyCode to eventTime
        if (input == identity) return
        input = identity
        focusUntil = if (keyCode in 19..22) now + 750 else Long.MIN_VALUE
    }
    fun accept(kind: UiSound, now: Long): Boolean {
        if (kind != UiSound.FOCUS) { focusUntil = Long.MIN_VALUE; return true }
        if (now > focusUntil) return false
        focusUntil = Long.MIN_VALUE
        return true
    }
}

/** Install on roots of separate Compose windows as well as the activity. */
fun androidx.compose.ui.Modifier.navigationSoundInput(): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.composed {
        val sounds = LocalUiSoundPlayer.current
        androidx.compose.ui.Modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == android.view.KeyEvent.ACTION_DOWN)
                sounds?.navigationInput(native.keyCode, native.eventTime)
            false
        }
    })

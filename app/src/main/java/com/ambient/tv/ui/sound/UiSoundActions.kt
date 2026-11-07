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
    fun navigationInputReleased(keyCode: Int) = Unit
    fun suppressNavigationInput(keyCode: Int, eventTime: Long) = Unit
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

/** SoundPool.load is asynchronous. Preserve independent fresh inputs while samples load. */
internal class UiSoundLoadGate(private val maxDelayMs: Long = 200) {
    private val ready = mutableSetOf<UiSound>()
    private val pending = mutableListOf<Pair<UiSound, Long>>()
    fun request(kind: UiSound, now: Long): UiSound? {
        pending.removeAll { now - it.second !in 0..maxDelayMs }
        if (kind in ready) return kind
        pending += kind to now
        return null
    }
    fun loaded(kind: UiSound, succeeded: Boolean, now: Long): List<UiSound> {
        if (succeeded) ready += kind
        val fresh = pending.filter { it.first == kind && now - it.second in 0..maxDelayMs }
        pending.removeAll { it.first == kind || now - it.second !in 0..maxDelayMs }
        return if (succeeded) fresh.map { it.first } else emptyList()
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

/**
 * One feedback event per D-pad down, including keys consumed while focus is still settling.
 * Complete each input on the main queue after dispatch: a synchronous VALUE/BACK/etc. replaces
 * its default FOCUS tick. Focus callbacks never add another tick, even on a later frame.
 */
internal class UiSoundEventGate {
    class Input internal constructor() {
        internal var pending = true
        internal var sound: UiSound? = UiSound.FOCUS
    }

    private var identity: Pair<Int, Long>? = null
    private var current: Input? = null

    fun input(keyCode: Int, eventTime: Long): Input? {
        val identity = keyCode to eventTime
        if (this.identity == identity) return null
        this.identity = identity
        return (if (keyCode in 19..22) Input() else null).also { current = it }
    }

    fun releaseInput(keyCode: Int) {
        // Separate down/up pairs can share a millisecond when Companion input is batched.
        // Only reset deduplication; pending feedback still belongs to its original token.
        if (identity?.first == keyCode) identity = null
    }

    fun accept(kind: UiSound): Boolean {
        if (kind == UiSound.FOCUS) return false
        val input = current ?: return true
        input.sound = kind
        return false
    }

    fun suppressInput(keyCode: Int, eventTime: Long) {
        if (identity != (keyCode to eventTime)) return
        current?.sound = null
        // A selector may dismiss on key-up before the queued completion (Companion dispatches
        // down/up together). Cancel only the fallback; its explicit Back must still be heard.
        current = null
    }

    fun complete(input: Input): UiSound? {
        if (!input.pending) return null
        input.pending = false
        if (current === input) current = null
        return input.sound
    }
}

/** Install on roots of separate Compose windows as well as the activity. */
fun androidx.compose.ui.Modifier.navigationSoundInput(): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.composed {
        val sounds = LocalUiSoundPlayer.current
        androidx.compose.ui.Modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            when (native.action) {
                android.view.KeyEvent.ACTION_DOWN -> sounds?.navigationInput(native.keyCode, native.eventTime)
                android.view.KeyEvent.ACTION_UP -> sounds?.navigationInputReleased(native.keyCode)
            }
            false
        }
    })

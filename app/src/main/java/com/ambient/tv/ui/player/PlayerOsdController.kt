package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Kept for the fullscreen session, including source/renderer replacement. */
internal class PlayerOsdController(initialZapOsd: Boolean = false) {
    private var presentation by mutableStateOf(PlayerOsdPresentation())
    val shownByZap = mutableStateOf(initialZapOsd)
    val mode: PlayerOsdMode get() = presentation.mode
    val expanded: Boolean get() = presentation.expanded
    val closing: Boolean get() = presentation.closing
    val closingFromExpanded: Boolean get() = presentation.closingFromExpanded
    val seekFeedback: Boolean get() = presentation.seekFeedback
    val show: Boolean get() = presentation.show
    var interactionVersion by mutableIntStateOf(0)
        private set
    var transitionVersion by mutableIntStateOf(0)
        private set

    /** Compatibility with engine callbacks; visibility includes the closing phase. */
    val visible: MutableState<Boolean> = object : MutableState<Boolean> {
        override var value: Boolean
            get() = presentation.visible
            set(value) { if (value) reveal() else close() }
        override fun component1(): Boolean = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }

    private fun update(next: PlayerOsdPresentation) {
        if (next != presentation) {
            presentation = next
            transitionVersion++
        }
    }

    fun interact() { interactionVersion++ }

    /** Opening an already expanded OSD must retain its selected action. */
    fun reveal() {
        update(presentation.reveal())
        shownByZap.value = false
        interact()
    }

    fun showCompact() {
        update(presentation.compact())
        shownByZap.value = false
        interact()
    }

    fun showSeekFeedback() {
        update(presentation.compact(feedback = true))
        shownByZap.value = false
        interact()
    }

    fun expand() {
        update(presentation.expand())
        shownByZap.value = false
        interact()
    }

    fun collapse() = showCompact()

    fun close() {
        update(presentation.close())
        shownByZap.value = false
    }

    /** Ignore completion from an exit that was interrupted by another input/transition. */
    fun finishClose(expectedVersion: Int? = null) {
        if (expectedVersion != null && expectedVersion != transitionVersion) return
        update(presentation.finishClose())
    }
}

@Composable
internal fun rememberPlayerOsdController(): PlayerOsdController =
    LocalPlayerChromeState.current?.osd ?: remember { PlayerOsdController() }

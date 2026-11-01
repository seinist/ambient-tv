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

    /**
     * Whether the OSD was last drawn fully on screen. Survives a short remount of the overlay
     * (channel/station change, engine replacement) so a visible OSD does not fade in again.
     */
    internal var onScreen: Boolean = false

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

    /** A changed live channel restarts the info timeout without stealing expanded controls. */
    fun showChannelInfo() {
        val keepControls = presentation.expanded
        update(if (keepControls) presentation.reveal() else presentation.compact())
        // Up/Down keep zapping in compact info mode; OK claims normal navigation.
        shownByZap.value = !keepControls
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

    /** One handheld picture tap opens the action bar from compact, hidden or closing state. */
    fun expandForHandheldTap(): Boolean {
        if (expanded) return false
        expand()
        return true
    }

    fun collapse() = showCompact()

    fun close() {
        update(presentation.close())
        shownByZap.value = false
    }

    /** A timed-out settings panel must not reveal or animate the OSD behind it. */
    fun hideImmediately() {
        update(PlayerOsdPresentation(mode = PlayerOsdMode.HIDDEN))
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

/** Ignore the first metadata emission and repeated ICY/EPG updates for the same station. */
internal fun channelIdentityChanged(previousId: String?, currentId: String?): Boolean =
    previousId != null && currentId != null && previousId != currentId

package com.ambient.tv.notifications

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/** Shares the global connection summary's availability and focus with the TV navigation shell. */
@Stable
class TvWakeFocusState {
    internal val requester = FocusRequester()

    var isAvailable by mutableStateOf(false)
        private set

    var hasFocus by mutableStateOf(false)
        private set

    internal fun attach() { isAvailable = true }

    internal fun detach() {
        isAvailable = false
        hasFocus = false
    }

    internal fun onFocusChanged(focused: Boolean) { hasFocus = focused }

    /** Sidebar Right can reach this action while the destination still has no usable content. */
    fun requestFocus(): Boolean = isAvailable &&
        (hasFocus || runCatching { requester.requestFocus() }.getOrDefault(false))

    /** A removed Cancel must land on Details even while its enclosing focus group still owns focus. */
    internal fun requestEntryFocus(): Boolean = isAvailable &&
        runCatching { requester.requestFocus() }.getOrDefault(false)
}

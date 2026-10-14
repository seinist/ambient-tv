package com.ambient.tv.ui.common

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Owned by panel navigation, so replacing a playback engine cannot reset video controls. */
internal class VideoPanelState {
    var section by mutableStateOf("renderer")
    var expandedSelector by mutableStateOf<String?>(null)
    var focusedSelector by mutableStateOf<String?>(null)

    fun leave() {
        expandedSelector = null
        focusedSelector = null
    }
}

internal val LocalVideoPanelState = compositionLocalOf<VideoPanelState?> { null }

package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Whether a TV text field currently holds focus (or is editing). Screen-wide shortcuts such as
 * the remote's Search key stay out of the way while the user is in a field.
 */
object TvTextFieldFocus {
    private var focusedFields by mutableIntStateOf(0)
    val anyFocused: Boolean get() = focusedFields > 0

    @Composable
    fun Report(active: Boolean) {
        if (active) DisposableEffect(Unit) {
            focusedFields++
            onDispose { focusedFields = (focusedFields - 1).coerceAtLeast(0) }
        }
    }
}

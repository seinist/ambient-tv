package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Insets [content] by [percent] of the display size on every edge; the freed border stays black. */
@Composable
fun OverscanBox(percent: Int, content: @Composable () -> Unit) {
    // Same tree at 0% too: switching structure would remount the app and lose navigation state.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.padding(horizontal = maxWidth * (percent / 100f), vertical = maxHeight * (percent / 100f))) {
            content()
        }
    }
}

package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.ambient.tv.ui.player.LocalPlayerChromeState
import com.ambient.tv.ui.player.PlayerChromeState
import com.ambient.tv.ui.player.PlayerOverlayVisibility

/** Games start immediately; the shared media player's startup info must stay hidden. */
internal fun createGamePlayerChrome(): PlayerChromeState =
    PlayerChromeState().apply { osd.hideImmediately() }

/**
 * Only a paused game menu owns the shared player chrome. Remove its input layer immediately
 * on Continue: a media-style closing animation would cover the newly active touch controls.
 */
@Composable
internal fun GamePlayerMenuOverlay(
    menuOpen: Boolean,
    panelOpen: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!menuOpen) return
    val osd = LocalPlayerChromeState.current?.osd
    DisposableEffect(osd) {
        onDispose { osd?.onScreen = false }
    }
    PlayerOverlayVisibility(
        visible = osd?.visible?.value == true && !panelOpen,
        content = content,
    )
}

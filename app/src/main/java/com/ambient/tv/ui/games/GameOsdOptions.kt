package com.ambient.tv.ui.games

import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.OsdPlayer

/** Keep the Games settings independent of whichever video player's bar was composed last. */
internal fun gameOsdButtons(achievements: Boolean, multipleDiscs: Boolean): List<OsdButton> =
    OsdPlayer.GAMES.buttons.filter { button ->
        when (button) {
            OsdButton.GAME_ACHIEVEMENTS -> achievements
            OsdButton.GAME_DISC -> multipleDiscs
            else -> true
        }
    }

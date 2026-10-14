package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/** Sidebar/media actions can advance the open player without replacing its destination. */
object PlayerItemNavigation {
    internal var owner: Any? = null
    internal var advance: ((String) -> Unit)? = null

    fun playNext(candidate: NextUpCandidate): Boolean {
        val current = BackgroundPlaybackSession.snapshot.value
        if (advance == null) return false
        if (current.replacingSource) return true // Consume repeated Next while the open player loads.
        val next = current.metadata?.nextUp ?: current.metadata?.companionNext ?: return false
        if (next.source != candidate.source || next.mediaId != candidate.mediaId) return false
        advance?.invoke(candidate.mediaId)
        return true
    }
}

@Composable
fun RegisterPlayerItemNavigation(owner: Any, onNext: (String) -> Unit) {
    val action = rememberUpdatedState(onNext)
    DisposableEffect(owner) {
        PlayerItemNavigation.owner = owner
        PlayerItemNavigation.advance = { action.value(it) }
        onDispose {
            if (PlayerItemNavigation.owner === owner) {
                PlayerItemNavigation.owner = null
                PlayerItemNavigation.advance = null
            }
        }
    }
}

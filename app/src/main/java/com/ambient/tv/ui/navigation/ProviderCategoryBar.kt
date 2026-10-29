package com.ambient.tv.ui.navigation

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer

/**
 * Provider pages used to show their sibling categories as a tab bar on top; the sidebar menu
 * already lists them, so the content now gets the full height. The group keeps restoring the
 * last focused item when focus comes back from the sidebar.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ProviderBrowseContent(
    system: String?,
    activeRoute: String,
    viewModel: SidebarViewModel,
    onNavigate: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().focusRestorer().focusGroup()) {
        content()
    }
}

/**
 * Where focus lands when the sidebar hands it to the content. A plain request on the content
 * container picks the top-left focusable, which on library pages is a header chip; a page can
 * register its own entry here. It returns true once it holds focus, null while it is still
 * restoring (ask again next frame), and false to fall back to the plain container request.
 */
object SidebarContentEntry {
    private var owner: Any? = null
    private var enter: (() -> Boolean?)? = null
    var pending: Boolean = false
        private set
    val registered: Boolean get() = enter != null

    internal fun register(owner: Any, enter: () -> Boolean?) {
        this.owner = owner
        this.enter = enter
        pending = false
    }

    internal fun unregister(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        enter = null
        pending = false
    }

    fun requestFocus(): Boolean? {
        val result = enter?.invoke() ?: if (enter == null) false else null
        pending = result == null
        return result
    }
}

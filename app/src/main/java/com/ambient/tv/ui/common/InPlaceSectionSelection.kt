package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.ui.navigation.Destinations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sections of one sidebar system switch inside the live screen instead of through navigation.
 *
 * Every section is also a navigation route (deep links, Home rows, launcher). Selecting one in
 * the sidebar used to navigate: the old destination left the stack, a new screen and view model
 * were created, header and focus tree rebuilt and the content came back only after the new model
 * had read its cache. With the system's screen already on top, the sidebar sends the section key
 * here; the screen applies it to its existing state and reports the shown section back through
 * [displayed], which the sidebar highlight and the remembered route follow.
 */
internal open class InPlaceSectionSelection {
    data class Request(val serial: Long, val key: String)

    private val mutableRequests = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = mutableRequests.asStateFlow()

    /** Section shown by the live screen, with that screen's owner token. */
    private val mutableDisplayed = MutableStateFlow<Pair<Any, String>?>(null)
    val displayed: StateFlow<Pair<Any, String>?> = mutableDisplayed.asStateFlow()

    fun request(key: String) {
        mutableRequests.update { Request((it?.serial ?: 0L) + 1L, key) }
    }

    fun show(owner: Any, key: String) {
        mutableDisplayed.value = owner to key
    }

    /** Only the owner clears its own entry: a replacement screen may already have shown its section. */
    fun hide(owner: Any) {
        mutableDisplayed.update { if (it?.first === owner) null else it }
    }
}

internal object TelegramSectionSelection : InPlaceSectionSelection()
internal object RadioSectionSelection : InPlaceSectionSelection()

/**
 * Reports [shownKey] as the live section while this screen is composed and applies sidebar
 * requests to it while it is resumed. Requests from before this screen entered are ignored.
 */
@Composable
internal fun InPlaceSectionHost(selection: InPlaceSectionSelection, shownKey: String, onRequest: (String) -> Unit) {
    val owner = remember { Any() }
    DisposableEffect(owner, shownKey) {
        selection.show(owner, shownKey)
        onDispose { selection.hide(owner) }
    }
    val apply by rememberUpdatedState(onRequest)
    var handled by remember { mutableLongStateOf(selection.requests.value?.serial ?: 0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(selection, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            selection.requests.collect { request ->
                if (request == null || request.serial <= handled) return@collect
                handled = request.serial
                apply(request.key)
            }
        }
    }
}

/** Section key a sidebar route addresses; the root route is the system's default section. */
internal fun sectionKeyOfRoute(route: String, rootRoute: String, rootKey: String, prefix: String): String? = when {
    route == rootRoute -> rootKey
    route.startsWith(prefix) -> java.net.URLDecoder.decode(route.removePrefix(prefix), "UTF-8").takeIf { it.isNotBlank() }
    else -> null
}

internal fun telegramSectionKeyOfRoute(route: String): String? =
    sectionKeyOfRoute(route, Destinations.TELEGRAM, "recent", "telegram_section/")

internal fun radioSectionKeyOfRoute(route: String): String? =
    sectionKeyOfRoute(route, Destinations.RADIO, "popular", "radio_section/")

/** Whether the system's screen is the current destination, so the switch can stay in place. */
internal fun sectionSwitchesInPlace(currentRoute: String?, liveRoutes: Set<String>, displayedKey: String?, targetKey: String?): Boolean =
    displayedKey != null && targetKey != null && targetKey != displayedKey && currentRoute in liveRoutes

internal val TELEGRAM_SECTION_ROUTES = setOf(Destinations.TELEGRAM, Destinations.TELEGRAM_SECTION)
internal val RADIO_SECTION_ROUTES = setOf(Destinations.RADIO, Destinations.RADIO_SECTION)

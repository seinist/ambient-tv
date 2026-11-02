package com.ambient.tv.ui

/**
 * Allows saved browse state only when the preceding UI is being recreated in this process.
 * A service can keep the process alive after the user has closed the UI, so process lifetime
 * alone is not enough to decide whether its saved focus, scroll and navigation belong here.
 *
 * Call [create] before restoring Activity state, and [destroy] from that owner's onDestroy.
 * Only the Activity that owns the browse UI participates; a redirecting Activity does not.
 * Ordinary background/foreground transitions and navigation do not end the session. Owners
 * must be distinct identity tokens, not Activity references; calls run on the UI thread.
 */
internal class BrowseUiSessionPolicy {
    private var currentOwner: Any? = null
    private var recreationPending = false

    /** Claims the UI session and consumes any permission to restore the preceding UI. */
    fun create(owner: Any, hasSavedState: Boolean): Boolean {
        val allowRestore = hasSavedState && recreationPending
        currentOwner = owner
        recreationPending = false
        return allowRestore
    }

    /** A late destruction of a replaced owner, such as a handheld trampoline, is ignored. */
    fun destroy(owner: Any, changingConfigurations: Boolean) {
        if (currentOwner !== owner) return
        currentOwner = null
        recreationPending = changingConfigurations
    }
}

/**
 * A handheld leaves its root page with Back by moving the task to the background (the Activity
 * stays alive). Opening the app from the launcher afterwards is a new start for the user, so it
 * begins a new browse session at the top, like a cold start. Returning via Recents keeps it.
 */
internal fun launcherStartsFreshUiSession(
    leftFromRoot: Boolean,
    action: String?,
    categories: Set<String>?,
): Boolean = leftFromRoot && action == android.content.Intent.ACTION_MAIN &&
    categories?.contains(android.content.Intent.CATEGORY_LAUNCHER) == true

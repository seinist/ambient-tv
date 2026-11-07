package com.ambient.tv.ui.common

/**
 * Focus targets live only as long as their controls are composed; navigation retains their keys.
 * Several controls can share a key, including outgoing/incoming copies during a panel transition.
 */
internal class PlayerPanelFocusRegistry {
    private class Target(val owner: Any, val request: () -> Boolean)
    private val targets = HashMap<String, MutableList<Target>>()

    internal val registeredTargetCount: Int get() = targets.values.sumOf { it.size }
    internal val registeredKeyCount: Int get() = targets.size

    fun register(key: String, owner: Any, request: () -> Boolean) {
        unregister(key, owner)
        targets.getOrPut(key) { mutableListOf() }.add(Target(owner, request))
    }

    fun unregister(key: String, owner: Any) {
        val owners = targets[key] ?: return
        owners.removeAll { it.owner === owner }
        if (owners.isEmpty()) targets.remove(key)
    }

    fun requestFocus(key: String): Boolean {
        val target = targets[key]?.lastOrNull() ?: return false
        // A new panel may not be attached yet. Retry it next frame, rather than focusing a
        // still-mounted outgoing copy and incorrectly declaring the hand-back complete.
        return runCatching { target.request() }.getOrDefault(false)
    }
}

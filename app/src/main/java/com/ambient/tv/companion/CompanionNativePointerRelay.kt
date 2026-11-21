package com.ambient.tv.companion

/**
 * Emits only actual Libretro pointer changes. Previously each phone drag frame triggered
 * four JNI pointer writes plus mouse and lightgun resets even when only one finger moved.
 *
 * The sender receives normalized source-frame coordinates. Releasing or switching mode
 * clears the old device explicitly; no previous button/trigger can survive the transition.
 */
internal class CompanionNativePointerRelay(
    private val send: (port: Int, kind: Int, index: Int, x: Float, y: Float, flags: Int) -> Unit,
) {
    private val previous = arrayOfNulls<CompanionPointerState>(4)

    fun update(port: Int, next: CompanionPointerState?) {
        if (port !in 0..3) return
        val old = previous[port]
        val oldTouch = old?.takeIf { it.kind == "touch" }
        val newTouch = next?.takeIf { it.kind == "touch" }
        if (oldTouch != null || newTouch != null) {
            for (index in 0..3) {
                val from = oldTouch?.contacts?.firstOrNull { it.index == index && it.pressed }
                val to = newTouch?.contacts?.firstOrNull { it.index == index && it.pressed }
                if (from != to) {
                    send(port, 0, index, to?.x ?: from?.x ?: 0.5f,
                        to?.y ?: from?.y ?: 0.5f, if (to != null) 1 else 0)
                }
            }
        }
        val oldMouse = old?.takeIf { it.kind == "mouse" }
        val newMouse = next?.takeIf { it.kind == "mouse" }
        if (newMouse != null) {
            if (newMouse.dx != 0f || newMouse.dy != 0f || oldMouse?.buttons != newMouse.buttons)
                send(port, 1, 0, newMouse.dx, newMouse.dy, newMouse.buttons)
        } else if (oldMouse != null && oldMouse.buttons != 0) {
            send(port, 1, 0, 0f, 0f, 0)
        }
        val oldGun = old?.takeIf { it.kind == "gun" }
        val newGun = next?.takeIf { it.kind == "gun" }
        if (oldGun != newGun) {
            val point = newGun?.contacts?.firstOrNull()
            val offscreen = newGun?.offscreen == true
            send(port, 2, 0,
                if (offscreen) -1f else point?.x ?: 0.5f,
                if (offscreen) -1f else point?.y ?: 0.5f,
                newGun?.buttons ?: 0)
        }
        previous[port] = next?.let { value ->
            if (value.kind == "mouse") value.copy(dx = 0f, dy = 0f) else value
        }
    }

    fun clear() {
        for (port in 0..3) update(port, null)
    }
}

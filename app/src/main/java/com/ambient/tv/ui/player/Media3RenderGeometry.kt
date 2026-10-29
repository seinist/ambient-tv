package com.ambient.tv.ui.player

import java.lang.ref.WeakReference

/** The menu may use the intended output size; shaders must wait for the real surface callback. */
internal class Media3RenderGeometry {
    private var owner: WeakReference<Any>? = null
    var requestedTarget: Pair<Int, Int>? = null
        private set
    var confirmedSurface: Pair<Int, Int>? = null
        private set
    val scalerViewport: Pair<Int, Int>? get() = requestedTarget ?: confirmedSurface

    fun attach(nextOwner: Any): Boolean {
        if (owner?.get() === nextOwner) return false
        owner = WeakReference(nextOwner)
        val changed = requestedTarget != null
        requestedTarget = null
        return changed
    }

    fun updateTarget(source: Any, size: Pair<Int, Int>?): Boolean {
        if (owner?.get() !== source) return false
        val next = size?.takeIf { it.first > 0 && it.second > 0 }
        if (requestedTarget == next) return false
        requestedTarget = next
        return true
    }

    fun updateSurface(width: Int, height: Int): Boolean {
        // A transient destroyed surface must not empty/rebuild the effect chain during a switch.
        if (width <= 0 || height <= 0) return false
        val next = width to height
        if (confirmedSurface == next) return false
        confirmedSurface = next
        return true
    }

    fun detach(source: Any): Boolean {
        if (owner?.get() !== source) return false
        owner = null
        val changed = requestedTarget != null
        requestedTarget = null
        return changed
    }
}

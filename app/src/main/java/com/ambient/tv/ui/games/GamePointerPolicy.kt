package com.ambient.tv.ui.games

/** A gamepad-only game has no desktop cursor; its menus and pointer devices still do. */
internal fun gamePointerVisible(
    menuVisible: Boolean,
    directPointer: Boolean,
    controllerTypes: Iterable<Int?>,
): Boolean = menuVisible || directPointer || controllerTypes.any { type ->
    // Libretro device subtypes keep the base device in the low byte.
    type != null && type >= 0 && (type and 0xff) in setOf(2, 4, 6)
}

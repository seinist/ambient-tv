package com.ambient.tv.ui.input

import android.view.MotionEvent
import kotlin.math.hypot

internal enum class TvMouseButtonAction { BACK }

/** Secondary click and a dedicated mouse Back button follow the TV navigation stack. */
internal fun tvMouseButtonAction(button: Int): TvMouseButtonAction? = when (button) {
    MotionEvent.BUTTON_SECONDARY,
    MotionEvent.BUTTON_BACK,
    -> TvMouseButtonAction.BACK
    else -> null
}

/** Do not turn a stationary middle click into a primary click; pan only after touch slop. */
internal fun shouldStartMiddleMouseDrag(
    downX: Float,
    downY: Float,
    currentX: Float,
    currentY: Float,
    touchSlop: Int,
): Boolean = hypot(currentX - downX, currentY - downY) > touchSlop.coerceAtLeast(0)

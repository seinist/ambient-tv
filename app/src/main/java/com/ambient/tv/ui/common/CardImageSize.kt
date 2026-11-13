package com.ambient.tv.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.SizeResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Pixel density of a 1080p TV interface (xhdpi). Card images never decode larger than at this density. */
internal const val CARD_IMAGE_1080_DENSITY = 2f

/**
 * Decode size of a card measured at [maxWidth] x [maxHeight] px on a [density] screen when card
 * images are limited to 1080p: the size the same card has on a 1080p interface. Unchanged at or
 * below that density; an unbounded side stays undefined (Coil keeps the aspect ratio).
 */
internal fun cardImage1080Size(maxWidth: Int, maxHeight: Int, density: Float): Size {
    val scale = if (density > CARD_IMAGE_1080_DENSITY) CARD_IMAGE_1080_DENSITY / density else 1f
    fun dimension(px: Int): Dimension =
        if (px == Constraints.Infinity || px <= 0) Dimension.Undefined
        else Dimension((px * scale).toInt().coerceAtLeast(1))
    return Size(dimension(maxWidth), dimension(maxHeight))
}

/**
 * Like AsyncImage's own constraints resolver, but scaled down to [cardImage1080Size]. Reads the
 * constraints during measurement, so the first frame is not delayed by a size callback.
 */
internal class CardImage1080SizeResolver(private val density: Float) : SizeResolver {
    private val size = MutableStateFlow<Size?>(null)

    override suspend fun size(): Size = size.filterNotNull().first()

    fun modifier(): Modifier = Modifier.layout { measurable, constraints ->
        size.value = cardImage1080Size(constraints.maxWidth, constraints.maxHeight, density)
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

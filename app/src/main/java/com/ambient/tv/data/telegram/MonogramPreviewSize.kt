package com.ambient.tv.data.telegram

internal const val MONOGRAM_PREVIEW_SIZE_EXTRA = "ambient.monogram.preview-size"

/** Three bounded classes avoid one disk/RAM cache variant per arbitrary layout pixel size. */
internal fun monogramPreviewTarget(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 320
    val area = width.toLong() * height.toLong()
    return when {
        area <= 160 * 90 -> 160
        area <= 320 * 180 -> 320
        else -> 640
    }
}
internal fun monogramPreviewTarget(size: coil3.size.Size, messageId: Int): Int =
    if (messageId == 0) 320 else monogramPreviewTarget(
        (size.width as? coil3.size.Dimension.Pixels)?.px ?: 0,
        (size.height as? coil3.size.Dimension.Pixels)?.px ?: 0)

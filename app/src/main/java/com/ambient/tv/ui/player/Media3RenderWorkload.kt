package com.ambient.tv.ui.player

/**
 * A stable filter list is not a stable workload: adaptive streams can switch from Full HD
 * to UHD without changing any settings. Re-arm the measured frame-drop guard for changes
 * in input/output size, frame rate or color transfer.
 * Saved "continue anyway" choices apply only to the workload the user actually accepted.
 */
internal fun media3RenderWorkloadSignature(
    stages: List<Media3Stage>,
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    fps: Float,
    colorTransfer: Int?,
): String {
    val frameRate = media3FilterFrameRate(fps)
    return "${sourceWidth}x${sourceHeight}->${outputWidth}x${outputHeight}@$frameRate" +
        ":transfer=$colorTransfer:" + stages.joinToString(";")
}

package com.ambient.tv.ui.games

/**
 * Atomic Libretro SET_PIXEL_FORMAT outcome from the pinned native frontend.
 *
 * A core option or 10-bit VkImage is insufficient evidence by itself. An HDR10
 * source is accepted only when the *core itself* requested the packed PQ format
 * and the frontend acknowledged that request. This says nothing about whether
 * the TV currently receives an HDR signal; that is separately checked by WSI.
 */
internal data class GameCoreHdrPixelHandshake(val bits: Int = 0) {
    val hdr10Requested: Boolean get() = bits and 1 != 0
    val hdr10Accepted: Boolean get() = bits and 3 == 3
    val scrgbRequested: Boolean get() = bits and 4 != 0

    val description: String get() = when {
        hdr10Accepted -> "HDR10/PQ core pixel format accepted"
        hdr10Requested -> "HDR10/PQ core pixel format rejected"
        scrgbRequested -> "scRGB FP16 core pixel format rejected"
        else -> "No verified core HDR pixel-format handshake"
    }
}

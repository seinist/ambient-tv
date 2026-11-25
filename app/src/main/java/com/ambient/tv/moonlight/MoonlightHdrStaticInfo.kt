package com.ambient.tv.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GameStream transfers CTA-861.3 static mastering metadata without the descriptor ID.
 * Android MediaFormat.KEY_HDR_STATIC_INFO requires 25 bytes: ID 0, 12 little-endian uint16.
 */
fun moonlightHdrStaticInfo(payload: ByteArray?): ByteBuffer? {
    if (payload == null || payload.size != 24) return null
    return ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0)
        put(payload)
        flip()
    }
}

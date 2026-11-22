package com.ambient.tv.ui.games

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class NetplayInputTest {
    @Test fun pressureFramesHaveAnExactWireShapeAndDigitalDefaults() {
        val value = NetplayInput(0x1fff, -32767, 32767, -2, 2, 8192, 24575)
        val bytes = ByteArrayOutputStream().also { value.write(DataOutputStream(it)) }.toByteArray()
        assertEquals(16, bytes.size)
        assertEquals(value, NetplayInput.read(DataInputStream(ByteArrayInputStream(bytes))))
        val digital = NetplayInput((1 shl 12) or (1 shl 13), 0, 0, 0, 0)
        assertEquals(32767, digital.leftTrigger)
        assertEquals(32767, digital.rightTrigger)
    }

    @Test fun invalidRemotePressureAndMaskAreRejected() {
        fun bytes(buttons: Int, left: Int, right: Int) = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).apply {
                writeInt(buttons)
                repeat(4) { writeShort(0) }
                writeShort(left); writeShort(right)
            }
        }.toByteArray()
        for (wire in listOf(bytes(0, 32768, 0), bytes(0, 0, 65535), bytes(-1, 0, 0), bytes(65536, 0, 0))) {
            val failure = runCatching { NetplayInput.read(DataInputStream(ByteArrayInputStream(wire))) }.exceptionOrNull()
            assertTrue(failure is IOException)
        }
    }
}

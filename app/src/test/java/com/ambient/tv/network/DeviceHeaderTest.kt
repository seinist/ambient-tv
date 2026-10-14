package com.ambient.tv.network

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHeaderTest {
    @Test
    fun `non ascii model names become valid header values`() {
        assertEquals("SHIELD Android TV", asciiHeaderValue("SHIELD Android TV", "x"))
        assertEquals("Mi TV Stick", asciiHeaderValue("Mi TV Stick™", "x"))
        assertEquals("Fernseher", asciiHeaderValue("Fernseher\n", "x"))
        assertEquals("Android TV", asciiHeaderValue("小米", "Android TV"))
        assertEquals("Android TV", asciiHeaderValue(null, "Android TV"))
    }
}

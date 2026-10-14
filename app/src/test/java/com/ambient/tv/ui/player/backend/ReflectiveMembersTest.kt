package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReflectiveMembersTest {
    class Stats {
        @JvmField val readBytes: Long = 42L
        fun getDecodedVideo(): Int = 7
        var released = 0
        fun release() { released++ }
    }

    @Test
    fun `fields, getters and methods are found and missing members are null`() {
        val stats = Stats()
        assertEquals(42L, ReflectiveMembers.value(stats, "readBytes"))
        assertEquals(7, ReflectiveMembers.value(stats, "decodedVideo"))
        assertNull(ReflectiveMembers.value(stats, "lostPictures"))
        ReflectiveMembers.call(stats, "release")
        ReflectiveMembers.call(stats, "release")
        assertEquals(2, stats.released)
        assertNull(ReflectiveMembers.call(stats, "getMedia"))
    }
}

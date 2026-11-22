package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class HoldActionTest {
    @Test fun pointerKeyAndAccessibleActivationDoNotReleaseEachOther() {
        val changes = mutableListOf<Boolean>()
        val hold = HoldActionState(changes::add)
        hold.set("pointer", true)
        hold.set("key", true)
        hold.set("pointer", false)
        hold.set("accessible", true)
        hold.set("key", false)
        assertEquals(listOf(true), changes)
        hold.set("accessible", false)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun repeatsCancellationAndLateReleasesAreIdempotent() {
        val changes = mutableListOf<Boolean>()
        val hold = HoldActionState(changes::add)
        repeat(10) { hold.set("key", true) }
        hold.releaseAll()
        hold.releaseAll()
        hold.set("key", false)
        assertEquals(listOf(true, false), changes)
        hold.set("new", true)
        hold.set("key", false)
        assertEquals(listOf(true, false, true), changes)
        hold.releaseAll()
        assertEquals(listOf(true, false, true, false), changes)
    }
}

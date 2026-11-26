package com.ambient.tv.youtube

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SmartTubeReflectionTest {
    class Fixture {
        fun getter(): String = "value"
        fun choose(value: String): String = value
        fun choose(value: Int): Int = value
        fun nullable(value: String?): String? = value
    }

    @Test fun resolvesGettersOverloadsAndNulls() {
        val target = Fixture()
        assertEquals("value", target.cachedMethod("getter", emptyArray(), false)!!.invoke(target))
        assertEquals("text", target.cachedMethod("choose", arrayOf("text"), false)!!.invoke(target, "text"))
        assertEquals(7, target.cachedMethod("choose", arrayOf(7), false)!!.invoke(target, 7))
        assertNull(target.cachedMethod("nullable", arrayOf<Any?>(null), false)!!.invoke(target, null))
        assertNull(target.cachedMethod("missing", emptyArray(), false))
        assertNull(target.cachedMethod("choose", arrayOf(true), false))
        assertNotNull(target.cachedMethod("choose", arrayOf(true), true))
    }

    @Test fun concurrentResolutionPreservesResults() {
        val target = Fixture()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 128).map { value ->
                pool.submit<Int> {
                    target.cachedMethod("choose", arrayOf(value), false)!!.invoke(target, value) as Int
                }
            }
            tasks.forEachIndexed { index, future -> assertEquals(index, future.get(5, TimeUnit.SECONDS).toInt()) }
        } finally {
            pool.shutdownNow()
        }
    }
}

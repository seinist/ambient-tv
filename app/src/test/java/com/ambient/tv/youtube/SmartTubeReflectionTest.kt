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

    @Test fun actualInvocationWrappersPreserveContracts() {
        val target = Fixture()
        assertTrue(target.hasMethod("getter", 0))
        assertFalse(target.hasMethod("getter", 1))
        assertEquals("value", target.call("getter"))
        assertNull(target.call("missing"))
        assertFalse(target.invokeDetailed("missing").found)
        assertTrue(target.invokeDetailed("choose", true).found)
        assertNotNull(target.invokeDetailed("choose", true).error)
        assertFalse(target.invokeCompatible("choose", true).invoked)
        assertTrue(target.invokeCompatible("nullable", null).invoked)
    }

    @Test fun signatureEvictionPreservesLookup() {
        val target = Fixture()
        repeat(600) { index ->
            assertNull(target.cachedMethod("missing$index", arrayOf(index), false))
        }
        assertEquals(7, target.cachedMethod("choose", arrayOf(7), false)!!.invoke(target, 7))
        assertEquals("value", target.call("getter"))
    }

    @Test fun classCacheBoundAndFifoEviction() {
        val cache = BoundedReflectionCache<Int, Any>(256)
        val oldest = cache.getOrPut(0) { Any() }
        val second = cache.getOrPut(1) { Any() }
        for (i in 2 until 256) cache.getOrPut(i) { Any() }
        assertSame(oldest, cache.getOrPut(0) { Any() })
        cache.getOrPut(256) { Any() }
        assertSame(second, cache.getOrPut(1) { Any() })
        assertNotSame(oldest, cache.getOrPut(0) { Any() })
        assertEquals(256, cache.size)

        // Distinct JVM array classes exercise the real class-index cache.
        for (dimensions in 1..140) {
            for (component in listOf(String::class.java, Int::class.javaPrimitiveType!!)) {
                val target = java.lang.reflect.Array.newInstance(component, *IntArray(dimensions))
                assertTrue(target.hasMethod("toString", 0))
            }
        }
        assertEquals(256, reflectionClassCacheSize())
        assertEquals("value", Fixture().call("getter"))
    }

    @Test fun concurrentCacheInsertionsRemainBounded() {
        val cache = BoundedReflectionCache<Int, Int>(256)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 1024).map { key ->
                pool.submit<Int> {
                    val value = cache.getOrPut(key) { key }
                    check(cache.size <= 256)
                    value
                }
            }
            tasks.forEachIndexed { index, future -> assertEquals(index, future.get(5, TimeUnit.SECONDS).toInt()) }
            assertEquals(256, cache.size)
        } finally {
            pool.shutdownNow()
        }
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

package com.ambient.tv.tvheadend.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvheadendCacheConcurrencyTest {
    @Test fun serverSwitchDuringDecodeCannotPublishOldData() = interruptedDecode { cache ->
        assertNull(cache.read("B", "channels", String.serializer()))
        "B"
    }

    @Test fun switchingBackToTheSameServerRejectsAnEarlierGeneration() = interruptedDecode { cache ->
        cache.read("B", "channels", String.serializer())
        cache.read("A", "other", String.serializer())
        "A"
    }

    @Test fun disablingDuringDecodeCannotRestoreTheClearedCache() = interruptedDecode { cache ->
        cache.clear("A")
        "A"
    }

    private fun interruptedDecode(change: suspend (TvheadendDiskCache) -> String) = runBlocking {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val store = object : TvheadendCacheStore {
            var stored = mapOf("channels" to "\"old server data\"")
            override fun put(scope: String, key: String, valid: () -> Boolean, encode: () -> String) = Unit
            override suspend fun read(scope: String) = if (scope == "A") stored else emptyMap()
            override fun remove(scope: String) { if (scope == "A") stored = emptyMap() }
        }
        val cache = TvheadendDiskCache(store)
        val serializer = object : KSerializer<String> by String.serializer() {
            override fun deserialize(decoder: Decoder): String {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                return decoder.decodeString()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = executor.submit<String?> { runBlocking { cache.read("A", "channels", serializer) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val next = change(cache)
            resume.countDown()
            assertNull(old.get(2, TimeUnit.SECONDS))
            assertNull(cache.read(next, "channels", String.serializer()))
        } finally { resume.countDown(); executor.shutdownNow() }
    }
}

package com.liskovsoft.youtubeapi.service

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class SmartTubeFormatBridgeTest {
    private val manager get() = YouTubeServiceManager.instance() as YouTubeServiceManager

    @Test fun restoresThePersistedClientInTheActualFormatSelector() {
        val type = FormatInfoWrapper::class.java
        val clientFields = listOf("mActualClient", "mNextClient").map { type.getDeclaredField(it).apply { isAccessible = true } }
        val previous = clientFields.map { it.get(null) }
        val data = MediaServiceData.instance()
        val stored = data.clientType
        try {
            clientFields.forEach { it.set(null, null) }
            data.clientType = -1
            assertFalse(manager.restoreSavedFormatClient())
            assertEquals(AppClient.TV, FormatInfoWrapper.getActualClient())
            data.clientType = AppClient.TV_SAMSUNG.ordinal
            assertTrue(manager.restoreSavedFormatClient())
            assertEquals(AppClient.TV_SAMSUNG, FormatInfoWrapper.getActualClient())
            data.clientType = AppClient.TV.ordinal
            assertFalse(manager.restoreSavedFormatClient())
            assertEquals(AppClient.TV_SAMSUNG, FormatInfoWrapper.getActualClient())
        } finally {
            data.clientType = stored
            clientFields.zip(previous).forEach { (field, value) -> field.set(null, value) }
        }
    }

    @Test fun invalidationDropsTheCachedFormatAndRejectsAnInFlightWrite() {
        val type = FormatInfoWrapper::class.java
        val generation = type.getDeclaredField("formatGeneration").apply { isAccessible = true }.get(null) as AtomicLong
        val store = type.getDeclaredMethod("setCachedFormatInfo", MediaItemFormatInfo::class.java, String::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val load = type.getDeclaredMethod("getCachedFormatInfo", String::class.java).apply { isAccessible = true }
        val fixture = Proxy.newProxyInstance(MediaItemFormatInfo::class.java.classLoader, arrayOf(MediaItemFormatInfo::class.java)) { _, method, _ ->
            when (method.name) {
                "getVideoId" -> "fixture"
                "isCacheActual" -> true
                "isUnplayable" -> false
                else -> null
            }
        }
        try {
            val oldGeneration = generation.get()
            store.invoke(FormatInfoWrapper, fixture, null, oldGeneration)
            assertSame(fixture, load.invoke(FormatInfoWrapper, "fixture"))
            manager.invalidateFormatCache()
            assertNull(load.invoke(FormatInfoWrapper, "fixture"))
            store.invoke(FormatInfoWrapper, fixture, null, oldGeneration)
            assertNull(load.invoke(FormatInfoWrapper, "fixture"))
            store.invoke(FormatInfoWrapper, fixture, null, generation.get())
            assertSame(fixture, load.invoke(FormatInfoWrapper, "fixture"))
        } finally {
            manager.invalidateFormatCache()
        }
    }
}

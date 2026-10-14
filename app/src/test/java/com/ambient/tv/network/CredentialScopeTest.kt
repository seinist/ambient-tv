package com.ambient.tv.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CredentialScopeTest {
    private val value = "Basic dGVzdDpzY29wZQ=="

    @Test fun boundCredentialOnlyReachesItsOrigin() {
        CredentialScope.bind(value, "http://192.168.1.2:9981/".toHttpUrl())
        val headers = mapOf("Authorization" to value, "Accept" to "image/*")
        assertEquals(headers, CredentialScope.scopedHeaders("http://192.168.1.2:9981/imagecache/1", headers))
        assertEquals(mapOf("Accept" to "image/*"), CredentialScope.scopedHeaders("https://picons.example.org/a.png", headers))
        assertEquals(mapOf("Accept" to "image/*"), CredentialScope.scopedHeaders("http://192.168.1.2:8096/a.png", headers))
        assertEquals(mapOf("Accept" to "image/*"), CredentialScope.scopedHeaders("https://192.168.1.2:9981/a.png", headers))
        assertFalse(CredentialScope.allows(value, "http://evil.example/".toHttpUrl()))
    }

    @Test fun aCredentialInUseStaysBoundWhenManyOthersAreBound() {
        val origin = "http://192.168.1.2:9981/".toHttpUrl()
        val elsewhere = "https://picons.example.org/a.png".toHttpUrl()
        CredentialScope.bind(value, origin)
        repeat(500) { index ->
            CredentialScope.bind("Basic other-$index", origin)
            // The live credential keeps being used, as every TVHeadend artwork request does.
            assertFalse(CredentialScope.allows(value, elsewhere))
        }
        assertTrue(CredentialScope.allows(value, origin))
    }

    @Test fun unboundHeadersPassThrough() {
        val headers = mapOf("Authorization" to "MediaBrowser Token=\"x\"")
        assertEquals(headers, CredentialScope.scopedHeaders("https://anything.example/", headers))
    }
}

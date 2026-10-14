package com.ambient.tv.network

import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImageAuthInterceptorTest {
    @Test fun preservesExplicitBasicAuthenticationForTvheadendArtwork() {
        val request = Request.Builder()
            .url("http://192.168.178.34:9981/imagecache/channel-logo")
            .header("Authorization", "Basic dHY6c2VjcmV0")
            .build()

        assertTrue(hasExplicitAuthorization(request))
    }

    @Test fun injectsProviderTokensWhenTheRequestHasNoAuthentication() {
        val request = Request.Builder().url("http://192.168.178.34:8096/Items/image").build()

        assertFalse(hasExplicitAuthorization(request))
    }
}

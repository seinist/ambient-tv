package com.ambient.tv.data.emby

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

@Singleton
class EmbyHeadersInterceptor @Inject constructor(
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val authenticationCall = chain.request().url.encodedPath.endsWith("/Users/AuthenticateByName")
        val token = tokenStore.embyToken.takeUnless { authenticationCall }
        val authorization = buildString {
            append("MediaBrowser Client=\"${com.ambient.tv.branding.AppBrand.name}\", Device=\"Android TV\", DeviceId=\"")
            append(preferences.clientIdentifier)
            append("\", Version=\"").append(BuildConfig.VERSION_NAME).append('"')
            if (!token.isNullOrBlank()) append(", Token=\"").append(token).append('"')
        }
        val metadataLanguage = LanguagePreferences.resolve(preferences.metadataLanguageKey)
        val request = chain.request().newBuilder()
            .header("Accept-Language", metadataLanguage)
            .header("Authorization", authorization)
            .header("X-Emby-Authorization", authorization)
            .header("X-Application-Device", com.ambient.tv.network.deviceModelHeader)
            .apply { if (!token.isNullOrBlank()) header("X-Emby-Token", token) }
            .build()
        return chain.proceed(request)
    }
}

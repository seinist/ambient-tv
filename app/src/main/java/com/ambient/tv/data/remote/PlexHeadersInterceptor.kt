package com.ambient.tv.data.remote

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.data.local.TokenStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexHeadersInterceptor @Inject constructor(
    private val appPreferences: AppPreferences,
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val metadataLanguage = LanguagePreferences.resolve(appPreferences.metadataLanguageKey)
        val builder = original.newBuilder()
            .header("Accept", "application/json")
            .header("Accept-Language", metadataLanguage)
            .header("X-Plex-Language", LanguagePreferences.baseLanguage(metadataLanguage) ?: metadataLanguage)
            .header("X-Plex-Client-Identifier", appPreferences.clientIdentifier)
            .header("X-Plex-Product", com.ambient.tv.branding.AppBrand.name)
            .header("X-Plex-Version", BuildConfig.VERSION_NAME)
            .header("X-Plex-Platform", "Android TV")
            .header("X-Plex-Device", "Android TV")
            .header("X-Plex-Device-Name", com.ambient.tv.network.deviceModelHeader)

        tokenStore.token?.takeIf { it.isNotBlank() }?.let { token ->
            builder.header("X-Plex-Token", token)
        }

        return chain.proceed(builder.build())
    }
}

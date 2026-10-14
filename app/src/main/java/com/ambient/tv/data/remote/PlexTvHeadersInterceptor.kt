package com.ambient.tv.data.remote

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Headers for plex.tv account calls. Only the resources lookup carries the account token, never a local PMS token. */
@Singleton
class PlexTvHeadersInterceptor @Inject constructor(
    private val appPreferences: AppPreferences,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", appPreferences.clientIdentifier)
            .header("X-Plex-Product", com.ambient.tv.branding.AppBrand.name)
            .header("X-Plex-Version", BuildConfig.VERSION_NAME)
            .header("X-Plex-Platform", "Android TV")
            .header("X-Plex-Device", "Android TV")
            .header("X-Plex-Device-Name", com.ambient.tv.network.deviceModelHeader)
            .apply {
                // Only the resources lookup authenticates against plex.tv; every other call
                // (PIN create/poll) stays anonymous so no token can leak into them.
                if (chain.request().url.encodedPath != RESOURCES_PATH) removeHeader("X-Plex-Token")
            }
            .build()
        return chain.proceed(request)
    }

    private companion object {
        const val RESOURCES_PATH = "/api/v2/resources"
    }
}

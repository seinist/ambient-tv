package com.ambient.tv.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Reports whether a media server accepts the current login: a 401 on an authenticated endpoint
 * is a rejection, a success is a confirmation. Endpoints that answer without a login
 * ([anonymous]) prove nothing and are skipped.
 */
class LoginStateInterceptor(
    private val anonymous: (path: String) -> Boolean,
    private val onLoginState: (accepted: Boolean) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val path = chain.request().url.encodedPath
        if (!anonymous(path)) {
            when {
                response.code == 401 -> onLoginState(false)
                response.isSuccessful -> onLoginState(true)
            }
        }
        return response
    }
}

/** Jellyfin/Emby endpoints that work without a session. */
internal fun mediaBrowserAnonymousPath(path: String): Boolean =
    path.contains("/Public", ignoreCase = true) ||
        path.contains("AuthenticateByName", ignoreCase = true) ||
        path.contains("/QuickConnect", ignoreCase = true) ||
        path.contains("/Startup", ignoreCase = true) ||
        path.contains("/Branding", ignoreCase = true)

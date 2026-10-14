package com.ambient.tv.data.repository

import com.ambient.tv.util.suspendResult
import com.ambient.tv.data.remote.PlexTvApiService
import com.ambient.tv.domain.model.PlexPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexAuthRepository @Inject constructor(
    private val api: PlexTvApiService,
) {
    suspend fun createPin(): Result<PlexPin> = withContext(Dispatchers.IO) {
        suspendResult {
            api.createPin(strong = true).let {
                PlexPin(
                    id = it.id,
                    code = it.code,
                    shortCode = it.shortCode?.takeIf { pin -> pin.isNotBlank() },
                )
            }
        }
    }

    /** Returns the auth token once the user has approved the code on plex.tv/link, else null. */
    suspend fun pollToken(pinId: Long): Result<String?> = withContext(Dispatchers.IO) {
        suspendResult { api.getPin(pinId).authToken }
    }

    /**
     * Token to store for the edited server: its own access token when plex.tv lists it (needed for
     * shared servers), else the account token, which is what owned servers accept as well.
     */
    suspend fun serverToken(accountToken: String, machineIdentifier: String?, host: String, port: Int): String =
        serverLink(accountToken, machineIdentifier, host, port).token

    /** Server token plus, when plex.tv knows one, the server's HTTPS plex.direct address. */
    data class PlexServerLink(
        val token: String,
        val secureHost: String? = null,
        val securePort: Int? = null,
        val machineIdentifier: String? = null,
    )

    suspend fun serverLink(accountToken: String, machineIdentifier: String?, host: String, port: Int): PlexServerLink =
        withContext(Dispatchers.IO) {
            val server = suspendResult { api.resources(accountToken) }
                .onFailure { Timber.w(it, "plex.tv resources lookup failed; keeping the account token") }
                .getOrNull()
                ?.let { plexMatchingServer(it, machineIdentifier, host, port) }
            val secure = server?.let { plexDirectSecureAddress(it, host) }
            PlexServerLink(
                token = server?.accessToken?.trim()?.takeIf(String::isNotBlank) ?: accountToken,
                secureHost = secure?.first,
                securePort = secure?.second,
                machineIdentifier = server?.clientIdentifier?.takeIf(String::isNotBlank),
            )
        }
}

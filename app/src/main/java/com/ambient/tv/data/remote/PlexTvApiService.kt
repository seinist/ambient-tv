package com.ambient.tv.data.remote

import com.ambient.tv.data.remote.dto.PlexPinDto
import com.ambient.tv.data.remote.dto.PlexResourceDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Talks to plex.tv itself (account linking), as opposed to [PlexApiService] which talks to the
 * user's local Plex Media Server.
 */
interface PlexTvApiService {

    // strong=true returns the long opaque code embedded in the app.plex.tv auth QR
    // (see [PlexAuthLink]). strong=false is only the 4-character plex.tv/link PIN and must
    // not be the QR payload. The typed PIN stays on screen when the response includes one.
    @POST("api/v2/pins")
    suspend fun createPin(@Query("strong") strong: Boolean = true): PlexPinDto

    @Headers("Cache-Control: no-cache", "Pragma: no-cache")
    @GET("api/v2/pins/{id}")
    suspend fun getPin(@Path("id") id: Long): PlexPinDto

    /** Servers of the signed-in account, including shared ones with their own access token. */
    @GET("api/v2/resources")
    suspend fun resources(
        @Header("X-Plex-Token") accountToken: String,
        @Query("includeHttps") includeHttps: Int = 1,
        @Query("includeRelay") includeRelay: Int = 0,
    ): List<PlexResourceDto>
}

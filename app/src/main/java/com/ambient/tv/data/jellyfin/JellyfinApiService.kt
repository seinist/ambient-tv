package com.ambient.tv.data.jellyfin

import com.ambient.tv.data.metadata.MediaBrowserImageInfo
import com.ambient.tv.data.metadata.MediaBrowserRemoteImageList
import com.ambient.tv.data.metadata.MediaBrowserRemoteSearchQuery
import com.ambient.tv.data.metadata.MediaBrowserUserDto
import com.ambient.tv.network.ElevatedRequest
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface JellyfinApiService {
    @GET("System/Info/Public") suspend fun getPublicSystemInfo(): JellyfinSystemInfo
    @POST("Users/AuthenticateByName") suspend fun authenticate(@Body body: JellyfinAuthRequest): JellyfinAuthResponse
    @GET("QuickConnect/Enabled") suspend fun quickConnectEnabled(): Boolean
    @POST("QuickConnect/Initiate") suspend fun initiateQuickConnect(): JellyfinQuickConnectResult
    @GET("QuickConnect/Connect") suspend fun quickConnectState(@Query("secret") secret: String): JellyfinQuickConnectResult
    @POST("Users/AuthenticateWithQuickConnect") suspend fun authenticateQuickConnect(@Body body: JellyfinQuickConnectRequest): JellyfinAuthResponse

    @GET("UserViews")
    suspend fun getUserViews(
        @Query("userId") userId: String,
        @Query("includeExternalContent") includeExternalContent: Boolean = false,
    ): JellyfinItemsResponse

    @GET("Items")
    suspend fun getItems(
        @Query("userId") userId: String,
        @Query("parentId") parentId: String? = null,
        @Query("recursive") recursive: Boolean = false,
        @Query("includeItemTypes") includeItemTypes: String? = null,
        @Query("fields") fields: String = ITEM_FIELDS,
        @Query("sortBy") sortBy: String? = null,
        @Query("sortOrder") sortOrder: String? = null,
        @Query("searchTerm") searchTerm: String? = null,
        @Query("personIds") personIds: String? = null,
        @Query("startIndex") startIndex: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("enableUserData") enableUserData: Boolean = true,
        @Query("enableImages") enableImages: Boolean = true,
        @Query("enableTotalRecordCount") enableTotalRecordCount: Boolean = true,
        @Query("isPlayed") isPlayed: Boolean? = null,
        @Query("genres") genres: String? = null,
    ): JellyfinItemsResponse

    /** The next unplayed episode of every series the user is watching. */
    @GET("Shows/NextUp")
    suspend fun getNextUp(
        @Query("userId") userId: String,
        @Query("fields") fields: String = ITEM_FIELDS,
        @Query("limit") limit: Int = 24,
        @Query("enableUserData") enableUserData: Boolean = true,
        @Query("enableTotalRecordCount") enableTotalRecordCount: Boolean = false,
        @Query("enableResumable") enableResumable: Boolean = false,
    ): JellyfinItemsResponse

    @GET("Genres")
    suspend fun getGenres(
        @Query("userId") userId: String,
        @Query("parentId") parentId: String,
        @Query("includeItemTypes") includeItemTypes: String = "Movie,Series",
        @Query("sortBy") sortBy: String = "SortName",
        @Query("enableImages") enableImages: Boolean = false,
        @Query("enableTotalRecordCount") enableTotalRecordCount: Boolean = false,
    ): JellyfinItemsResponse

    @GET("UserItems/Resume")
    suspend fun getResumeItems(
        @Query("userId") userId: String,
        @Query("fields") fields: String = ITEM_FIELDS,
        @Query("limit") limit: Int = 100,
        @Query("enableUserData") enableUserData: Boolean = true,
    ): JellyfinItemsResponse

    @GET("MediaSegments/{id}") suspend fun getMediaSegments(@Path("id") id: String): JellyfinSegmentsResponse
    /** Jellyfin 10.9+; 404 when the song has no lyrics. */
    @GET("Audio/{id}/Lyrics") suspend fun getLyrics(@Path("id") id: String): JsonObject

    @GET("Items/{id}") suspend fun getItem(@Path("id") id: String, @Query("userId") userId: String): JellyfinItem
    @GET("Items/{id}/Similar") suspend fun getSimilar(@Path("id") id: String, @Query("userId") userId: String, @Query("limit") limit: Int = 20, @Query("fields") fields: String = ITEM_FIELDS): JellyfinItemsResponse
    /** Trailers stored with the item (Jellyfin 10.9+ path, then the older user-scoped one). */
    @GET("Items/{id}/LocalTrailers") suspend fun getLocalTrailers(@Path("id") id: String, @Query("userId") userId: String): List<JellyfinItem>
    @GET("Users/{userId}/Items/{id}/LocalTrailers") suspend fun getLocalTrailersLegacy(@Path("id") id: String, @Path("userId") userId: String): List<JellyfinItem>
    /** Backdrops the server's own image providers offer for the item (needs no key in the app). */
    @GET("Items/{id}/RemoteImages") suspend fun getRemoteImages(@Path("id") id: String, @Query("type") type: String = "Backdrop", @Query("limit") limit: Int = 1): JellyfinRemoteImages
    @GET("Items/{id}/ThemeSongs") suspend fun getThemeSongs(@Path("id") id: String, @Query("userId") userId: String, @Query("inheritFromParent") inheritFromParent: Boolean = true): JellyfinItemsResponse
    @POST("Items/{id}/PlaybackInfo") suspend fun getPlaybackInfo(@Path("id") id: String, @Body body: JellyfinPlaybackInfoRequest): JellyfinPlaybackInfoResponse
    @POST("Sessions/Playing") suspend fun reportPlaybackStart(@Body body: JellyfinPlaybackProgress)
    @POST("Sessions/Playing/Progress") suspend fun reportPlaybackProgress(@Body body: JellyfinPlaybackProgress)
    @POST("Sessions/Playing/Stopped") suspend fun reportPlaybackStopped(@Body body: JellyfinPlaybackProgress)
    @POST("UserPlayedItems/{id}") suspend fun markPlayed(@Path("id") id: String)
    @DELETE("UserPlayedItems/{id}") suspend fun markUnplayed(@Path("id") id: String)
    @POST("Users/{userId}/PlayedItems/{id}") suspend fun markPlayedLegacy(@Path("userId") userId: String, @Path("id") id: String)
    @DELETE("Users/{userId}/PlayedItems/{id}") suspend fun markUnplayedLegacy(@Path("userId") userId: String, @Path("id") id: String)
    @POST("Users/{userId}/Items/{id}/UserData") suspend fun updateUserDataLegacy(@Path("userId") userId: String, @Path("id") id: String, @Body body: JellyfinUserDataUpdate): JellyfinUserData
    @POST("UserItems/{id}/UserData") suspend fun updateUserData(@Path("id") id: String, @Body body: JellyfinUserDataUpdate): JellyfinUserData

    // Administrator-only metadata editing; see ElevatedRequest.
    @GET("Users/{userId}") suspend fun getUser(@Path("userId") userId: String): MediaBrowserUserDto
    @Headers(ElevatedRequest.MARKER) @POST("Items/RemoteSearch/{type}") suspend fun remoteSearch(@Path("type") type: String, @Body body: MediaBrowserRemoteSearchQuery): List<JsonObject>
    @Headers(ElevatedRequest.MARKER) @POST("Items/RemoteSearch/Apply/{id}") suspend fun applyRemoteSearch(@Path("id") id: String, @Body body: JsonObject, @Query("replaceAllImages") replaceAllImages: Boolean = true)
    @Headers(ElevatedRequest.MARKER) @GET("Items/{id}/RemoteImages") suspend fun getRemoteImageChoices(@Path("id") id: String, @Query("type") type: String, @Query("limit") limit: Int = 60, @Query("includeAllLanguages") includeAllLanguages: Boolean = false): MediaBrowserRemoteImageList
    @Headers(ElevatedRequest.MARKER) @POST("Items/{id}/RemoteImages/Download") suspend fun downloadRemoteImage(@Path("id") id: String, @Query("type") type: String, @Query("imageUrl") imageUrl: String)
    @Headers(ElevatedRequest.MARKER) @GET("Items/{id}/Images") suspend fun getImageInfos(@Path("id") id: String): List<MediaBrowserImageInfo>
    @Headers(ElevatedRequest.MARKER) @POST("Items/{id}/Images/{type}/{index}/Index") suspend fun moveImage(@Path("id") id: String, @Path("type") type: String, @Path("index") index: Int, @Query("newIndex") newIndex: Int)
    /** The complete item as the server stores it: an update must send every field back. */
    @Headers(ElevatedRequest.MARKER) @GET("Items/{id}") suspend fun getItemJson(@Path("id") id: String, @Query("userId") userId: String): JsonObject
    @Headers(ElevatedRequest.MARKER) @POST("Items/{id}") suspend fun updateItem(@Path("id") id: String, @Body body: JsonObject)

    companion object {
        const val ITEM_FIELDS = "Overview,Taglines,Genres,People,ProviderIds,DateCreated,PremiereDate,Studios,MediaSources,MediaStreams,ChildCount,RecursiveItemCount"
    }
}

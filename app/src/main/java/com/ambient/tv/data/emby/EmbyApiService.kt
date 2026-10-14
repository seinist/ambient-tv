package com.ambient.tv.data.emby

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface EmbyApiService {
    @GET("System/Info/Public") suspend fun getPublicSystemInfo(): EmbySystemInfo
    @POST("Users/AuthenticateByName") suspend fun authenticate(@Body body: EmbyAuthRequest): EmbyAuthResponse

    @GET("Users/{userId}/Views")
    suspend fun getUserViews(
        @Path("userId") userId: String,
        @Query("includeExternalContent") includeExternalContent: Boolean = false,
    ): EmbyItemsResponse

    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @Path("userId") userId: String,
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
    ): EmbyItemsResponse

    /** The next unplayed episode of every series the user is watching. */
    @GET("Shows/NextUp")
    suspend fun getNextUp(
        @Query("userId") userId: String,
        @Query("fields") fields: String = ITEM_FIELDS,
        @Query("limit") limit: Int = 24,
        @Query("enableUserData") enableUserData: Boolean = true,
        @Query("enableTotalRecordCount") enableTotalRecordCount: Boolean = false,
    ): EmbyItemsResponse

    @GET("Genres")
    suspend fun getGenres(
        @Query("userId") userId: String,
        @Query("parentId") parentId: String,
        @Query("includeItemTypes") includeItemTypes: String = "Movie,Series",
        @Query("sortBy") sortBy: String = "SortName",
        @Query("enableImages") enableImages: Boolean = false,
        @Query("enableTotalRecordCount") enableTotalRecordCount: Boolean = false,
    ): EmbyItemsResponse

    @GET("Users/{userId}/Items/Resume")
    suspend fun getResumeItems(
        @Path("userId") userId: String,
        @Query("fields") fields: String = ITEM_FIELDS,
        @Query("limit") limit: Int = 100,
        @Query("enableUserData") enableUserData: Boolean = true,
    ): EmbyItemsResponse

    @GET("Users/{userId}/Items/{id}") suspend fun getItem(@Path("id") id: String, @Path("userId") userId: String): EmbyItem
    @GET("Items/{id}/Similar") suspend fun getSimilar(@Path("id") id: String, @Query("userId") userId: String, @Query("limit") limit: Int = 20, @Query("fields") fields: String = ITEM_FIELDS): EmbyItemsResponse
    /** Trailers stored with the item. */
    @GET("Users/{userId}/Items/{id}/LocalTrailers") suspend fun getLocalTrailers(@Path("id") id: String, @Path("userId") userId: String): List<EmbyItem>
    @GET("Items/{id}/ThemeSongs") suspend fun getThemeSongs(@Path("id") id: String, @Query("userId") userId: String, @Query("inheritFromParent") inheritFromParent: Boolean = true): EmbyItemsResponse
    @POST("Items/{id}/PlaybackInfo") suspend fun getPlaybackInfo(@Path("id") id: String, @Body body: EmbyPlaybackInfoRequest): EmbyPlaybackInfoResponse
    @POST("Sessions/Playing") suspend fun reportPlaybackStart(@Body body: EmbyPlaybackProgress)
    @POST("Sessions/Playing/Progress") suspend fun reportPlaybackProgress(@Body body: EmbyPlaybackProgress)
    @POST("Sessions/Playing/Stopped") suspend fun reportPlaybackStopped(@Body body: EmbyPlaybackProgress)
    @POST("Users/{userId}/PlayedItems/{id}") suspend fun markPlayed(@Path("userId") userId: String, @Path("id") id: String)
    @DELETE("Users/{userId}/PlayedItems/{id}") suspend fun markUnplayed(@Path("userId") userId: String, @Path("id") id: String)
    @POST("Users/{userId}/Items/{id}/UserData") suspend fun updateUserData(@Path("userId") userId: String, @Path("id") id: String, @Body body: EmbyUserDataUpdate): EmbyUserData

    companion object {
        // Since Emby 4.2 a list response omits these unless asked, and every card shows them.
        const val CARD_FIELDS = "ProductionYear,OfficialRating,CommunityRating,CriticRating"
        const val ITEM_FIELDS = "Overview,Taglines,Genres,People,ProviderIds,DateCreated,PremiereDate,Studios,MediaSources,MediaStreams,ChildCount,RecursiveItemCount,$CARD_FIELDS"
    }
}

package com.ambient.tv.data.remote

import com.ambient.tv.data.remote.dto.FilterChoicesResponse
import com.ambient.tv.data.remote.dto.HubsResponse
import com.ambient.tv.data.remote.dto.IdentityResponse
import com.ambient.tv.data.remote.dto.LibrarySectionsResponse
import com.ambient.tv.data.remote.dto.MetadataResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.PUT

interface PlexApiService {

    @GET("identity")
    suspend fun getIdentity(): IdentityResponse

    @GET("library/sections")
    suspend fun getLibrarySections(): LibrarySectionsResponse

    @GET("library/metadata/{id}")
    suspend fun getMetadata(
        @Path("id") ratingKey: String,
        @Query("includeGuids") includeGuids: Int = 1,
    ): MetadataResponse

    @GET("library/metadata/{id}")
    suspend fun getPlaybackTimeline(
        @Path("id") ratingKey: String,
        @Query("mediaIndex") mediaIndex: Int = 0,
        @Query("includeChapters") includeChapters: Int = 1,
        @Query("includeMarkers") includeMarkers: Int = 1,
        @Query("includeGuids") includeGuids: Int = 0,
    ): MetadataResponse

    @GET("library/onDeck")
    suspend fun getOnDeck(@Query("includeGuids") includeGuids: Int = 1): MetadataResponse

    /** Direct children of a show, season or collection, in server order. */
    @GET("library/metadata/{id}/children")
    suspend fun getChildren(
        @Path("id") ratingKey: String,
        @Query("includeGuids") includeGuids: Int = 1,
        @Query("X-Plex-Container-Start") start: Int? = null,
        @Query("X-Plex-Container-Size") size: Int? = null,
    ): MetadataResponse

    /** Local extras of a title (trailers, featurettes) stored next to the media. */
    @GET("library/metadata/{id}/extras")
    suspend fun getExtras(@Path("id") ratingKey: String): MetadataResponse

    @GET("library/metadata/{id}/similar")
    suspend fun getSimilar(
        @Path("id") ratingKey: String,
        @Query("includeGuids") includeGuids: Int = 1,
    ): MetadataResponse

    @GET("library/sections/{id}/all")
    suspend fun getSectionItems(
        @Path("id") sectionKey: String,
        @Query("title") titleFilter: String? = null,
        @Query("actor") actorFilter: Long? = null,
        @Query("collection") collectionFilter: Long? = null,
        @Query("sort") sort: String? = null,
        @Query("X-Plex-Container-Start") start: Int? = null,
        @Query("X-Plex-Container-Size") size: Int? = null,
        @Query("includeGuids") includeGuids: Int = 1,
        /** Plex metadata type to list instead of the section's top level, e.g. 4 for episodes. */
        @Query("type") type: Int? = null,
        /** 1 keeps unplayed movies or episodes. */
        @Query("unwatched") unwatched: Int? = null,
        /** 1 keeps shows that still have unplayed episodes. */
        @Query("unwatchedLeaves") unwatchedLeaves: Int? = null,
        /** Genre id from [getSectionGenres]. */
        @Query("genre") genreFilter: String? = null,
    ): MetadataResponse

    @GET("library/sections/{id}/genre")
    suspend fun getSectionGenres(@Path("id") sectionKey: String): FilterChoicesResponse

    /** The "Recommended" rows Plex builds for a library; [count] caps the items per row. */
    @GET("hubs/sections/{id}")
    suspend fun getSectionHubs(
        @Path("id") sectionKey: String,
        @Query("count") count: Int,
        @Query("includeGuids") includeGuids: Int = 1,
    ): HubsResponse

    // /:/progress, /:/scrobble and /:/unscrobble take the bare rating key (as Plex Web and
    // python-plexapi send it), not the /library/metadata/<id> path.
    @GET(":/progress")
    suspend fun reportProgress(
        @Query("key") ratingKey: String,
        @Query("time") timeMs: Long,
        @Query("state") state: String,
        @Query("identifier") identifier: String = "com.plexapp.plugins.library",
    )

    /**
     * What official clients send while playing: besides the resume offset it creates the server's
     * "Now Playing" session (dashboard, Tautulli, concurrent-stream limits) for this client.
     */
    @GET(":/timeline")
    suspend fun reportTimeline(
        @Query("ratingKey") ratingKey: String,
        @Query("key") key: String,
        @Query("time") timeMs: Long,
        @Query("state") state: String,
        @Query("duration") durationMs: Long? = null,
        @Query("identifier") identifier: String = "com.plexapp.plugins.library",
    )

    @GET(":/scrobble")
    suspend fun markWatched(
        @Query("key") ratingKey: String,
        @Query("identifier") identifier: String = "com.plexapp.plugins.library",
    )

    @GET(":/unscrobble")
    suspend fun markUnwatched(
        @Query("key") ratingKey: String,
        @Query("identifier") identifier: String = "com.plexapp.plugins.library",
    )

    @PUT("actions/removeFromContinueWatching")
    suspend fun removeFromContinueWatching(
        @Query("ratingKey") ratingKey: String,
    )
}

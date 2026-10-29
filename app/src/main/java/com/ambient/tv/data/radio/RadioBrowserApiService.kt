package com.ambient.tv.data.radio

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RadioBrowserApiService {
    @GET("json/stations/search")
    suspend fun searchStations(
        @Query("name") name: String? = null,
        @Query("countrycode") countryCode: String? = null,
        @Query("language") language: String? = null,
        @Query("languageExact") languageExact: Boolean = false,
        @Query("tag") tag: String? = null,
        @Query("tagExact") tagExact: Boolean = false,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 50,
        @Query("hidebroken") hideBroken: Boolean = true,
    ): List<RadioBrowserStationDto>

    @GET("json/tags")
    suspend fun tags(
        @Query("order") order: String = "stationcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 100,
        @Query("hidebroken") hideBroken: Boolean = true,
    ): List<RadioBrowserFacetDto>

    @GET("json/countrycodes")
    suspend fun countryCodes(
        @Query("order") order: String = "stationcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 100,
        @Query("hidebroken") hideBroken: Boolean = true,
    ): List<RadioBrowserFacetDto>

    @GET("json/languages")
    suspend fun languages(
        @Query("order") order: String = "stationcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 100,
        @Query("hidebroken") hideBroken: Boolean = true,
    ): List<RadioBrowserFacetDto>

    @GET("json/stations/topclick/{limit}")
    suspend fun topClicked(
        @Path("limit") limit: Int,
        @Query("hidebroken") hideBroken: Boolean = true,
    ): List<RadioBrowserStationDto>

    @GET("json/stations/byuuid/{stationUuid}")
    suspend fun stationByUuid(
        @Path("stationUuid") stationUuid: String,
        @Query("hidebroken") hideBroken: Boolean = true,
    ): List<RadioBrowserStationDto>

    @GET("json/url/{stationUuid}")
    suspend fun resolveAndCountClick(
        @Path("stationUuid") stationUuid: String,
    ): RadioBrowserClickDto
}

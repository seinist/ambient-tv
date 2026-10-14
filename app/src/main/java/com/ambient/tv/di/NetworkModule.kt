package com.ambient.tv.di

import com.ambient.tv.BuildConfig
import com.ambient.tv.util.withoutUrlQueries
import com.ambient.tv.data.remote.DynamicServerUrlInterceptor
import com.ambient.tv.data.remote.PlexApiService
import com.ambient.tv.data.remote.PlexHeadersInterceptor
import com.ambient.tv.data.remote.PlexTvApiService
import com.ambient.tv.data.remote.PlexTvClient
import com.ambient.tv.data.remote.PlexTvHeadersInterceptor
import com.ambient.tv.data.jellyfin.JellyfinApiService
import com.ambient.tv.data.jellyfin.JellyfinClient
import com.ambient.tv.data.jellyfin.JellyfinDynamicUrlInterceptor
import com.ambient.tv.data.jellyfin.JellyfinHeadersInterceptor
import com.ambient.tv.data.emby.EmbyApiService
import com.ambient.tv.data.emby.EmbyClient
import com.ambient.tv.data.emby.EmbyDynamicUrlInterceptor
import com.ambient.tv.data.emby.EmbyHeadersInterceptor
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private const val PLEX_BASE_URL = "http://plex.local.invalid/"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Shared connection pool for Plex/JF/Emby API + plex.tv + health probes.
     * Streaming / auth-proxy clients intentionally keep their own pools.
     */
    @Provides
    @Singleton
    @SharedOkHttp
    fun provideSharedConnectionPool(): ConnectionPool =
        ConnectionPool(maxIdleConnections = 10, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES)

    @Provides
    @Singleton
    @SharedOkHttp
    fun provideSharedDispatcher(): Dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicServerUrlInterceptor: DynamicServerUrlInterceptor,
        plexHeadersInterceptor: PlexHeadersInterceptor,
        preferences: com.ambient.tv.data.local.AppPreferences,
        availability: com.ambient.tv.data.browse.SourceAvailability,
        @SharedOkHttp connectionPool: ConnectionPool,
        @SharedOkHttp dispatcher: Dispatcher,
    ): OkHttpClient = OkHttpClient.Builder()
        .dns(com.ambient.tv.network.LanOnlyDns({ preferences.localNetworkOnly }))
        .addInterceptor(availability.interceptor("plex"))
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .addInterceptor(dynamicServerUrlInterceptor)
        .addInterceptor(plexHeadersInterceptor)
        // /identity answers without a token; every other call shows whether the token works.
        .addInterceptor(com.ambient.tv.data.remote.LoginStateInterceptor({ it.startsWith("/identity") }) { accepted ->
            preferences.plexLoginVerified = accepted
        })
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor { message -> timber.log.Timber.tag("Http").d(message.withoutUrlQueries()) }.apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
        .let(com.ambient.tv.network.CredentialRedirectGuard::install).build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(PLEX_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun providePlexApiService(retrofit: Retrofit): PlexApiService = retrofit.create(PlexApiService::class.java)

    // plex.tv account-linking calls must go to the real plex.tv host, so they deliberately
    // skip DynamicServerUrlInterceptor (which rewrites every request to the local server).
    @Provides
    @Singleton
    @PlexTvClient
    fun providePlexTvOkHttpClient(
        plexTvHeadersInterceptor: PlexTvHeadersInterceptor,
        @SharedOkHttp connectionPool: ConnectionPool,
        @SharedOkHttp dispatcher: Dispatcher,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .addInterceptor(plexTvHeadersInterceptor)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor { message -> timber.log.Timber.tag("Http").d(message.withoutUrlQueries()) }.apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
            .let(com.ambient.tv.network.CredentialRedirectGuard::install).build()

    @Provides
    @Singleton
    @PlexTvClient
    fun providePlexTvRetrofit(@PlexTvClient okHttpClient: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://plex.tv/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun providePlexTvApiService(@PlexTvClient retrofit: Retrofit): PlexTvApiService =
        retrofit.create(PlexTvApiService::class.java)

    @Provides
    @Singleton
    @JellyfinClient
    fun provideJellyfinOkHttpClient(
        dynamicUrlInterceptor: JellyfinDynamicUrlInterceptor,
        headersInterceptor: JellyfinHeadersInterceptor,
        preferences: com.ambient.tv.data.local.AppPreferences,
        availability: com.ambient.tv.data.browse.SourceAvailability,
        @SharedOkHttp connectionPool: ConnectionPool,
        @SharedOkHttp dispatcher: Dispatcher,
    ): OkHttpClient = OkHttpClient.Builder()
        .dns(com.ambient.tv.network.LanOnlyDns({ preferences.localNetworkOnly }))
        .addInterceptor(availability.interceptor("jellyfin"))
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .addInterceptor(dynamicUrlInterceptor)
        .addInterceptor(headersInterceptor)
        // A revoked session hides the active server from menus until it signs in again.
        .addInterceptor(com.ambient.tv.data.remote.LoginStateInterceptor({ com.ambient.tv.data.remote.mediaBrowserAnonymousPath(it) }) { accepted ->
            preferences.activeJellyfinServerId?.let { preferences.setLoginRejected("jellyfin:$it", !accepted) }
        })
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor { message -> timber.log.Timber.tag("Http").d(message.withoutUrlQueries()) }.apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
        .let(com.ambient.tv.network.CredentialRedirectGuard::install).build()

    @Provides
    @Singleton
    @JellyfinClient
    fun provideJellyfinRetrofit(@JellyfinClient client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("http://jellyfin.local.invalid/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideJellyfinApiService(@JellyfinClient retrofit: Retrofit): JellyfinApiService =
        retrofit.create(JellyfinApiService::class.java)

    @Provides
    @Singleton
    @EmbyClient
    fun provideEmbyOkHttpClient(
        dynamicUrlInterceptor: EmbyDynamicUrlInterceptor,
        headersInterceptor: EmbyHeadersInterceptor,
        preferences: com.ambient.tv.data.local.AppPreferences,
        availability: com.ambient.tv.data.browse.SourceAvailability,
        @SharedOkHttp connectionPool: ConnectionPool,
        @SharedOkHttp dispatcher: Dispatcher,
    ): OkHttpClient = OkHttpClient.Builder()
        .dns(com.ambient.tv.network.LanOnlyDns({ preferences.localNetworkOnly }))
        .addInterceptor(availability.interceptor("emby"))
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .addInterceptor(dynamicUrlInterceptor)
        .addInterceptor(headersInterceptor)
        // A revoked session hides the active server from menus until it signs in again.
        .addInterceptor(com.ambient.tv.data.remote.LoginStateInterceptor({ com.ambient.tv.data.remote.mediaBrowserAnonymousPath(it) }) { accepted ->
            preferences.activeEmbyServerId?.let { preferences.setLoginRejected("emby:$it", !accepted) }
        })
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor { message -> timber.log.Timber.tag("Http").d(message.withoutUrlQueries()) }.apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
        .let(com.ambient.tv.network.CredentialRedirectGuard::install).build()

    @Provides
    @Singleton
    @EmbyClient
    fun provideEmbyRetrofit(@EmbyClient client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("http://emby.local.invalid/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideEmbyApiService(@EmbyClient retrofit: Retrofit): EmbyApiService =
        retrofit.create(EmbyApiService::class.java)
}

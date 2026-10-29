package com.ambient.tv.di

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.radio.RadioBrowserApiService
import com.ambient.tv.data.radio.RadioBrowserClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private const val RADIO_BROWSER_BASE_URL = "https://de1.api.radio-browser.info/"

@Module
@InstallIn(SingletonComponent::class)
object RadioBrowserModule {
    @Provides
    @Singleton
    @RadioBrowserClient
    fun provideRadioBrowserOkHttpClient(): OkHttpClient {
        val userAgent = "Ambient-TV/" + BuildConfig.VERSION_NAME
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build(),
                )
            })
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor { message ->
                            timber.log.Timber.tag("RadioBrowser").d(message)
                        }.apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @RadioBrowserClient
    fun provideRadioBrowserRetrofit(
        @RadioBrowserClient client: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(RADIO_BROWSER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideRadioBrowserApiService(
        @RadioBrowserClient retrofit: Retrofit,
    ): RadioBrowserApiService = retrofit.create(RadioBrowserApiService::class.java)
}

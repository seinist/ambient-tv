package com.ambient.tv.di

import com.ambient.tv.data.emby.EmbyClient
import com.ambient.tv.data.jellyfin.JellyfinClient
import com.ambient.tv.data.photos.PhotoJsonApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import retrofit2.Retrofit

/** Raw JSON photo endpoints on the existing server clients (same host rewrite and auth headers). */
@Module
@InstallIn(SingletonComponent::class)
object PhotoModule {
    @Provides @Singleton @Named("plexPhotos")
    fun providePlexPhotoApi(retrofit: Retrofit): PhotoJsonApi = retrofit.create(PhotoJsonApi::class.java)

    @Provides @Singleton @Named("jellyfinPhotos")
    fun provideJellyfinPhotoApi(@JellyfinClient retrofit: Retrofit): PhotoJsonApi = retrofit.create(PhotoJsonApi::class.java)

    @Provides @Singleton @Named("embyPhotos")
    fun provideEmbyPhotoApi(@EmbyClient retrofit: Retrofit): PhotoJsonApi = retrofit.create(PhotoJsonApi::class.java)
}

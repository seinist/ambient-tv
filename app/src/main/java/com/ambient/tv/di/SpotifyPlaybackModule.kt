package com.ambient.tv.di

import com.ambient.tv.spotify.playback.LibrespotPlaybackEngine
import com.ambient.tv.spotify.playback.SpotifyPlaybackEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SpotifyPlaybackModule {
    @Binds
    @Singleton
    abstract fun bindSpotifyPlaybackEngine(engine: LibrespotPlaybackEngine): SpotifyPlaybackEngine
}

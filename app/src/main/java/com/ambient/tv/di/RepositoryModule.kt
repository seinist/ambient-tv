package com.ambient.tv.di

import com.ambient.tv.data.repository.RoutingMediaRepository
import com.ambient.tv.domain.repository.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindMediaRepository(repository: RoutingMediaRepository): MediaRepository
}

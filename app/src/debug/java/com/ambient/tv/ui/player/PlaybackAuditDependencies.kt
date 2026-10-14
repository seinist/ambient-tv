package com.ambient.tv.ui.player

import android.content.Context
import com.ambient.tv.data.browse.PlaybackAvailability
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackAuditDependencies {
    fun playbackAvailability(): PlaybackAvailability
    fun sourceAvailability(): com.ambient.tv.data.browse.SourceAvailability
    fun mediaRepository(): com.ambient.tv.domain.repository.MediaRepository
    fun browseScopes(): com.ambient.tv.data.browse.BrowseScopes
}

internal fun auditPlaybackAvailability(context: Context): PlaybackAvailability =
    EntryPointAccessors.fromApplication(context.applicationContext, PlaybackAuditDependencies::class.java).playbackAvailability()

internal fun auditSourceAvailability(context: Context): com.ambient.tv.data.browse.SourceAvailability =
    EntryPointAccessors.fromApplication(context.applicationContext, PlaybackAuditDependencies::class.java).sourceAvailability()

package com.ambient.tv.data.remote

import javax.inject.Qualifier

/** Marks bindings for talking to plex.tv directly (no [DynamicServerUrlInterceptor]). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlexTvClient

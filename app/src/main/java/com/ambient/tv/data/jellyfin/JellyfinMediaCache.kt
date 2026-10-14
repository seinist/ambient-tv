package com.ambient.tv.data.jellyfin

import com.ambient.tv.data.repository.PlexMediaCache
import javax.inject.Inject
import javax.inject.Singleton

/** Separate process cache so identical Plex and Jellyfin item ids can never contaminate each other. */
@Singleton
class JellyfinMediaCache @Inject constructor() : PlexMediaCache()


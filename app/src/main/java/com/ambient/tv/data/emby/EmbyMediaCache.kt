package com.ambient.tv.data.emby

import com.ambient.tv.data.repository.PlexMediaCache
import javax.inject.Inject
import javax.inject.Singleton

/** Separate process cache so identical Plex and Emby item ids can never contaminate each other. */
@Singleton
class EmbyMediaCache @Inject constructor() : PlexMediaCache()


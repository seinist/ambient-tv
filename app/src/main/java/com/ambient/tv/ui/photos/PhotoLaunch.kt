package com.ambient.tv.ui.photos

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.data.photos.PhotoRepository
import com.ambient.tv.data.photos.PhotoViewerSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Entry points into the photo viewer from places without a photo view model (Home, screensaver). */
object PhotoLaunch {
    /** Home row items carry this prefix so media selection can route them to the viewer. */
    const val HOME_PREFIX = "photohome:"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun photoRepository(): PhotoRepository
        fun photoSession(): PhotoViewerSession
        fun appPreferences(): com.ambient.tv.data.local.AppPreferences
    }

    fun deps(context: Context): Deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    /** Prepares the viewer for a home row item; false when the list is gone (e.g. after a refresh). */
    fun prepareFromHome(context: Context, ratingKey: String): Boolean {
        val deps = deps(context)
        val id = ratingKey.removePrefix(HOME_PREFIX)
        val entries = deps.photoRepository().lastRecent
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        deps.photoSession().request = PhotoViewerSession.Request(
            title = context.getString(R.string.photos_recent_row),
            entries = entries,
            startIndex = index,
            startSlideshow = false,
            albumKey = null,
        )
        return true
    }
}

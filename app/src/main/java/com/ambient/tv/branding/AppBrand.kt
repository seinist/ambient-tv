package com.ambient.tv.branding

import com.ambient.tv.BuildConfig

/** Display identity shared by UI resources, default receiver names and server product headers. */
object AppBrand {
    val name: String get() = BuildConfig.APP_DISPLAY_NAME
}

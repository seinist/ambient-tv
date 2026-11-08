package com.ambient.tv.ui.tvheadend

import android.net.Uri
import androidx.media3.common.MimeTypes

/** Service-started audio and its player screen must use the same Media3 acquisition identity. */
internal fun liveTvMedia3MimeType(url: String): String? =
    MimeTypes.APPLICATION_M3U8.takeIf { Uri.parse(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true) }

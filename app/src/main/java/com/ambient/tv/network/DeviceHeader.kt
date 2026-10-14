package com.ambient.tv.network

import android.os.Build

/**
 * OkHttp rejects header values with characters outside printable ASCII (it throws, so every
 * request would fail). Some vendors ship model names with non-ASCII characters, so device names
 * are filtered before they go into a header.
 */
internal fun asciiHeaderValue(raw: String?, fallback: String): String =
    raw.orEmpty().filter { it in ' '..'~' }.trim().ifEmpty { fallback }

/** `Build.MODEL` as a safe header value. */
internal val deviceModelHeader: String by lazy { asciiHeaderValue(Build.MODEL, "Android TV") }

package com.ambient.tv.ui.common

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Request headers for an artwork load (server auth, Referer, …). Coil 3 replaced the per-header
 * `setHeader` of Coil 2 with one header set per request; an empty map leaves the request as is.
 */
internal fun ImageRequest.Builder.requestHeaders(headers: Map<String, String>): ImageRequest.Builder =
    if (headers.isEmpty()) this
    else httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())

/**
 * Shared player artwork may mix authenticated Live TV images and public TMDB backdrops in its
 * fallback chain. Never forward a TV/media-server credential to TMDB, including after a retry.
 */
internal fun artworkHeadersForModel(model: Any?, headers: Map<String, String>): Map<String, String> {
    val url = (model as? String)?.toHttpUrlOrNull()
    return if (url?.host == "image.tmdb.org") emptyMap() else headers
}

package com.ambient.tv.ui.common

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

/**
 * Request headers for an artwork load (server auth, Referer, …). Coil 3 replaced the per-header
 * `setHeader` of Coil 2 with one header set per request; an empty map leaves the request as is.
 */
internal fun ImageRequest.Builder.requestHeaders(headers: Map<String, String>): ImageRequest.Builder =
    if (headers.isEmpty()) this
    else httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())

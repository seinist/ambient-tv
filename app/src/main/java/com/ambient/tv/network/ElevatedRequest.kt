package com.ambient.tv.network

import okhttp3.Request

/**
 * Marks administrator-only requests (metadata editing, rights checks). There a 401/403 means
 * "this user may not", not a revoked login or an unusable server, so login and availability
 * tracking ignore those answers.
 */
object ElevatedRequest {
    const val HEADER = "X-Ambient-Elevated"
    /** For Retrofit's `@Headers`. */
    const val MARKER = "$HEADER: 1"

    fun isElevated(request: Request): Boolean = request.header(HEADER) != null
}

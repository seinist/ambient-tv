@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource

/**
 * Retry only an expired HTTP URL during open(), never a partially consumed read.
 * Preserve DataSpec (range, headers, flags, key, method, body) while changing only its URI.
 * The factory shares the same replacement/cooldown map across parallel audio/video loads.
 */
internal class RenewingMedia3DataSource(
    private val upstream: DataSource,
    private val policy: StreamUrlRenewalPolicy,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme !in setOf("http", "https")) return upstream.open(dataSpec)
        val original = dataSpec.uri.toString()
        val current = policy.current(original)
        val request = if (current == original) dataSpec else dataSpec.withUri(Uri.parse(current))
        try {
            return upstream.open(request)
        } catch (failure: HttpDataSource.InvalidResponseCodeException) {
            if (failure.responseCode != 403 && failure.responseCode != 410) throw failure
            // Media3 HTTP sources can be partially initialized after a failed open.
            // Close before attempting a replacement so no native connection/transfer remains.
            try {
                upstream.close()
            } catch (closeError: Exception) {
                failure.addSuppressed(closeError)
                throw failure
            }
            val fresh = policy.replacement(original, current) ?: throw failure
            return upstream.open(dataSpec.withUri(Uri.parse(fresh)))
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        renew: (String) -> String?,
    ) : DataSource.Factory {
        private val policy = StreamUrlRenewalPolicy(renew)
        override fun createDataSource(): DataSource =
            RenewingMedia3DataSource(upstream.createDataSource(), policy)
    }
}

/** Keep the transport-neutral factory entry point used by Main. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RenewingMedia3DataSourceFactory(upstream: DataSource.Factory, renew: (String) -> String?) :
    DataSource.Factory by RenewingMedia3DataSource.Factory(upstream, renew)

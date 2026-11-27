package com.ambient.tv.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

/** Per-player signed-URL recovery for every Media3 HTTP transport. */
@OptIn(UnstableApi::class)
internal class RenewingMedia3DataSourceFactory(
    private val upstream: DataSource.Factory,
    private val renew: (String) -> String?,
) : DataSource.Factory {
    private val replacements = LinkedHashMap<String, String>()
    private val attempted = LinkedHashMap<String, Long>()

    @Synchronized private fun current(url: String): String = replacements[url] ?: url

    @Synchronized private fun replacement(original: String, failed: String): String? {
        current(original).takeIf { it != failed }?.let { return it }
        val now = System.nanoTime()
        attempted[failed]?.let { if (now - it < 30_000_000_000L) return null }
        attempted[failed] = now
        while (attempted.size > 64) attempted.remove(attempted.keys.first())
        val fresh = runCatching { renew(failed) }.getOrNull()?.takeIf { it.isNotBlank() && it != failed } ?: return null
        replacements[original] = fresh
        while (replacements.size > 64) replacements.remove(replacements.keys.first())
        return fresh
    }

    override fun createDataSource(): DataSource {
        val source = upstream.createDataSource()
        return object : DataSource by source {
            override fun open(dataSpec: DataSpec): Long {
                val original = dataSpec.uri.toString()
                val url = current(original)
                val request = if (url == original) dataSpec else dataSpec.withUri(Uri.parse(url))
                try {
                    return source.open(request)
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    if (error.responseCode != 403 && error.responseCode != 410) throw error
                    val fresh = replacement(original, url) ?: throw error
                    try {
                        source.close()
                    } catch (closeError: IOException) {
                        error.addSuppressed(closeError)
                        throw error
                    }
                    return source.open(dataSpec.withUri(Uri.parse(fresh)))
                }
            }
        }
    }
}

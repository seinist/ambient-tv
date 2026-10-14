package com.ambient.tv.companion

import coil3.SingletonImageLoader
import coil3.toBitmap
import com.ambient.tv.ui.common.requestHeaders
import coil3.request.allowHardware
import android.content.Context
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One bounded thumbnail for the current TV item. No URLs/credentials leave the receiver. */
internal class CompanionPlaybackArtwork(private val context: Context, private val scope: CoroutineScope) {
    private data class Key(val media: CompanionMedia?, val url: String?, val headers: Map<String, String>)
    private var key: Key? = null
    private var job: Job? = null
    @Volatile private var jpeg = ""

    fun update(media: CompanionMedia?, url: String?, headers: Map<String, String> = emptyMap()): String {
        val next = Key(media, url, headers)
        if (next != key) {
            job?.cancel(); key = next; jpeg = ""
            if (!url.isNullOrBlank()) job = scope.launch(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false)
                    request.requestHeaders(headers)
                    val image = (SingletonImageLoader.get(context).execute(request.build()) as? SuccessResult)?.image ?: return@launch
                    val width = image.width.coerceAtLeast(1)
                    val height = image.height.coerceAtLeast(1)
                    val ratio = 128f / maxOf(width, height)
                    val bitmap = image.toBitmap((width * ratio).toInt().coerceIn(1, 128), (height * ratio).toInt().coerceIn(1, 128))
                    // The image may hand us its cached bitmap; do not recycle Coil's cache.
                    val bytes = ByteArrayOutputStream()
                    for (quality in listOf(80, 60, 40)) {
                        bytes.reset(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bytes)
                        if (bytes.size() <= 3072) break
                    }
                    if (bytes.size() <= 3072) {
                        val encoded = Base64.getEncoder().encodeToString(bytes.toByteArray())
                        kotlinx.coroutines.withContext(Dispatchers.Main) { if (key == next) jpeg = encoded }
                    }
                }
            }
        }
        return jpeg
    }

    fun clear() { job?.cancel(); job = null; key = null; jpeg = "" }
}

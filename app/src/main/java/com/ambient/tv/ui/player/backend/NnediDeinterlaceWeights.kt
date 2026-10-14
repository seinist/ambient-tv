package com.ambient.tv.ui.player.backend

import android.content.Context
import com.ambient.tv.R
import java.io.File
import java.security.MessageDigest

/** Called on Dispatchers.IO; the file is bundled and never downloaded during playback. */
internal object NnediDeinterlaceWeights {
    private const val SHA256 = "27f382430435bb7613deb1c52f3c79c300c9869812cfe29079432a9c82251d42"

    @Synchronized
    fun path(context: Context): String {
        val target = File(context.cacheDir, "nnedi3-deinterlace-${SHA256.take(12)}.bin")
        fun valid(file: File): Boolean {
            if (file.length() != 13_574_928L) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == SHA256
        }
        if (!valid(target)) {
            val staging = File.createTempFile("nnedi-deint-", ".bin", context.cacheDir)
            try {
                context.resources.openRawResource(R.raw.nnedi3_deinterlace_weights).use { input ->
                    staging.outputStream().use { output -> input.copyTo(output, 65536) }
                }
                check(valid(staging)) { "Invalid bundled NNEDI weights" }
                check(staging.renameTo(target)) { "Cannot publish NNEDI weights" }
            } finally {
                staging.delete()
            }
        }
        return target.absolutePath
    }
}

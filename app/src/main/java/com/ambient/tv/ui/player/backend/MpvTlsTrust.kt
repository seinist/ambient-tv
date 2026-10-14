package com.ambient.tv.ui.player.backend

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import timber.log.Timber

/**
 * mpv fetches network streams through libcurl/mbedTLS with tls-verify on, and mbedTLS cannot read
 * Android's trust store. Export the system and user CAs (the same ones Media3 trusts) as one PEM
 * bundle for `tls-ca-file`; without it every HTTPS stream fails certificate verification.
 */
internal object MpvTlsTrust {
    @Volatile private var bundle: File? = null

    fun caFile(context: Context): File? {
        bundle?.let { return it }
        return synchronized(this) {
            bundle ?: runCatching { export(context) }
                .onFailure { Timber.w(it, "MPV CA bundle export failed") }
                .getOrNull()
                ?.also { bundle = it }
        }
    }

    private fun export(context: Context): File {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val pem = buildString {
            for (alias in store.aliases()) {
                val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.encodeToString(cert.encoded, Base64.DEFAULT))
                append("-----END CERTIFICATE-----\n")
            }
        }
        check(pem.isNotEmpty()) { "Android trust store is empty" }
        val file = File(context.noBackupFilesDir, "mpv-cacert.pem")
        // Rewritten once per process, so installed or removed user CAs apply after a restart.
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(pem)
        check(temp.renameTo(file)) { "cannot replace $file" }
        return file
    }
}

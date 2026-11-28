package com.ambient.tv.moonlight

import java.io.IOException

/** Discovery metadata alone never confers trust. */
data class MoonlightServerInfo(
    val name: String,
    val uniqueId: String,
    val appVersion: String,
    val httpsPort: Int,
    val state: String,
)

fun parseMoonlightServerInfo(xml: ByteArray): MoonlightServerInfo {
    val fields = mutableMapOf<String, String>()
    val wanted = setOf("hostname", "uniqueid", "appversion", "HttpsPort", "state")
    parseMoonlightXml(xml, 64 * 1024, 512, onStart = { path, attrs ->
        if (path.size == 1 && (path[0] != "root" || attrs.getValue("status_code") != "200"))
            throw IOException("Invalid Moonlight server response")
    }, onEnd = { path, value ->
        if (path.size == 2 && path[1] in wanted) {
            if (fields.put(path[1], value) != null) throw IOException("Duplicate serverinfo field")
        }
    })
    fun field(name: String) = fields[name].orEmpty()
    val version = field("appversion")
    val id = field("uniqueid")
    if (version.isBlank() || version.length > 128 || version.any { it.isISOControl() } ||
        id.length !in 1..128 || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        throw IOException("Invalid Moonlight server identity")
    return MoonlightServerInfo(
        name = field("hostname").take(128).filterNot { it.isISOControl() }.ifBlank { id },
        uniqueId = id,
        appVersion = version,
        httpsPort = field("HttpsPort").toIntOrNull()?.takeIf { it in 1..65535 }
            ?: MoonlightHostAddress.DEFAULT_HTTPS_PORT,
        state = field("state").take(128).filterNot { it.isISOControl() },
    )
}

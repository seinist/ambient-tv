package com.ambient.tv.moonlight

import java.io.IOException
import kotlinx.serialization.Serializable

/** Metadata accepted only after pinned, authenticated HTTPS. */
@Serializable
data class MoonlightApp(val id: Int, val title: String, val hdrSupported: Boolean)
data class MoonlightAppCatalog(val apps: List<MoonlightApp>)

fun parseMoonlightAppList(xml: ByteArray): MoonlightAppCatalog {
    val result = LinkedHashMap<Int, MoonlightApp>()
    val fields = mutableMapOf<String, String>()
    val wanted = setOf("ID", "AppTitle", "IsHdrSupported")
    var count = 0
    var duplicate = false
    parseMoonlightXml(xml, 1024 * 1024, 65_536, onStart = { path, attrs ->
        if (path.size == 1 && (path[0] != "root" || attrs.getValue("status_code") != "200"))
            throw IOException("Moonlight app list rejected")
        if (path.size == 2 && path[1] == "App") {
            if (++count > 4096) throw IOException("Too many Moonlight apps")
            fields.clear()
            duplicate = false
        }
    }, onEnd = { path, value ->
        if (path.size == 3 && path[1] == "App" && path[2] in wanted) {
            if (fields.put(path[2], value) != null) duplicate = true
        }
        if (path.size == 2 && path[1] == "App" && !duplicate) {
            val id = fields["ID"]?.toIntOrNull()
            val title = fields["AppTitle"].orEmpty()
            if (id != null && id > 0 && title.isNotBlank() && title.length <= 240 &&
                title.none { it.isISOControl() } && id !in result)
                result[id] = MoonlightApp(id, title, fields["IsHdrSupported"] == "1")
        }
    })
    return MoonlightAppCatalog(result.values.toList())
}

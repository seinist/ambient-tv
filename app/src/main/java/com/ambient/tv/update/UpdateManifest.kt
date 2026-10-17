package com.ambient.tv.update

import org.json.JSONObject

internal const val UPDATE_REPOSITORY = "seinist/ambient-tv"
internal const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

internal data class UpdateApk(val name: String, val abi: String, val size: Long, val sha256: String)
internal data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apks: List<UpdateApk>,
) {
    fun select(supportedAbis: List<String>): UpdateApk? = supportedAbis.firstNotNullOfOrNull { abi ->
        apks.firstOrNull { it.abi == abi }
    } ?: apks.firstOrNull { it.abi == "universal" }

    companion object {
        fun parse(text: String): UpdateManifest {
            val json = JSONObject(text)
            require(json.getInt("schemaVersion") == 1)
            require(json.getString("packageName") == "com.ambient.tv")
            val code = json.getLong("versionCode")
            val name = json.getString("versionName")
            val minSdk = json.getInt("minSdk")
            require(code in 1..Int.MAX_VALUE && name.matches(Regex("[0-9A-Za-z._+\\-]{1,64}")) && minSdk > 0)
            val entries = json.getJSONArray("apks")
            require(entries.length() in 1..5)
            val apks = (0 until entries.length()).map { index ->
                val item = entries.getJSONObject(index)
                UpdateApk(item.getString("name"), item.getString("abi"), item.getLong("size"), item.getString("sha256")).also {
                    require(it.name.matches(Regex("[A-Za-z0-9._-]+\\.apk")))
                    require(it.abi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal"))
                    require(it.size in 1..4_000_000_000L && it.sha256.matches(Regex("[0-9a-f]{64}")))
                }
            }
            require(apks.map { it.name }.distinct().size == apks.size && apks.map { it.abi }.distinct().size == apks.size)
            return UpdateManifest(code, name, minSdk, apks)
        }
    }
}

internal fun updateCheckDue(now: Long, lastAttempt: Long): Boolean =
    lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= UPDATE_INTERVAL_MS

internal fun trustedReleaseAsset(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
        uri.path.startsWith("/$UPDATE_REPOSITORY/releases/download/") &&
        uri.path.split('/').none { it == "." || it == ".." } && uri.query == null && uri.fragment == null
}.getOrDefault(false)

package com.ambient.tv.update

import org.json.JSONObject
import org.json.JSONArray
import okhttp3.HttpUrl

internal const val UPDATE_REPOSITORY = "seinist/ambient-tv"
internal const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

internal const val UPDATE_PACKAGING_METADATA = "com.ambient.tv.MODULE_PACKAGING"

internal enum class UpdateVariant(val id: String, val manifestAsset: String) {
    FULL("full", "update.json"),
    MODULAR("modular", "update-modular.json");

    companion object {
        fun fromId(id: String?): UpdateVariant? = entries.firstOrNull { it.id == id }
    }
}

internal fun updateVersionAllowed(installedCode: Long, installed: UpdateVariant?, incomingCode: Long, target: UpdateVariant): Boolean =
    installed != null && (incomingCode > installedCode || incomingCode == installedCode && installed != target)

internal data class UpdateApk(val name: String, val abi: String, val size: Long, val sha256: String)
internal data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apks: List<UpdateApk>,
    val variant: UpdateVariant = UpdateVariant.FULL,
) {
    fun select(supportedAbis: List<String>): UpdateApk? = supportedAbis.firstNotNullOfOrNull { abi ->
        apks.firstOrNull { it.abi == abi }
    } ?: apks.firstOrNull { it.abi == "universal" }

    companion object {
        fun parse(text: String): UpdateManifest {
            val json = JSONObject(text)
            val variant = when (json.getInt("schemaVersion")) {
                1 -> { require(!json.has("packaging") || json.getString("packaging") == "full"); UpdateVariant.FULL }
                2 -> requireNotNull(UpdateVariant.fromId(json.getString("packaging")))
                else -> error("Unknown update manifest schema")
            }
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
            return UpdateManifest(code, name, minSdk, apks, variant)
        }
    }
}

internal fun updateCheckDue(now: Long, lastAttempt: Long): Boolean =
    lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= UPDATE_INTERVAL_MS

/** Every initial asset must belong to the exact release and filename. */
internal fun trustedReleaseAsset(url: String, tag: String? = null, name: String? = null): Boolean = runCatching {
    val uri = java.net.URI(url)
    val match = Regex("/$UPDATE_REPOSITORY/releases/download/(v[A-Za-z0-9._+-]{1,100})/([A-Za-z0-9._-]{1,200})")
        .matchEntire(uri.rawPath)
    uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
        match != null && (tag == null || match.groupValues[1] == tag) &&
        (name == null || match.groupValues[2] == name) && uri.query == null && uri.fragment == null
}.getOrDefault(false)

internal fun trustedUpdateRedirect(initial: HttpUrl, next: HttpUrl): Boolean =
    next.isHttps && next.port == 443 && next.username.isEmpty() && next.password.isEmpty() &&
        next.fragment == null && when (initial.host) {
            "api.github.com" -> next == initial
            "github.com" -> when (next.host) {
                "github.com" -> next == initial
                "release-assets.githubusercontent.com", "objects.githubusercontent.com",
                "github-releases.githubusercontent.com" -> true
                else -> false
            }
            else -> false
        }

/** Only a missing variant permits searching older releases; malformed or
 * duplicate assets in a matching release stop discovery. Never mix releases.
 */
internal object UpdateReleaseDiscovery {
    private const val PAGE_SIZE = 10
    private const val MAX_PAGES = 10
    const val API = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases"

    fun manifestUrl(release: JSONObject, variant: UpdateVariant): String? {
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        if (!tag.matches(Regex("v[A-Za-z0-9._+-]{1,100}"))) return null
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == variant.manifestAsset }
        if (matches.isEmpty()) return null
        require(matches.size == 1)
        return matches.single().getString("browser_download_url").also {
            require(trustedReleaseAsset(it, tag, variant.manifestAsset))
        }
    }

    data class Candidate(val release: String, val manifestUrl: String)

    fun find(variant: UpdateVariant, fetch: (String) -> String, latest: String?): Candidate? {
        if (latest != null) manifestUrl(JSONObject(latest), variant)?.let { return Candidate(latest, it) }
        val seen = mutableSetOf<String>()
        latest?.let { seen.add(JSONObject(it).getString("tag_name")) }
        for (page in 1..MAX_PAGES) {
            val rows = JSONArray(fetch("$API?per_page=$PAGE_SIZE&page=$page"))
            require(rows.length() <= PAGE_SIZE)
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                if (!seen.add(row.getString("tag_name"))) continue
                manifestUrl(row, variant)?.let { return Candidate(row.toString(), it) }
            }
            if (rows.length() < PAGE_SIZE) return null
        }
        return null
    }
}

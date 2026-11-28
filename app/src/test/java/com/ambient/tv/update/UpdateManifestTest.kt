package com.ambient.tv.update

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class UpdateManifestTest {
    private val json = """{
      "schemaVersion":1,"packageName":"com.ambient.tv","versionCode":300,"versionName":"1.0.300-beta","minSdk":29,
      "apks":[
        {"name":"arm.apk","abi":"armeabi-v7a","size":500,"sha256":"${"a".repeat(64)}"},
        {"name":"arm64.apk","abi":"arm64-v8a","size":600,"sha256":"${"b".repeat(64)}"},
        {"name":"all.apk","abi":"universal","size":700,"sha256":"${"c".repeat(64)}"}
      ]
    }"""

    @Test fun prefersDeviceAbiOrderAndUsesUniversalOnlyAsFallback() {
        val manifest = UpdateManifest.parse(json)
        assertEquals("arm64.apk", manifest.select(listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("arm.apk", manifest.select(listOf("armeabi-v7a"))?.name)
        assertEquals("all.apk", manifest.select(listOf("x86_64"))?.name)
        assertNull(manifest.copy(apks = manifest.apks.filter { it.abi != "universal" }).select(listOf("x86")))
    }

    @Test fun rejectsForeignPackagesInvalidChecksumsAndDuplicateArchitectures() {
        listOf(
            json.replace("com.ambient.tv", "another.package"),
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            json.replace("a".repeat(64), "bad-hash"),
            json.replace("arm.apk", "../arm.apk"),
            json.replace("armeabi-v7a", "arm64-v8a"),
            json.replace("\"size\":500", "\"size\":0"),
        ).forEach { assertTrue(runCatching { UpdateManifest.parse(it) }.isFailure) }
    }

    @Test fun variantManifestAndExplicitSameVersionSwitch() {
        assertEquals(UpdateVariant.FULL, UpdateManifest.parse(json).variant)
        val modular = json.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"packaging\":\"modular\"")
        assertEquals(UpdateVariant.MODULAR, UpdateManifest.parse(modular).variant)
        assertTrue(runCatching { UpdateManifest.parse(modular.replace("modular", "modular-preview")) }.isFailure)
        assertTrue(runCatching { UpdateManifest.parse(modular.replace("\"schemaVersion\":2", "\"schemaVersion\":1")) }.isFailure)
        assertTrue(updateVersionAllowed(300, UpdateVariant.FULL, 300, UpdateVariant.MODULAR))
        assertTrue(updateVersionAllowed(300, UpdateVariant.MODULAR, 300, UpdateVariant.FULL))
        assertFalse(updateVersionAllowed(300, UpdateVariant.FULL, 300, UpdateVariant.FULL))
        assertFalse(updateVersionAllowed(300, UpdateVariant.MODULAR, 299, UpdateVariant.FULL))
        assertFalse(updateVersionAllowed(300, null, 301, UpdateVariant.FULL))
    }

    @Test fun automaticCheckIsDailyAndRecoversFromClockMovingBackwards() {
        assertTrue(updateCheckDue(1000, 0))
        assertFalse(updateCheckDue(2000, 1000))
        assertTrue(updateCheckDue(1000 + UPDATE_INTERVAL_MS, 1000))
        assertTrue(updateCheckDue(500, 1000))
    }

    @Test fun assetsMustBelongToTheReleaseRepository() {
        assertTrue(trustedReleaseAsset("https://github.com/seinist/ambient-tv/releases/download/v1/app.apk"))
        listOf("http://github.com/seinist/ambient-tv/releases/download/v1/app.apk",
            "https://github.com.evil.test/seinist/ambient-tv/releases/download/v1/app.apk",
            "https://github.com/other/repo/releases/download/v1/app.apk",
            "https://user@github.com/seinist/ambient-tv/releases/download/v1/app.apk",
            "https://github.com:444/seinist/ambient-tv/releases/download/v1/app.apk",
        ).forEach { assertFalse(trustedReleaseAsset(it)) }
    }

    @Test fun updateAssetsAreBoundToOneReleaseAndFilename() {
        val valid = "https://github.com/seinist/ambient-tv/releases/download/v1/app.apk"
        assertTrue(trustedReleaseAsset(valid, "v1", "app.apk"))
        assertFalse(trustedReleaseAsset(valid, "v2", "app.apk"))
        assertFalse(trustedReleaseAsset(valid, "v1", "other.apk"))
        assertFalse(trustedReleaseAsset(valid.replace("app.apk", "%61pp.apk")))
        assertFalse(trustedReleaseAsset(valid.replace("v1", "modules-vlc-v1")))
    }

    @Test fun findsModularReleaseWhenLatestOnlyContainsFullAndDoesNotMixAssets() {
        fun release(tag: String, variant: UpdateVariant) = org.json.JSONObject()
            .put("tag_name", tag).put("draft", false).put("prerelease", false)
            .put("assets", org.json.JSONArray().put(org.json.JSONObject()
                .put("name", variant.manifestAsset)
                .put("browser_download_url", "https://github.com/seinist/ambient-tv/releases/download/$tag/${variant.manifestAsset}")))
        val full = release("v3", UpdateVariant.FULL)
        val modular = release("v2", UpdateVariant.MODULAR)
        val candidate = requireNotNull(UpdateReleaseDiscovery.find(UpdateVariant.MODULAR, {
            assertTrue(it.endsWith("page=1"))
            org.json.JSONArray().put(full).put(modular).toString()
        }, full.toString()))
        assertEquals("v2", org.json.JSONObject(candidate.release).getString("tag_name"))
        assertTrue(candidate.manifestUrl.endsWith("/v2/update-modular.json"))
        val bad = release("v4", UpdateVariant.MODULAR)
        bad.getJSONArray("assets").getJSONObject(0).put("browser_download_url", candidate.manifestUrl)
        assertTrue(runCatching {
            UpdateReleaseDiscovery.find(UpdateVariant.MODULAR, { error("must not fall back") }, bad.toString())
        }.isFailure)
    }

    @Test fun updateRedirectsCannotLeaveApprovedGithubOrigins() {
        val initial = okhttp3.HttpUrl.Builder().scheme("https").host("github.com")
            .addPathSegments("seinist/ambient-tv/releases/download/v1/app.apk").build()
        val cdn = okhttp3.HttpUrl.Builder().scheme("https").host("release-assets.githubusercontent.com")
            .addPathSegment("asset").addQueryParameter("token", "example").build()
        assertTrue(trustedUpdateRedirect(initial, cdn))
        assertFalse(trustedUpdateRedirect(initial, cdn.newBuilder().host("evil.example").build()))
        assertFalse(trustedUpdateRedirect(initial, cdn.newBuilder().scheme("http").port(80).build()))
        assertFalse(trustedUpdateRedirect(initial, initial.newBuilder().addPathSegment("other").build()))
    }

    private fun cachedUpdater(code: Long = com.ambient.tv.BuildConfig.VERSION_CODE.toLong() + 1,
                              automatic: Boolean = false, wrongSize: Boolean = false): AppUpdater {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val release = org.json.JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v1").put("body", "Release notes")
        val assets = org.json.JSONArray()
        listOf("arm.apk" to 500, "arm64.apk" to 600, "all.apk" to 700).forEach { (name, size) ->
            assets.put(org.json.JSONObject().put("name", name).put("size", if (wrongSize) 1 else size)
                .put("browser_download_url", "https://github.com/seinist/ambient-tv/releases/download/v1/$name"))
        }
        release.put("assets", assets)
        context.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE).edit().clear()
            .putBoolean("automatic", automatic).putString("release", release.toString())
            .putString("manifest", json.replace("\"versionCode\":300", "\"versionCode\":$code")).commit()
        return AppUpdater(context)
    }

    @Test fun discoverySurvivesProcessRestartWithoutNetwork() {
        val updater = cachedUpdater()
        assertNotNull(updater.state.value.offer)
        assertFalse(updater.state.value.automatic)
        assertFalse(updater.state.value.ready)
        val restored = AppUpdater(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals(updater.state.value.offer, restored.state.value.offer)
    }

    @Test fun installedAndOlderCachedReleasesAreNotOffered() {
        val installed = com.ambient.tv.BuildConfig.VERSION_CODE.toLong()
        assertNull(cachedUpdater(installed).state.value.offer)
        assertNull(cachedUpdater(installed - 1).state.value.offer)
    }

    @Test fun cachedAssetMustStillMatchManifestSize() {
        assertNull(cachedUpdater(wrongSize = true).state.value.offer)
    }

    @Test fun skipIsPersistentButKeepsManualUpdateAvailable() {
        val updater = cachedUpdater(automatic = true)
        updater.skip()
        val restored = AppUpdater(org.robolectric.RuntimeEnvironment.getApplication())
        assertNotNull(restored.state.value.offer)
        assertEquals(restored.state.value.offer!!.manifest.versionCode, restored.state.value.skipped)
        restored.announceOnHome()
        val prefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE)
        assertEquals(0L, prefs.getLong("announced", 0))
    }

    @Test fun homeNotificationIsEmittedOnlyOncePerVersion() {
        val updater = cachedUpdater(automatic = true)
        val store = com.ambient.tv.notifications.AppNotifications.store
        store.clearHistory()
        updater.announceOnHome()
        val first = store.state.value.history.first { it.notice.key.startsWith("app-update:") }
        store.dismiss(first.notice.key)
        AppUpdater(org.robolectric.RuntimeEnvironment.getApplication()).announceOnHome()
        assertTrue(store.state.value.pending.none { it.notice.key == first.notice.key })
    }

}

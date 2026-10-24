package com.ambient.tv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.ambient.tv.BuildConfig
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class UpdateOffer(val manifest: UpdateManifest, val apk: UpdateApk, val url: String, val notes: String) {
    val displayVersion get() = "${manifest.versionName} (${manifest.versionCode})"
}
internal data class UpdateState(
    val automatic: Boolean = false,
    val checking: Boolean = false,
    val checked: Boolean = false,
    val offer: UpdateOffer? = null,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val ready: Boolean = false,
    val preparingInstall: Boolean = false,
    val skipped: Long = 0,
    val error: Int? = null,
)

/** App-owned scope keeps downloads alive across navigation, without retaining an Activity. */
@Singleton
class AppUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()
    private val directory get() = File(context.filesDir, "updates").apply { mkdirs() }
    private val mutable = MutableStateFlow(UpdateState(
        automatic = prefs.getBoolean("automatic", false), skipped = prefs.getLong("skipped", 0),
    ))
    internal val state = mutable.asStateFlow()
    private var work: Job? = null
    @Volatile private var downloadCall: Call? = null

    init {
        // Retain discoveries across process restarts even when the daily network check is not due.
        val cached = runCatching {
            parseOffer(prefs.getString("release", null) ?: return@runCatching null,
                prefs.getString("manifest", null) ?: return@runCatching null)
        }.getOrNull()
        mutable.update { it.copy(offer = cached, ready = cached?.let { offer -> apkFile(offer).isFile } == true) }
    }

    fun onAppStart() {
        if (mutable.value.automatic) check(automatic = true)
    }

    internal fun setAutomatic(value: Boolean) {
        prefs.edit().putBoolean("automatic", value).apply()
        mutable.update { it.copy(automatic = value) }
        if (value) onAppStart()
    }

    internal fun skip() {
        val code = mutable.value.offer?.manifest?.versionCode ?: return
        prefs.edit().putLong("skipped", code).apply()
        mutable.update { it.copy(skipped = code) }
    }

    /** Called only from the visible Home surface, so a long film cannot expire the queued notice. */
    internal fun announceOnHome() {
        val current = mutable.value
        val offer = current.offer ?: return
        val code = offer.manifest.versionCode
        if (!current.automatic || current.skipped == code || prefs.getLong("announced", 0) == code) return
        AppNotifications.post(AppNotice(
            key = "app-update:$code", message = R.string.update_available_notice,
            args = listOf(offer.displayVersion), kind = NoticeKind.SETTINGS, interruptPlayback = false,
        ))
        prefs.edit().putLong("announced", code).apply()
    }

    internal fun check(automatic: Boolean = false) {
        if (work?.isActive == true || mutable.value.preparingInstall) return
        val now = System.currentTimeMillis()
        if (automatic && !updateCheckDue(now, prefs.getLong("last_attempt", 0))) return
        prefs.edit().putLong("last_attempt", now).apply()
        mutable.update { it.copy(checking = true, error = null) }
        work = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val release = fetchJson("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest")
                    val json = JSONObject(release)
                    require(!json.getBoolean("draft") && !json.getBoolean("prerelease"))
                    val assets = json.getJSONArray("assets")
                    val manifestAssets = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .filter { it.optString("name") == "update.json" }
                    if (manifestAssets.size != 1) throw UpdateFailure(R.string.update_manifest_missing)
                    val manifestUrl = manifestAssets.single().getString("browser_download_url")
                    require(trustedReleaseAsset(manifestUrl))
                    val manifest = fetchJson(manifestUrl)
                    Triple(release, manifest, parseOffer(release, manifest))
                }
                prefs.edit().putString("release", result.first).putString("manifest", result.second).apply()
                mutable.update { it.copy(offer = result.third, checked = true,
                    ready = result.third?.let { offer -> apkFile(offer).isFile } == true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!automatic) mutable.update { it.copy(error = errorResource(failure)) }
            } finally {
                mutable.update { it.copy(checking = false) }
            }
        }
    }

    private fun parseOffer(release: String, text: String): UpdateOffer? {
        val json = JSONObject(release)
        require(!json.getBoolean("draft") && !json.getBoolean("prerelease"))
        val manifest = UpdateManifest.parse(text)
        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return null
        if (BuildConfig.APPLICATION_ID != "com.ambient.tv" || manifest.minSdk > Build.VERSION.SDK_INT)
            throw UpdateFailure(R.string.update_incompatible)
        val apk = manifest.select(Build.SUPPORTED_ABIS.toList()) ?: throw UpdateFailure(R.string.update_incompatible)
        val assets = json.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == apk.name }
        require(matches.size == 1)
        val asset = matches.single()
        val url = asset.getString("browser_download_url")
        require(trustedReleaseAsset(url) && asset.getLong("size") == apk.size)
        return UpdateOffer(manifest, apk, url, json.optString("body").take(12_000))
    }

    private fun fetchJson(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AmbientTV/${BuildConfig.VERSION_NAME}").build()
        val call = client.newCall(request).apply { timeout().timeout(45, TimeUnit.SECONDS) }
        call.execute().use { response ->
            if (response.code == 403 || response.code == 429) throw UpdateFailure(R.string.update_rate_limited)
            if (response.code == 404) throw UpdateFailure(R.string.update_manifest_missing)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1_000_000) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            require(bytes.size <= 1_000_000)
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun apkFile(offer: UpdateOffer) = File(directory, "${offer.apk.sha256}.apk")

    internal fun download() {
        if (work?.isActive == true || mutable.value.preparingInstall) return
        val offer = mutable.value.offer ?: return
        mutable.update { it.copy(downloading = true, ready = false, progress = 0, error = null) }
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    directory.listFiles()?.filter { it.extension in setOf("apk", "part") }?.forEach { it.delete() }
                    if (directory.usableSpace < offer.apk.size * 2 + 32_000_000) throw UpdateFailure(R.string.update_no_space)
                    val part = File(directory, "download.part.apk")
                    try {
                        val call = client.newCall(Request.Builder().url(offer.url).build())
                        downloadCall = call
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body
                            val digest = MessageDigest.getInstance("SHA-256")
                            var count = 0L
                            body.byteStream().use { input ->
                                part.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        ensureActive()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        count += read
                                        if (count > offer.apk.size) throw UpdateFailure(R.string.update_invalid_apk)
                                        output.write(buffer, 0, read)
                                        digest.update(buffer, 0, read)
                                        val progress = (count * 100 / offer.apk.size).toInt()
                                        if (progress != mutable.value.progress) mutable.update { it.copy(progress = progress) }
                                    }
                                }
                            }
                            if (count != offer.apk.size || digest.digest().hex() != offer.apk.sha256)
                                throw UpdateFailure(R.string.update_invalid_apk)
                        }
                        validateApk(part, offer)
                        ensureActive()
                        if (!part.renameTo(apkFile(offer))) throw IOException("Cannot store verified APK")
                    } finally {
                        part.delete()
                        downloadCall = null
                    }
                }
                mutable.update { it.copy(ready = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isActive) mutable.update { it.copy(error = errorResource(failure)) }
            } finally {
                mutable.update { it.copy(downloading = false) }
            }
        }
    }

    internal fun cancelDownload() { work?.cancel(); downloadCall?.cancel() }
    internal fun reportInstallError() { mutable.update { it.copy(error = R.string.update_install_failed) } }

    /**
     * Revalidate persisted bytes, then hand them to the system installer in one session together
     * with the APK's baseline profile ("base.dm"), so ART compiles the UI at install time instead
     * of running it interpreted after every update. The system then asks the user to confirm
     * (see [UpdateInstallReceiver]). Returns false when the install could not be started.
     */
    internal suspend fun install(withProfile: Boolean = true): Boolean {
        val offer = mutable.value.offer ?: return false
        if (!mutable.value.ready || mutable.value.preparingInstall) return false
        mutable.update { it.copy(preparingInstall = true, error = null) }
        return try {
            withContext(Dispatchers.IO) {
                val file = apkFile(offer)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                if (file.length() != offer.apk.size || digest.digest().hex() != offer.apk.sha256)
                    throw UpdateFailure(R.string.update_invalid_apk)
                validateApk(file, offer)
                commitSession(file, withProfile)
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(ready = false, error = errorResource(failure)) }
            false
        } finally { mutable.update { it.copy(preparingInstall = false) } }
    }

    /** ART rejected the profile on this device: the same verified APK goes in once more without it. */
    internal fun retryWithoutProfile() {
        scope.launch { if (!install(withProfile = false)) reportInstallError() }
    }

    private fun commitSession(file: File, withProfile: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val metadata = java.io.ByteArrayOutputStream()
                val profiled = withProfile && writeDexMetadata(file, metadata)
                if (profiled) session.openWrite("base.dm", 0, metadata.size().toLong()).use { out ->
                    out.write(metadata.toByteArray())
                    session.fsync(out)
                }
                val result = Intent(context, UpdateInstallReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(UpdateInstallReceiver.EXTRA_PROFILED, profiled)
                // Mutable: the installer adds the status and its confirmation intent.
                val pending = PendingIntent.getBroadcast(context, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            } catch (failure: Exception) {
                runCatching { session.abandon() }
                throw failure
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, offer: UpdateOffer) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateFailure(R.string.update_invalid_apk)
        if (archive.packageName != context.packageName || archive.longVersionCode != offer.manifest.versionCode ||
            archive.versionName != offer.manifest.versionName || archive.longVersionCode <= BuildConfig.VERSION_CODE ||
            archive.applicationInfo?.minSdkVersion != offer.manifest.minSdk)
            throw UpdateFailure(R.string.update_invalid_apk)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            ?: throw UpdateFailure(R.string.update_signature_mismatch)
        val incoming = archive.signingInfo ?: throw UpdateFailure(R.string.update_signature_mismatch)
        val current = installed.apkContentsSigners.toSet()
        val valid = if (installed.hasMultipleSigners() || incoming.hasMultipleSigners())
            current == incoming.apkContentsSigners.toSet()
        else incoming.signingCertificateHistory?.toSet()?.containsAll(current) == true
        if (!valid) throw UpdateFailure(R.string.update_signature_mismatch)
    }
}

private class UpdateFailure(val resource: Int) : Exception()
private fun errorResource(error: Exception): Int = when (error) {
    is UpdateFailure -> error.resource
    is IOException -> R.string.update_network_error
    else -> R.string.update_invalid_apk
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

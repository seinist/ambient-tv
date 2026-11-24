package com.ambient.tv.romm

import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.di.SharedOkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The server answered with an error status. */
open class RommHttpException(val code: Int, message: String) : IOException(message)

/** The sign-in was refused (revoked token, changed password): sign in again in Settings. */
class RommAuthException(code: Int) : RommHttpException(code, "RomM refused the sign-in ($code)")

/** The server is older than a feature needs (for example pairing with a code). */
class RommUnsupportedException(message: String) : IOException(message)

/** Server version and whether it answers at all; no sign-in needed. */
data class RommServerInfo(val version: String)

sealed interface RommDevicePoll {
    data object Pending : RommDevicePoll
    data object SlowDown : RommDevicePoll
    data object Denied : RommDevicePoll
    data object Expired : RommDevicePoll
    data class Approved(val token: String) : RommDevicePoll
}

/**
 * RomM's REST API. Requests go through the shared host reachability, so a RomM server that goes
 * down marks every system on the same machine offline, exactly like the media servers.
 */
@Singleton
class RommClient @Inject constructor(
    preferences: AppPreferences,
    hosts: HostConnectivity,
    @SharedOkHttp connectionPool: ConnectionPool,
    @SharedOkHttp dispatcher: Dispatcher,
) {
    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .dns(com.ambient.tv.network.LanOnlyDns({ preferences.localNetworkOnly }))
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .addInterceptor(hosts.interceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
        .let(com.ambient.tv.network.CredentialRedirectGuard::install)
        .build()

    /** Large catalogs and ROM downloads may take long between bytes on a busy NAS. */
    private val transfers: OkHttpClient = http.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun serverInfo(base: HttpUrl): RommServerInfo = io {
        val body = execute(http, Request.Builder().url(api(base, "heartbeat")).get().build(), auth = null)
        val root = json.parseToJsonElement(body).jsonObject
        val system = (root["SYSTEM"] ?: root["system"]) as? JsonObject
        RommServerInfo(version = (system?.get("VERSION") ?: system?.get("version"))
            ?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    suspend fun me(base: HttpUrl, auth: RommAuth): RommUser = io {
        json.decodeFromString(RommUser.serializer(), execute(http, get(api(base, "users/me")), auth))
    }

    /** RomM 5: the TV shows a code and a QR code, the user approves on a phone. */
    suspend fun startDevicePairing(base: HttpUrl, deviceId: String, deviceName: String, appVersion: String): RommDeviceAuth = io {
        val payload = json.encodeToString(RommDeviceAuthRequest.serializer(), RommDeviceAuthRequest(
            deviceIdentifier = deviceId, name = deviceName.take(255).ifBlank { "Ambient TV" },
            client = "ambient-tv", platform = "android", clientVersion = appVersion.take(50),
            scopes = SCOPES,
        ))
        val request = Request.Builder().url(api(base, "auth/device/init"))
            .post(payload.toRequestBody(JSON)).build()
        val body = try {
            execute(http, request, auth = null)
        } catch (error: RommHttpException) {
            if (error.code == 404 || error.code == 405) throw RommUnsupportedException("Device pairing needs RomM 5")
            throw error
        }
        json.decodeFromString(RommDeviceAuth.serializer(), body)
    }

    suspend fun pollDevicePairing(base: HttpUrl, deviceCode: String): RommDevicePoll = io {
        val payload = json.encodeToString(RommDeviceTokenRequest.serializer(), RommDeviceTokenRequest(deviceCode))
        val request = Request.Builder().url(api(base, "auth/device/token")).post(payload.toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            when {
                response.isSuccessful -> RommDevicePoll.Approved(
                    json.decodeFromString(RommDeviceToken.serializer(), text).accessToken)
                response.code == 400 -> when (runCatching { json.decodeFromString(RommErrorDetail.serializer(), text).detail }.getOrNull()) {
                    "authorization_pending" -> RommDevicePoll.Pending
                    "slow_down" -> RommDevicePoll.SlowDown
                    "access_denied" -> RommDevicePoll.Denied
                    else -> RommDevicePoll.Expired
                }
                response.code == 429 -> RommDevicePoll.SlowDown
                else -> throw RommHttpException(response.code, "Pairing failed (${response.code})")
            }
        }
    }

    /** RomM 4.8+: a pairing code shown with a client token in the web interface. */
    suspend fun exchangePairCode(base: HttpUrl, code: String): String = io {
        val payload = json.encodeToString(RommPairExchangeRequest.serializer(), RommPairExchangeRequest(code.trim()))
        val request = Request.Builder().url(api(base, "client-tokens/exchange")).post(payload.toRequestBody(JSON)).build()
        val body = try {
            execute(http, request, auth = null)
        } catch (error: RommHttpException) {
            if (error.code == 405) throw RommUnsupportedException("Pairing codes need RomM 4.8")
            throw error
        }
        json.decodeFromString(RommPairExchange.serializer(), body).rawToken
    }

    suspend fun platforms(account: RommAccount): List<RommPlatform> = io {
        val body = execute(http, get(api(account.baseUrl, "platforms")), account.auth)
        items(json.parseToJsonElement(body)).mapNotNull {
            runCatching { json.decodeFromJsonElement(RommPlatform.serializer(), it) }.getOrNull()
        }
    }

    /** Every game of one platform, page by page, alphabetical. */
    suspend fun roms(account: RommAccount, platformId: Long): List<RommRom> = io {
        val result = ArrayList<RommRom>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val url = api(account.baseUrl, "roms").newBuilder()
                // platform_ids is the RomM 4.4+ name; older versions read platform_id.
                .addQueryParameter("platform_id", platformId.toString())
                .addQueryParameter("platform_ids", platformId.toString())
                .addQueryParameter("limit", PAGE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("order_by", "name")
                .addQueryParameter("order_dir", "asc")
                .addQueryParameter("with_char_index", "false")
                .addQueryParameter("with_filter_values", "false")
                .addQueryParameter("with_rom_id_index", "false")
                .addQueryParameter("with_provider_metadata", "false")
                .build()
            val root = json.parseToJsonElement(execute(transfers, get(url), account.auth))
            val page = items(root).mapNotNull { runCatching { json.decodeFromJsonElement(RommRom.serializer(), it) }.getOrNull() }
                .filter { it.platformId == 0L || it.platformId == platformId }
            result += page
            val total = (root as? JsonObject)?.get("total")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            // RomM 3 answers with one unpaged list.
            if (root is JsonArray || page.size < PAGE || (total != null && result.size >= total) ||
                result.size >= MAX_ROMS) break
            offset += PAGE
        }
        result
    }

    suspend fun rom(account: RommAccount, romId: Long): RommRom = io {
        json.decodeFromString(RommRom.serializer(), execute(http, get(api(account.baseUrl, "roms/$romId")), account.auth))
    }

    /**
     * The game file for playing (one file, or a ZIP of all its files). Written to [target];
     * [onProgress] gets 0…1 or null while the size is unknown.
     */
    suspend fun downloadRom(account: RommAccount, rom: RommRom, target: File, maxBytes: Long, onProgress: (Float?) -> Unit) = io {
        val url = api(account.baseUrl, "roms/${rom.id}/content").newBuilder()
            .addPathSegment(rom.fsName.ifBlank { "game" })
            .addQueryParameter("purpose", "play")
            .build()
        download(account, url, target, maxBytes, rom.fsSizeBytes.takeIf { !rom.zipped && it > 0 }, onProgress)
    }

    suspend fun firmware(account: RommAccount, platformId: Long): List<RommFirmware> = io {
        val url = api(account.baseUrl, "firmware").newBuilder()
            .addQueryParameter("platform_id", platformId.toString()).build()
        items(json.parseToJsonElement(execute(http, get(url), account.auth))).mapNotNull {
            runCatching { json.decodeFromJsonElement(RommFirmware.serializer(), it) }.getOrNull()
        }.filter { it.platformId == 0L || it.platformId == platformId }
    }

    suspend fun downloadFirmware(account: RommAccount, firmware: RommFirmware, target: File) = io {
        val url = api(account.baseUrl, "firmware/${firmware.id}/content").newBuilder()
            .addPathSegment(firmware.fileName).build()
        download(account, url, target, MAX_FIRMWARE_BYTES, firmware.sizeBytes.takeIf { it > 0 }) { }
    }

    suspend fun states(account: RommAccount, romId: Long): List<RommAsset> = assets(account, "states", romId)

    suspend fun saves(account: RommAccount, romId: Long): List<RommAsset> = assets(account, "saves", romId)

    private suspend fun assets(account: RommAccount, kind: String, romId: Long): List<RommAsset> = io {
        val url = api(account.baseUrl, kind).newBuilder().addQueryParameter("rom_id", romId.toString()).build()
        val root = try {
            json.parseToJsonElement(execute(http, get(url), account.auth))
        } catch (error: RommHttpException) {
            if (error is RommAuthException || (error.code != 404 && error.code != 405)) throw error
            // Older RomM versions list them only in the game's details.
            val detail = json.parseToJsonElement(execute(http, get(api(account.baseUrl, "roms/$romId")), account.auth))
            (detail as? JsonObject)?.get("user_$kind") ?: JsonArray(emptyList())
        }
        items(root).mapNotNull {
            runCatching { json.decodeFromJsonElement(RommAsset.serializer(), it) }.getOrNull()
        }.filter { it.romId == 0L || it.romId == romId }
    }

    /** The bytes of a save, state or screenshot, by the path the server gave for it. */
    suspend fun assetBytes(account: RommAccount, downloadPath: String, maxBytes: Long): ByteArray = io {
        val url = resolve(account.baseUrl, downloadPath) ?: throw IOException("Invalid asset path")
        http.newCall(get(url).withAuth(account.auth)).execute().use { response ->
            check(response, account.auth)
            val length = response.body.contentLength()
            if (length > maxBytes) throw IOException("Asset exceeds the size limit")
            response.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(length.coerceIn(0, 1L shl 20).toInt())
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("Asset exceeds the size limit")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        }
    }

    suspend fun uploadState(account: RommAccount, romId: Long, emulator: String, fileName: String,
                            bytes: ByteArray, screenshot: ByteArray?): RommAsset =
        upload(account, "states", "stateFile", romId, emulator, fileName, bytes, screenshot)

    suspend fun uploadSave(account: RommAccount, romId: Long, emulator: String, fileName: String, bytes: ByteArray): RommAsset =
        upload(account, "saves", "saveFile", romId, emulator, fileName, bytes, null)

    private suspend fun upload(account: RommAccount, kind: String, field: String, romId: Long, emulator: String,
                               fileName: String, bytes: ByteArray, screenshot: ByteArray?): RommAsset = io {
        val url = api(account.baseUrl, kind).newBuilder()
            .addQueryParameter("rom_id", romId.toString())
            .addQueryParameter("emulator", emulator.take(50))
            .build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(field, fileName, bytes.toRequestBody(OCTET))
            .apply {
                if (screenshot != null) addFormDataPart("screenshotFile", fileName.substringBeforeLast('.') + ".jpg",
                    screenshot.toRequestBody(JPEG))
            }
            .build()
        val text = execute(transfers, Request.Builder().url(url).post(body).build(), account.auth)
        json.decodeFromString(RommAsset.serializer(), text)
    }

    suspend fun deleteStates(account: RommAccount, ids: List<Long>) = io {
        if (ids.isEmpty()) return@io
        val payload = json.encodeToString(RommDeleteStates.serializer(), RommDeleteStates(ids))
        try {
            execute(http, Request.Builder().url(api(account.baseUrl, "states/delete"))
                .post(payload.toRequestBody(JSON)).build(), account.auth)
        } catch (error: RommHttpException) {
            // Already gone (deleted on another device or in the web interface) is the wanted result.
            if (error.code != 404) throw error
        }
        Unit
    }

    /** Tells RomM the game was just played: its "last played", which every client shows. */
    suspend fun markPlayed(account: RommAccount, romId: Long) = io {
        val url = api(account.baseUrl, "roms/$romId/props").newBuilder()
            .addQueryParameter("update_last_played", "true").build()
        execute(http, Request.Builder().url(url).put("{}".toRequestBody(JSON)).build(), account.auth)
        Unit
    }

    // Collections (the user's Favorites) ----------------------------------------------------------

    suspend fun collections(account: RommAccount): List<RommCollection> = io {
        items(json.parseToJsonElement(execute(http, get(api(account.baseUrl, "collections")), account.auth)))
            .mapNotNull { collection(it) }
    }

    /** Creates the user's Favorites collection, the way RomM's web interface does. */
    suspend fun createFavorites(account: RommAccount, name: String): RommCollection = io {
        val url = api(account.baseUrl, "collections").newBuilder()
            .addQueryParameter("is_favorite", "true")
            .addQueryParameter("is_public", "false")
            .build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name", name)
            .addFormDataPart("description", "")
            .build()
        collection(json.parseToJsonElement(execute(http, Request.Builder().url(url).post(body).build(), account.auth)))
            ?: throw IOException("RomM did not return the new collection")
    }

    /** Adds or removes games in one of the user's collections, keeping the others in it. */
    suspend fun changeCollection(account: RommAccount, target: RommCollection, add: Collection<Long>,
                                 remove: Collection<Long>): RommCollection = io {
        var current = target
        try {
            if (add.isNotEmpty()) current = collectionRoms(account, current.id, add, adding = true)
            if (remove.isNotEmpty()) current = collectionRoms(account, current.id, remove, adding = false)
            current
        } catch (error: RommHttpException) {
            if (error is RommAuthException || (error.code != 404 && error.code != 405)) throw error
            // Older RomM versions change a collection only as a whole.
            val ids = (current.romIds + add) - remove.toSet()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("rom_ids", ids.joinToString(",", "[", "]"))
                .addFormDataPart("name", current.name)
                .build()
            val text = execute(http, Request.Builder().url(api(account.baseUrl, "collections/${current.id}")).put(body).build(),
                account.auth)
            collection(json.parseToJsonElement(text)) ?: current.copy(romIds = ids)
        }
    }

    private fun collectionRoms(account: RommAccount, id: Long, romIds: Collection<Long>, adding: Boolean): RommCollection {
        val payload = romIds.joinToString(",", "{\"rom_ids\":[", "]}").toRequestBody(JSON)
        val builder = Request.Builder().url(api(account.baseUrl, "collections/$id/roms"))
        val request = if (adding) builder.post(payload).build() else builder.delete(payload).build()
        return collection(json.parseToJsonElement(execute(http, request, account.auth)))
            ?: throw IOException("RomM did not return the collection")
    }

    /** Absolute image address for a path RomM gives ("/assets/romm/resources/…"); images need no sign-in. */
    fun imageUrl(base: HttpUrl, path: String?): String? {
        val value = path?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (value.startsWith("https://") || value.startsWith("http://")) return value
        if (value.startsWith("//")) return "https:$value"
        return resolve(base, value)?.toString()
    }

    private suspend fun download(account: RommAccount, url: HttpUrl, target: File, maxBytes: Long,
                                 expected: Long?, onProgress: (Float?) -> Unit) {
        target.parentFile?.mkdirs()
        try {
            transfers.newCall(get(url).withAuth(account.auth)).execute().use { response ->
                check(response, account.auth)
                if (response.code == 202) throw IOException("RomM is still preparing this file")
                val total = response.body.contentLength().takeIf { it > 0 } ?: expected
                if (total != null && total > maxBytes) throw IOException("File exceeds the available space")
                response.body.byteStream().use { input ->
                    target.outputStream().buffered(256 * 1024).use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        var reported = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > maxBytes) throw IOException("File exceeds the available space")
                            out.write(buffer, 0, count)
                            if (total != null) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != reported) { reported = percent; onProgress((written.toFloat() / total).coerceIn(0f, 1f)) }
                            } else onProgress(null)
                        }
                        if (written == 0L) throw IOException("Empty download")
                        val declared = response.body.contentLength()
                        if (declared > 0 && written != declared) throw IOException("Incomplete download")
                    }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun execute(client: OkHttpClient, request: Request, auth: RommAuth?): String {
        val call = client.newCall(if (auth != null) request.withAuth(auth) else request)
        call.execute().use { response ->
            check(response, auth)
            return response.body.string()
        }
    }

    private fun check(response: Response, auth: RommAuth?) {
        if (response.isSuccessful) return
        if ((response.code == 401 || response.code == 403) && auth != null) throw RommAuthException(response.code)
        val detail = runCatching {
            json.decodeFromString(RommErrorDetail.serializer(), response.peekBody(4096).string()).detail
        }.getOrNull()
        throw RommHttpException(response.code, detail?.take(200) ?: "RomM answered ${response.code}")
    }

    private fun Request.withAuth(auth: RommAuth): Request = newBuilder().header("Authorization", auth.header()).build()

    private fun get(url: HttpUrl) = Request.Builder().url(url).get().header("Accept", "application/json").build()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        private const val PAGE = 500
        private const val MAX_ROMS = 50_000
        const val MAX_FIRMWARE_BYTES = 64L * 1024 * 1024

        /** What Ambient TV reads and writes: the library, firmware, and the user's saves and states. */
        val SCOPES = listOf("me.read", "roms.read", "platforms.read", "assets.read", "assets.write",
            "firmware.read", "roms.user.read", "roms.user.write", "collections.read", "collections.write")

        /** A collection as any RomM version sends it (`rom_ids`, or `roms` before 3.5). */
        internal fun collection(element: JsonElement): RommCollection? {
            val root = element as? JsonObject ?: return null
            fun long(key: String) = (root[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()
            val id = long("id") ?: return null
            val ids = (root["rom_ids"] ?: root["roms"]) as? JsonArray
            return RommCollection(
                id = id,
                name = (root["name"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty(),
                isFavorite = (root["is_favorite"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true",
                userId = long("user_id") ?: 0L,
                ownerUsername = (root["owner_username"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty(),
                romIds = ids.orEmpty().mapNotNullTo(linkedSetOf()) { item ->
                    when (item) {
                        is kotlinx.serialization.json.JsonPrimitive -> item.contentOrNull?.toLongOrNull()
                        is JsonObject -> (item["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        else -> null
                    }
                },
            )
        }

        /** `/api/<path>` below the web interface address (which may itself have a path). */
        fun api(base: HttpUrl, path: String): HttpUrl = base.newBuilder()
            .addPathSegment("api").addPathSegments(path).build()

        /** A server path with query ("/api/states/4/content?timestamp=…") below [base]. */
        fun resolve(base: HttpUrl, pathAndQuery: String): HttpUrl? {
            if (pathAndQuery.startsWith("https://") || pathAndQuery.startsWith("http://")) {
                val absolute = pathAndQuery.toHttpUrlOrNull() ?: return null
                // Only the RomM server itself; a different host never gets the sign-in.
                return absolute.takeIf { it.host == base.host && it.port == base.port }
            }
            val path = pathAndQuery.substringBefore('?').trimStart('/')
            val query = pathAndQuery.substringAfter('?', "").takeIf(String::isNotEmpty)
            if (path.split('/').any { it == ".." }) return null
            return base.newBuilder()
                .addEncodedPathSegments(path)
                .encodedQuery(query)
                .build()
        }

        private fun items(root: JsonElement): List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["items"] as? JsonArray) ?: emptyList()
            else -> emptyList()
        }
    }
}

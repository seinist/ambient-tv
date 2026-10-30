package com.ambient.tv.data.emby

import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.domain.model.EmbyServerProfile
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit

@Singleton
class EmbyAuthRepository @Inject constructor(
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val json: Json,
) {
    private val connectClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
        // PIN bodies and the Connect user token must stay on connect.emby.media.
        .let(com.ambient.tv.network.CredentialRedirectGuard::install)
        .build()

    private val localExchangeClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
        .let(com.ambient.tv.network.CredentialRedirectGuard::install)
        .build()

    private fun connectRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("X-Application", "${com.ambient.tv.branding.AppBrand.name}/${BuildConfig.VERSION_NAME}")

    private fun <T> executeJson(
        client: OkHttpClient,
        request: Request,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = client.newCall(request).execute().use { response ->
        val raw = response.body.string()
        check(response.isSuccessful) { "Emby Connect HTTP ${response.code}" }
        json.decodeFromString(serializer, raw)
    }

    suspend fun identify(): Result<EmbySystemInfo> = withContext(Dispatchers.IO) {
        suspendResult {
            candidateApi(
                preferences.embyHost,
                preferences.embyPort,
                preferences.embyUseHttps,
                preferences.embyBasePath,
            ).getPublicSystemInfo()
        }
    }

    suspend fun createConnectPin(): Result<EmbyConnectPin> = withContext(Dispatchers.IO) {
        suspendResult {
            val body = FormBody.Builder()
                .add("deviceId", preferences.clientIdentifier)
                .build()
            executeJson(
                connectClient,
                connectRequest("https://connect.emby.media/service/pin").post(body).build(),
                EmbyConnectPin.serializer(),
            ).also {
                require(it.pin.isNotBlank()) { "Emby Connect returned no PIN" }
            }
        }
    }

    suspend fun connectPinStatus(pin: EmbyConnectPin): Result<EmbyConnectPin> = withContext(Dispatchers.IO) {
        suspendResult {
            val url = requireNotNull("https://connect.emby.media/service/pin".toHttpUrlOrNull())
                .newBuilder()
                .addQueryParameter("pin", pin.pin)
                .addQueryParameter("deviceId", pin.deviceId.ifBlank { preferences.clientIdentifier })
                .build()
            executeJson(
                connectClient,
                connectRequest(url.toString()).get().build(),
                EmbyConnectPin.serializer(),
            )
        }
    }

    suspend fun completeConnectPin(
        pin: EmbyConnectPin,
        host: String,
        port: Int,
        useHttps: Boolean,
        basePath: String,
        macAddress: String?,
        autoWake: Boolean,
    ): Result<EmbyServerProfile> = withContext(Dispatchers.IO) {
        suspendResult {
            val normalizedHost = NetworkEndpoint.normalizeHost(host)
            val normalizedBasePath = normalizeBasePath(basePath)
            require(normalizedHost.isNotBlank()) { "Emby host is required" }
            if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(normalizedHost)) {
                error("Blocked non-local Emby server while LAN-only mode is enabled")
            }

            val deviceId = pin.deviceId.ifBlank { preferences.clientIdentifier }
            val connectAuth = executeJson(
                connectClient,
                connectRequest("https://connect.emby.media/service/pin/authenticate")
                    .post(
                        FormBody.Builder()
                            .add("deviceId", deviceId)
                            .add("pin", pin.pin)
                            .build(),
                    )
                    .build(),
                EmbyConnectAuth.serializer(),
            )
            require(connectAuth.userId.isNotBlank() && connectAuth.accessToken.isNotBlank()) {
                "Emby Connect authentication did not return a token"
            }

            val serversUrl = requireNotNull("https://connect.emby.media/service/servers".toHttpUrlOrNull())
                .newBuilder()
                .addQueryParameter("userId", connectAuth.userId)
                .build()
            val servers = executeJson(
                connectClient,
                connectRequest(serversUrl.toString())
                    .header("X-Connect-UserToken", connectAuth.accessToken)
                    .get()
                    .build(),
                ListSerializer(EmbyConnectServer.serializer()),
            )

            val api = candidateApi(normalizedHost, port, useHttps, normalizedBasePath)
            val info = api.getPublicSystemInfo()
            fun matchesAddress(value: String?): Boolean {
                val address = value?.toHttpUrlOrNull() ?: return false
                return address.host.equals(normalizedHost, ignoreCase = true)
            }
            val connectServer = servers.firstOrNull { it.systemId == info.id }
                ?: servers.firstOrNull { matchesAddress(it.localAddress) || matchesAddress(it.url) }
                ?: error("This Emby server is not linked to the confirmed Emby Connect account")
            require(connectServer.accessKey.isNotBlank()) { "Emby Connect server access key is missing" }

            val scheme = if (useHttps) "https" else "http"
            val authority = NetworkEndpoint.authorityHost(normalizedHost)
            val root = requireNotNull("$scheme://$authority:$port$normalizedBasePath/".toHttpUrlOrNull())
            val exchangeUrl = root.newBuilder()
                .addPathSegments("Connect/Exchange")
                .addQueryParameter("format", "json")
                .addQueryParameter("ConnectUserId", connectAuth.userId)
                .build()
            val exchange = executeJson(
                localExchangeClient,
                Request.Builder()
                    .url(exchangeUrl)
                    .header("X-MediaBrowser-Token", connectServer.accessKey)
                    .get()
                    .build(),
                EmbyConnectExchange.serializer(),
            )
            require(exchange.localUserId.isNotBlank() && exchange.accessToken.isNotBlank()) {
                "Emby server did not exchange the Connect token"
            }
            val localUser = candidateApi(
                normalizedHost,
                port,
                useHttps,
                normalizedBasePath,
                accessToken = exchange.accessToken,
            ).getConnectUser(exchange.localUserId)

            val existing = preferences.embyServerProfiles.firstOrNull {
                (!info.id.isBlank() && it.serverId == info.id) ||
                    (
                        it.host.equals(normalizedHost, true) &&
                            it.port == port &&
                            it.useHttps == useHttps &&
                            it.basePath == normalizedBasePath
                    )
            }
            val profile = EmbyServerProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = info.serverName.ifBlank { normalizedHost },
                host = normalizedHost,
                port = port,
                useHttps = useHttps,
                basePath = normalizedBasePath,
                serverId = info.id,
                userId = exchange.localUserId,
                username = localUser.name,
                authenticated = true,
                macAddress = macAddress,
                autoWakeEnabled = autoWake,
            )
            currentCoroutineContext().ensureActive()
            preferences.upsertEmbyServerProfile(profile, makeActive = true)
            preferences.setLoginRejected("emby:" + profile.id, false)
            tokenStore.setEmbyTokenForServer(profile.id, exchange.accessToken)
            tokenStore.setEmbyPasswordForServer(profile.id, null)
            preferences.embyEnabled = true
            profile
        }
    }

    suspend fun signIn(
        host: String,
        port: Int,
        useHttps: Boolean,
        basePath: String,
        username: String,
        password: String,
        macAddress: String?,
        autoWake: Boolean,
    ): Result<EmbyServerProfile> = withContext(Dispatchers.IO) {
        suspendResult {
            val normalizedHost = NetworkEndpoint.normalizeHost(host)
            val normalizedBasePath = normalizeBasePath(basePath)
            require(normalizedHost.isNotBlank()) { "Emby host is required" }
            if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(normalizedHost)) {
                error("Blocked non-local Emby server while LAN-only mode is enabled")
            }
            // Authentication is deliberately isolated from the preference-driven application
            // client. A failed login must neither switch the active endpoint nor send the token
            // belonging to the currently active profile to the candidate server.
            val api = candidateApi(normalizedHost, port, useHttps, normalizedBasePath)
            val info = api.getPublicSystemInfo()
            val auth = api.authenticate(EmbyAuthRequest(username.trim(), password))
            val existing = preferences.embyServerProfiles.firstOrNull {
                (!info.id.isBlank() && it.serverId == info.id) ||
                    (
                        it.host.equals(normalizedHost, true) &&
                            it.port == port &&
                            it.useHttps == useHttps &&
                            it.basePath == normalizedBasePath
                    )
            }
            val profile = EmbyServerProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = info.serverName.ifBlank { normalizedHost },
                host = normalizedHost,
                port = port,
                useHttps = useHttps,
                basePath = normalizedBasePath,
                serverId = auth.serverId ?: info.id,
                userId = auth.user.id,
                username = auth.user.name,
                authenticated = true,
                macAddress = macAddress,
                autoWakeEnabled = autoWake,
            )
            currentCoroutineContext().ensureActive()
            preferences.upsertEmbyServerProfile(profile, makeActive = true)
            preferences.setLoginRejected("emby:${profile.id}", false)
            tokenStore.setEmbyTokenForServer(profile.id, auth.accessToken)
            // The access token is all later requests need; the password is not kept (and an
            // older stored one is dropped) so it cannot leak from the device or a backup.
            tokenStore.setEmbyPasswordForServer(profile.id, null)
            preferences.embyEnabled = true
            profile
        }
    }

    private fun candidateApi(
        host: String,
        port: Int,
        useHttps: Boolean,
        basePath: String,
        accessToken: String? = null,
    ): EmbyApiService {
        val normalizedHost = NetworkEndpoint.normalizeHost(host)
        val normalizedPath = normalizeBasePath(basePath)
        val scheme = if (useHttps) "https" else "http"
        val authority = NetworkEndpoint.authorityHost(normalizedHost)
        val baseUrl = "$scheme://$authority:$port$normalizedPath/"
        val authorization = buildString {
            append("MediaBrowser Client=\"${com.ambient.tv.branding.AppBrand.name}\", Device=\"Android TV\", DeviceId=\"")
            append(preferences.clientIdentifier)
            append("\", Version=\"").append(BuildConfig.VERSION_NAME).append('"')
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", authorization)
                        .header("X-Emby-Authorization", authorization)
                        .header("X-Application-Device", com.ambient.tv.network.deviceModelHeader)
                        .apply { if (!accessToken.isNullOrBlank()) header("X-Emby-Token", accessToken) }
                        .build(),
                )
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor)
            // The login body carries the password: a 307/308 to another server is refused.
            .let(com.ambient.tv.network.CredentialRedirectGuard::install)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EmbyApiService::class.java)
    }

    private fun normalizeBasePath(value: String): String =
        com.ambient.tv.network.BasePathNormalizer.normalize(value)
}

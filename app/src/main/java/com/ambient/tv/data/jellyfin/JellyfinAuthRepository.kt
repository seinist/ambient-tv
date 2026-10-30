package com.ambient.tv.data.jellyfin

import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

@Singleton
class JellyfinAuthRepository @Inject constructor(
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val json: Json,
) {
    suspend fun identify(): Result<JellyfinSystemInfo> = withContext(Dispatchers.IO) {
        suspendResult {
            candidateApi(
                preferences.jellyfinHost,
                preferences.jellyfinPort,
                preferences.jellyfinUseHttps,
                preferences.jellyfinBasePath,
            ).getPublicSystemInfo()
        }
    }

    suspend fun quickConnectEnabled(
        host: String,
        port: Int,
        useHttps: Boolean,
        basePath: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        suspendResult {
            val normalizedHost = NetworkEndpoint.normalizeHost(host)
            require(normalizedHost.isNotBlank()) { "Jellyfin host is required" }
            if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(normalizedHost)) {
                error("Blocked non-local Jellyfin server while LAN-only mode is enabled")
            }
            candidateApi(normalizedHost, port, useHttps, normalizeBasePath(basePath)).quickConnectEnabled()
        }
    }

    suspend fun initiateQuickConnect(
        host: String,
        port: Int,
        useHttps: Boolean,
        basePath: String,
    ): Result<JellyfinQuickConnectResult> = withContext(Dispatchers.IO) {
        suspendResult {
            val normalizedHost = NetworkEndpoint.normalizeHost(host)
            require(normalizedHost.isNotBlank()) { "Jellyfin host is required" }
            if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(normalizedHost)) {
                error("Blocked non-local Jellyfin server while LAN-only mode is enabled")
            }
            val api = candidateApi(normalizedHost, port, useHttps, normalizeBasePath(basePath))
            check(api.quickConnectEnabled()) { "Jellyfin Quick Connect is disabled on this server" }
            api.initiateQuickConnect().also {
                require(it.secret.isNotBlank() && it.code.isNotBlank()) { "Jellyfin returned an invalid Quick Connect session" }
            }
        }
    }

    suspend fun quickConnectState(
        host: String,
        port: Int,
        useHttps: Boolean,
        basePath: String,
        secret: String,
    ): Result<JellyfinQuickConnectResult> = withContext(Dispatchers.IO) {
        suspendResult {
            candidateApi(
                NetworkEndpoint.normalizeHost(host),
                port,
                useHttps,
                normalizeBasePath(basePath),
            ).quickConnectState(secret)
        }
    }

    suspend fun completeQuickConnect(
        host: String,
        port: Int,
        useHttps: Boolean,
        basePath: String,
        secret: String,
        macAddress: String?,
        autoWake: Boolean,
    ): Result<JellyfinServerProfile> = withContext(Dispatchers.IO) {
        suspendResult {
            val normalizedHost = NetworkEndpoint.normalizeHost(host)
            val normalizedBasePath = normalizeBasePath(basePath)
            require(normalizedHost.isNotBlank()) { "Jellyfin host is required" }
            if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(normalizedHost)) {
                error("Blocked non-local Jellyfin server while LAN-only mode is enabled")
            }
            val api = candidateApi(normalizedHost, port, useHttps, normalizedBasePath)
            val info = api.getPublicSystemInfo()
            val auth = api.authenticateQuickConnect(JellyfinQuickConnectRequest(secret))
            val existing = preferences.jellyfinServerProfiles.firstOrNull {
                (!info.id.isBlank() && it.serverId == info.id) ||
                    (
                        it.host.equals(normalizedHost, true) &&
                            it.port == port &&
                            it.useHttps == useHttps &&
                            it.basePath == normalizedBasePath
                    )
            }
            val profile = JellyfinServerProfile(
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
            preferences.upsertJellyfinServerProfile(profile, makeActive = true)
            preferences.setLoginRejected("jellyfin:${profile.id}", false)
            tokenStore.setJellyfinTokenForServer(profile.id, auth.accessToken)
            tokenStore.setJellyfinPasswordForServer(profile.id, null)
            preferences.jellyfinEnabled = true
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
    ): Result<JellyfinServerProfile> = withContext(Dispatchers.IO) {
        suspendResult {
            val normalizedHost = NetworkEndpoint.normalizeHost(host)
            val normalizedBasePath = normalizeBasePath(basePath)
            require(normalizedHost.isNotBlank()) { "Jellyfin host is required" }
            if (preferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(normalizedHost)) {
                error("Blocked non-local Jellyfin server while LAN-only mode is enabled")
            }
            // Authentication is deliberately isolated from the preference-driven application
            // client. A failed login must neither switch the active endpoint nor send the token
            // belonging to the currently active profile to the candidate server.
            val api = candidateApi(normalizedHost, port, useHttps, normalizedBasePath)
            val info = api.getPublicSystemInfo()
            val auth = api.authenticate(JellyfinAuthRequest(username.trim(), password))
            val existing = preferences.jellyfinServerProfiles.firstOrNull {
                (!info.id.isBlank() && it.serverId == info.id) ||
                    (
                        it.host.equals(normalizedHost, true) &&
                            it.port == port &&
                            it.useHttps == useHttps &&
                            it.basePath == normalizedBasePath
                    )
            }
            val profile = JellyfinServerProfile(
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
            preferences.upsertJellyfinServerProfile(profile, makeActive = true)
            preferences.setLoginRejected("jellyfin:${profile.id}", false)
            tokenStore.setJellyfinTokenForServer(profile.id, auth.accessToken)
            // The access token is all later requests need; the password is not kept (and an
            // older stored one is dropped) so it cannot leak from the device or a backup.
            tokenStore.setJellyfinPasswordForServer(profile.id, null)
            preferences.jellyfinEnabled = true
            profile
        }
    }

    private fun candidateApi(host: String, port: Int, useHttps: Boolean, basePath: String): JellyfinApiService {
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
            .create(JellyfinApiService::class.java)
    }

    private fun normalizeBasePath(value: String): String =
        com.ambient.tv.network.BasePathNormalizer.normalize(value)
}

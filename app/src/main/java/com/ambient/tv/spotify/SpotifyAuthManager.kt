package com.ambient.tv.spotify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.ui.mobile.isTelevision
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request


data class SpotifyAuthState(
    val authorized: Boolean = false,
    val accountName: String? = null,
    val scopes: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
)

@Singleton
class SpotifyAuthManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val webApi: SpotifyWebApi,
) {
    companion object {
        val REDIRECT_URI: String get() = BuildConfig.SPOTIFY_REDIRECT_URI
        /** HTTPS relay page for the TV QR sign-in; blank when QR sign-in is disabled for this build. */
        val QR_REDIRECT_URI: String get() = BuildConfig.SPOTIFY_QR_REDIRECT_URI
        val REQUIRED_SCOPES: Set<String> = SpotifyAuthorizationPolicy.REQUIRED_SCOPES
        private const val QR_LOGIN_LIFETIME_MS = 15 * 60_000L
        private const val QR_CALLBACK_TIMEOUT_MS = 30_000L
    }

    /** URLs for one TV sign-in session: [webAuthUrl] for the in-app WebView, [qrUrl] for the phone (null if unavailable). */
    data class TvAuthorization(val webAuthUrl: String, val qrUrl: String?)

    private val client get() = webApi.client
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private val sessionLock = Any()
    @Volatile private var sessionGeneration = 0L

    private inline fun <T> inSession(expected: Long, block: () -> T): T = synchronized(sessionLock) {
        if (expected != sessionGeneration) throw CancellationException("Spotify authorization changed")
        block()
    }

    fun isRedirect(uri: Uri): Boolean {
        val expected = Uri.parse(REDIRECT_URI)
        fun normalizedPath(value: String?): String = value.orEmpty().removeSuffix("/")
        return uri.scheme.equals(expected.scheme, ignoreCase = true) &&
            uri.host.equals(expected.host, ignoreCase = true) &&
            uri.port == expected.port && uri.userInfo == null &&
            normalizedPath(uri.path) == normalizedPath(expected.path)
    }

    init {
        // v37 stored a librespot session token in the Web API slot. It had no OAuth scopes
        // or refresh token and must never be treated as a Web API authorization.
        if (!tokenStore.spotifyWebAccessToken.isNullOrBlank() && tokenStore.spotifyWebRefreshToken.isNullOrBlank() && tokenStore.spotifyWebScopes.isNullOrBlank()) {
            tokenStore.spotifyWebAccessToken = null
            tokenStore.spotifyWebAccessTokenExpiresAtMs = 0L
        }
    }

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SpotifyAuthState> = _state.asStateFlow()

    private var qrServer: SpotifyQrLoginServer? = null
    private val _qrAuthorizationCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when a phone completed the TV QR sign-in, so the waiting TV screen can close. */
    val qrAuthorizationCompleted: SharedFlow<Unit> = _qrAuthorizationCompleted.asSharedFlow()

    /** No client ID is built in: every user registers their own Spotify app (see README). */
    private fun effectiveClientId(): String = SpotifyClientIdPolicy.effective(preferences.spotifyClientId)

    private fun loadState(): SpotifyAuthState {
        val scopes = tokenStore.spotifyWebScopes.orEmpty().split(' ').filter(String::isNotBlank).toSet()
        return SpotifyAuthState(
            authorized = SpotifyAuthorizationPolicy.isAuthorized(
                tokenStore.spotifyWebAccessToken,
                tokenStore.spotifyWebRefreshToken,
                tokenStore.spotifyWebScopes,
            ),
            accountName = tokenStore.spotifyAccountName,
            scopes = scopes,
        )
    }

    fun launchAuthorization(context: Context = appContext): Result<Unit> = runCatching {
        _state.value = _state.value.copy(busy = true, error = null)
        if (isTelevision(context)) {
            // TV: no external browser. The sign-in screen offers the QR code for the phone and the
            // in-app WebView; both share one PKCE session.
            val tv = prepareTvAuthorization()
            context.startActivity(
                Intent(context, SpotifyWebAuthActivity::class.java)
                    .putExtra(SpotifyWebAuthActivity.EXTRA_AUTH_URL, tv.webAuthUrl)
                    .putExtra(SpotifyWebAuthActivity.EXTRA_QR_URL, tv.qrUrl)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            val session = newPkceSession(stateSuffix = null, server = null)
            val uri = Uri.parse(authorizeUrl(session, REDIRECT_URI))
            try {
                // The browser sign-in comes back to the app: running playback waits for it.
                com.ambient.tv.ui.player.BackgroundPlaybackGuard.markExternalFlow()
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(
                    Intent(context, SpotifyWebAuthActivity::class.java)
                        .putExtra(SpotifyWebAuthActivity.EXTRA_AUTH_URL, uri.toString())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        // Waiting in another Activity is not a token exchange. Returning with Back must allow retry.
        _state.value = _state.value.copy(busy = false)
    }.onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }

    /**
     * Starts a TV sign-in session. Builds the in-app WebView URL and, when this build has a QR relay
     * page and the TV has a LAN address, a LAN login URL for the phone. The QR `state` carries the
     * TV's address (`nonce~ip~port`) so the relay page knows where to forward the code.
     */
    fun prepareTvAuthorization(): TvAuthorization {
        requireClientId()
        val loginToken = randomUrlSafe(16)
        var qrSession: PkceSession? = null
        val server = QR_REDIRECT_URI.takeIf(String::isNotBlank)?.let {
            SpotifyQrLoginServer.start(appContext, loginToken, object : SpotifyQrLoginServer.Handler {
                override fun authorizeUrl(): String =
                    this@SpotifyAuthManager.authorizeUrl(checkNotNull(qrSession), QR_REDIRECT_URI)
                override fun callback(query: Uri): SpotifyQrLoginServer.Page = qrCallbackPage(query)
                override fun expired(server: SpotifyQrLoginServer) {
                    if (closeQrServer(server)) {
                        _state.value = _state.value.copy(error = appContext.getString(com.ambient.tv.R.string.spotify_qr_expired))
                    }
                }
            }, QR_LOGIN_LIFETIME_MS)
        }
        val session = newPkceSession(stateSuffix = server?.let { "~${it.host}~${it.port}" }, server = server)
        qrSession = session
        _state.value = _state.value.copy(error = null)
        return TvAuthorization(
            webAuthUrl = authorizeUrl(session, REDIRECT_URI),
            qrUrl = server?.loginUrl,
        )
    }

    suspend fun handleRedirect(uri: Uri): Result<Unit> = completeAuthorization(
        redirectUri = REDIRECT_URI,
        redirectMatches = isRedirect(uri),
        state = uri.getQueryParameter("state"),
        code = uri.getQueryParameter("code"),
        oauthError = uri.getQueryParameter("error"),
    )

    private fun qrCallbackPage(query: Uri): SpotifyQrLoginServer.Page {
        // Any LAN device can reach this listener. A request without the current PKCE state is
        // answered here without touching the TV's sign-in state or showing an error on screen.
        val state = query.getQueryParameter("state")
        val expected = tokenStore.spotifyPkceState
        if (state.isNullOrBlank() || expected.isNullOrBlank() ||
            !java.security.MessageDigest.isEqual(state.toByteArray(), expected.toByteArray())) {
            return SpotifyQrLoginServer.Page(
                success = false,
                title = appContext.getString(com.ambient.tv.R.string.spotify_qr_page_failed_title),
                message = appContext.getString(com.ambient.tv.R.string.spotify_auth_state_mismatch),
            )
        }
        // Bounded: the listener thread must not hang on a stalled token exchange.
        val result = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(QR_CALLBACK_TIMEOUT_MS) {
                completeAuthorization(
                    redirectUri = QR_REDIRECT_URI,
                    redirectMatches = true,
                    state = state,
                    code = query.getQueryParameter("code"),
                    oauthError = query.getQueryParameter("error"),
                )
            } ?: Result.failure(IllegalStateException(appContext.getString(com.ambient.tv.R.string.spotify_auth_failed)))
        }
        return if (result.isSuccess) {
            _qrAuthorizationCompleted.tryEmit(Unit)
            SpotifyQrLoginServer.Page(
                success = true,
                title = appContext.getString(com.ambient.tv.R.string.spotify_qr_page_success_title),
                message = appContext.getString(com.ambient.tv.R.string.spotify_qr_page_success_body),
            )
        } else {
            SpotifyQrLoginServer.Page(
                success = false,
                title = appContext.getString(com.ambient.tv.R.string.spotify_qr_page_failed_title),
                message = result.exceptionOrNull()?.message ?: appContext.getString(com.ambient.tv.R.string.spotify_auth_failed),
            )
        }
    }

    private suspend fun completeAuthorization(
        redirectUri: String,
        redirectMatches: Boolean,
        state: String?,
        code: String?,
        oauthError: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val generation = sessionGeneration
        suspendResult {
            require(redirectMatches) { appContext.getString(com.ambient.tv.R.string.spotify_auth_state_mismatch) }
            tokenMutex.withLock {
                val verifier = inSession(generation) {
                    require(!state.isNullOrBlank() && state == tokenStore.spotifyPkceState) {
                        appContext.getString(com.ambient.tv.R.string.spotify_auth_state_mismatch)
                    }
                    require(oauthError.isNullOrBlank()) { appContext.getString(com.ambient.tv.R.string.spotify_auth_cancelled, oauthError) }
                    _state.value = _state.value.copy(busy = true, error = null)
                    tokenStore.spotifyPkceVerifier ?: error(appContext.getString(com.ambient.tv.R.string.spotify_auth_verifier_missing))
                }
                val clientId = inSession(generation) {
                    tokenStore.spotifyPkceClientId?.takeIf(String::isNotBlank) ?: effectiveClientId()
                }
                val authorizationCode = code?.takeIf(String::isNotBlank)
                    ?: error(appContext.getString(com.ambient.tv.R.string.spotify_auth_code_missing))
                exchangeCode(authorizationCode, verifier, clientId, redirectUri, generation)
                inSession(generation) {
                    tokenStore.spotifyPkceVerifier = null
                    tokenStore.spotifyPkceState = null
                    tokenStore.spotifyPkceClientId = null
                    tokenStore.spotifyWebClientId = clientId
                    // Whichever path finished (WebView or phone), the other one is now obsolete.
                    closeQrServer()
                    _state.value = loadState()
                }
            }
            // A temporary profile endpoint failure must not discard a completed authorization.
            suspendResult { refreshProfile() }
            Unit
        }.onFailure {
            inSession(generation) {
                _state.value = loadState().copy(error = it.message ?: appContext.getString(com.ambient.tv.R.string.spotify_auth_failed))
            }
        }
    }

    suspend fun validAccessToken(): String = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val generation = sessionGeneration
            val current = inSession(generation) { tokenStore.spotifyWebAccessToken }
            if (!current.isNullOrBlank() && System.currentTimeMillis() + 60_000L < tokenStore.spotifyWebAccessTokenExpiresAtMs) return@withLock current
            val refresh = inSession(generation) { tokenStore.spotifyWebRefreshToken }
                ?: error(appContext.getString(com.ambient.tv.R.string.spotify_auth_not_signed_in))
            refreshToken(refresh, generation)
            inSession(generation) { tokenStore.spotifyWebAccessToken }
                ?: error(appContext.getString(com.ambient.tv.R.string.spotify_auth_refresh_failed))
        }
    }

    suspend fun forceRefreshAccessToken(): String = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val generation = sessionGeneration
            val refresh = inSession(generation) { tokenStore.spotifyWebRefreshToken }
                ?: error(appContext.getString(com.ambient.tv.R.string.spotify_auth_not_signed_in))
            // A server-side 401 can precede the locally calculated expiry. Force a real refresh.
            inSession(generation) { tokenStore.spotifyWebAccessTokenExpiresAtMs = 0L }
            refreshToken(refresh, generation)
            inSession(generation) { tokenStore.spotifyWebAccessToken }
                ?: error(appContext.getString(com.ambient.tv.R.string.spotify_auth_refresh_failed))
        }
    }

    suspend fun refreshProfile(): SpotifyAccount = withContext(Dispatchers.IO) {
        val generation = sessionGeneration
        var token = validAccessToken()
        val profile = try {
            webApi.profile(token)
        } catch (error: SpotifyApiException) {
            if (error.statusCode != 401) throw error
            token = forceRefreshAccessToken()
            webApi.profile(token)
        }
        currentCoroutineContext().ensureActive()
        inSession(generation) {
            tokenStore.spotifyAccountName = profile.displayName ?: profile.id
            _state.value = loadState()
        }
        profile
    }

    fun reloadState() = synchronized(sessionLock) {
        sessionGeneration++
        _state.value = loadState()
    }

    fun cancelAuthorization() = synchronized(sessionLock) {
        sessionGeneration++
        closeQrServer()
        tokenStore.spotifyPkceVerifier = null
        tokenStore.spotifyPkceState = null
        tokenStore.spotifyPkceClientId = null
        _state.value = loadState()
    }

    fun logout() = synchronized(sessionLock) {
        sessionGeneration++
        closeQrServer()
        tokenStore.clearSpotifyWebAuth()
        _state.value = SpotifyAuthState()
    }

    private suspend fun exchangeCode(code: String, verifier: String, clientId: String, redirectUri: String, generation: Long) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body.string().orEmpty()
            if (!response.isSuccessful) error(appContext.getString(
                com.ambient.tv.R.string.spotify_auth_exchange_failed,
                response.code,
                oauthErrorDescription(raw),
            ))
            currentCoroutineContext().ensureActive()
            inSession(generation) { storeTokenResponse(raw, keepExistingRefresh = false) }
        }
    }

    private suspend fun refreshToken(refreshToken: String, generation: Long) {
        val clientId = inSession(generation) { tokenStore.spotifyWebClientId?.takeIf(String::isNotBlank) ?: effectiveClientId() }
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body.string().orEmpty()
            if (!response.isSuccessful) {
                if (spotifyRefreshFailureEndsSession(response.code, oauthErrorCode(raw))) inSession(generation) { logout() }
                error(appContext.getString(
                    com.ambient.tv.R.string.spotify_auth_token_refresh_failed,
                    response.code,
                    oauthErrorDescription(raw),
                ))
            }
            currentCoroutineContext().ensureActive()
            inSession(generation) { storeTokenResponse(raw, keepExistingRefresh = true) }
        }
    }

    private fun storeTokenResponse(raw: String, keepExistingRefresh: Boolean) {
        val root = json.parseToJsonElement(raw).jsonObject
        val access = root["access_token"]?.jsonPrimitive?.contentOrNull ?: error(appContext.getString(com.ambient.tv.R.string.spotify_auth_access_missing))
        val refresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expires = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        val scopes = root["scope"]?.jsonPrimitive?.contentOrNull ?: tokenStore.spotifyWebScopes ?: SpotifyAuthorizationPolicy.REQUESTED_SCOPES.joinToString(" ")
        tokenStore.spotifyWebAccessToken = access
        if (!refresh.isNullOrBlank()) tokenStore.spotifyWebRefreshToken = refresh
        else if (!keepExistingRefresh) tokenStore.spotifyWebRefreshToken = null
        tokenStore.spotifyWebAccessTokenExpiresAtMs = System.currentTimeMillis() + expires * 1000L
        tokenStore.spotifyWebScopes = scopes
    }

    private fun oauthErrorCode(raw: String): String? = runCatching {
        json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.contentOrNull?.trim()
    }.getOrNull()

    private fun oauthErrorDescription(raw: String): String = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        root["error_description"]?.jsonPrimitive?.contentOrNull
            ?: root["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.trim()?.take(240).takeUnless { it.isNullOrBlank() }
        ?: appContext.getString(com.ambient.tv.R.string.spotify_auth_unknown_error)

    private class PkceSession(val clientId: String, val challenge: String, val state: String)

    private fun requireClientId(): String = effectiveClientId().also {
        require(it.isNotEmpty()) { appContext.getString(com.ambient.tv.R.string.spotify_auth_client_id_missing) }
    }

    /** Stores a fresh verifier/state for the next redirect; replaces any running QR listener with [server]. */
    private fun newPkceSession(stateSuffix: String?, server: SpotifyQrLoginServer?): PkceSession {
        val clientId = requireClientId()
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(32) + stateSuffix.orEmpty()
        synchronized(sessionLock) {
            sessionGeneration++
            closeQrServer()
            qrServer = server
            tokenStore.spotifyPkceVerifier = verifier
            tokenStore.spotifyPkceState = state
            tokenStore.spotifyPkceClientId = clientId
        }
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return PkceSession(clientId, challenge, state)
    }

    private fun authorizeUrl(session: PkceSession, redirectUri: String): String =
        Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", session.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", session.challenge)
            .appendQueryParameter("state", session.state)
            .appendQueryParameter("scope", SpotifyAuthorizationPolicy.REQUESTED_SCOPES.joinToString(" "))
            .appendQueryParameter("show_dialog", "true")
            .build()
            .toString()

    /** Closes the running QR listener; with [only], only if that listener is still the current one. */
    private fun closeQrServer(only: SpotifyQrLoginServer? = null): Boolean = synchronized(sessionLock) {
        val current = qrServer ?: return@synchronized false
        if (only != null && current !== only) return@synchronized false
        current.close()
        qrServer = null
        true
    }

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes).also(SecureRandom()::nextBytes)
        return base64Url(data)
    }

    private fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
}

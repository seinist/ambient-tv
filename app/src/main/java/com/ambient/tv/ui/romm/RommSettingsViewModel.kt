package com.ambient.tv.ui.romm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.romm.RommAccountStore
import com.ambient.tv.romm.RommAuth
import com.ambient.tv.romm.RommClient
import com.ambient.tv.romm.RommDevicePoll
import com.ambient.tv.romm.RommKeys
import com.ambient.tv.romm.RommLibrary
import com.ambient.tv.romm.RommUnsupportedException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject

/** The code and address shown while a phone approves this device in RomM. */
data class RommPairingUi(val code: String, val address: String, val waiting: Boolean = true)

data class RommSettingsUiState(
    val enabled: Boolean = false,
    val url: String = "",
    val signedIn: Boolean = false,
    val username: String = "",
    val serverVersion: String = "",
    val platformCount: Int = 0,
    val gameCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val pairing: RommPairingUi? = null,
)

/** Settings › RomM: address, sign-in (QR pairing, pairing code or token, user name and password). */
@HiltViewModel
class RommSettingsViewModel @Inject constructor(
    private val accounts: RommAccountStore,
    private val client: RommClient,
    private val library: RommLibrary,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutable = MutableStateFlow(RommSettingsUiState())
    val state: StateFlow<RommSettingsUiState> = mutable.asStateFlow()
    private var pairingJob: Job? = null

    init {
        viewModelScope.launch { accounts.changes.collect { load() } }
        viewModelScope.launch { library.state.collect { load() } }
    }

    private fun load() {
        val account = accounts.configuredAccount()
        val catalog = if (accounts.account() != null) library.catalog() else null
        mutable.update { it.copy(
            enabled = accounts.enabled,
            url = it.url.ifBlank { accounts.serverUrl },
            signedIn = account != null,
            username = accounts.username,
            serverVersion = accounts.serverVersion,
            platformCount = library.menuPlatforms().size,
            gameCount = catalog?.roms?.size ?: 0,
        ) }
    }

    fun setEnabled(value: Boolean) {
        accounts.setEnabled(value)
        if (value && accounts.account() != null) refreshLibrary()
    }

    fun setUrl(value: String) {
        mutable.update { it.copy(url = value, error = null, message = null) }
    }

    /** RomM 5: show a code and a QR code; a signed-in phone approves this device. */
    fun startPairing() {
        val base = baseUrl() ?: return
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            busy {
                val version = client.serverInfo(base).version
                val pairing = try {
                    client.startDevicePairing(base, accounts.deviceId, deviceName(), appVersion())
                } catch (error: RommUnsupportedException) {
                    mutable.update { it.copy(error = context.getString(R.string.romm_pairing_unsupported)) }
                    return@busy
                }
                val path = pairing.verificationPathComplete ?: "${pairing.verificationPath}?user_code=${pairing.userCode}"
                val address = RommClient.resolve(base, path)?.toString() ?: base.toString()
                mutable.update { it.copy(pairing = RommPairingUi(pairing.userCode, address)) }
                var interval = pairing.interval.coerceIn(2, 30) * 1000L
                val deadline = System.currentTimeMillis() + pairing.expiresIn.coerceIn(60, 3600) * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval)
                    when (val poll = client.pollDevicePairing(base, pairing.deviceCode)) {
                        RommDevicePoll.Pending -> Unit
                        RommDevicePoll.SlowDown -> interval += 5_000L
                        RommDevicePoll.Denied -> {
                            mutable.update { it.copy(pairing = null, error = context.getString(R.string.romm_pairing_denied)) }
                            return@busy
                        }
                        RommDevicePoll.Expired -> break
                        is RommDevicePoll.Approved -> {
                            mutable.update { it.copy(pairing = null) }
                            complete(base, RommAuth.Token(poll.token), version)
                            return@busy
                        }
                    }
                }
                mutable.update { it.copy(pairing = null, error = context.getString(R.string.romm_pairing_expired)) }
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        mutable.update { it.copy(pairing = null, busy = false) }
    }

    /** A client token ("rmm_…") or the pairing code RomM shows next to a new token (RomM 4.8+). */
    fun signInWithCode(input: String) {
        val base = baseUrl() ?: return
        val value = input.trim()
        if (value.isEmpty()) return
        viewModelScope.launch {
            busy {
                val version = client.serverInfo(base).version
                val token = if (value.startsWith("rmm_")) value else client.exchangePairCode(base, value)
                complete(base, RommAuth.Token(token), version)
            }
        }
    }

    /** User name and password, for any RomM version (sent with every request, kept encrypted). */
    fun signInWithPassword(username: String, password: String) {
        val base = baseUrl() ?: return
        if (username.isBlank() || password.isEmpty()) return
        viewModelScope.launch {
            busy {
                val version = client.serverInfo(base).version
                complete(base, RommAuth.Basic(username.trim(), password), version)
            }
        }
    }

    fun testConnection() {
        val account = accounts.configuredAccount() ?: return
        viewModelScope.launch {
            busy {
                val version = client.serverInfo(account.baseUrl).version
                val user = client.me(account.baseUrl, account.auth)
                mutable.update { it.copy(message = context.getString(R.string.romm_connection_ok,
                    user.username.ifBlank { account.username }, version.ifBlank { "?" })) }
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            busy {
                val catalog = library.refresh()
                mutable.update { it.copy(message = context.getString(R.string.romm_library_loaded,
                    library.menuPlatforms().size, catalog.roms.size)) }
            }
        }
    }

    fun signOut() {
        accounts.configuredAccount()?.let { library.forget(it.serverKey) }
        accounts.signOut()
        mutable.update { it.copy(message = null, error = null) }
    }

    private suspend fun complete(base: HttpUrl, auth: RommAuth, version: String) {
        val user = client.me(base, auth)
        val username = user.username.ifBlank { (auth as? RommAuth.Basic)?.username.orEmpty() }
        accounts.signedIn(base, auth, username, version)
        mutable.update { it.copy(url = base.toString().trimEnd('/'), message = context.getString(R.string.romm_signed_in, username)) }
        runCatching { library.refresh() }
    }

    private fun baseUrl(): HttpUrl? {
        val base = RommKeys.normalizeUrl(mutable.value.url)
        if (base == null) mutable.update { it.copy(error = context.getString(R.string.romm_url_invalid)) }
        else accounts.setServerUrl(base.toString().trimEnd('/'))
        return base
    }

    private suspend fun busy(block: suspend () -> Unit) {
        mutable.update { it.copy(busy = true, error = null, message = null) }
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(pairing = null, error = rommErrorMessage(context, error)) }
        } finally {
            mutable.update { it.copy(busy = false) }
        }
    }

    private fun deviceName(): String =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")?.takeIf(String::isNotBlank)
            ?.let { "Ambient TV ($it)" } ?: "Ambient TV (${android.os.Build.MODEL})"

    private fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}

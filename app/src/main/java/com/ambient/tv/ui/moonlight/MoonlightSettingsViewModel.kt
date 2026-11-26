package com.ambient.tv.ui.moonlight

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.moonlight.MoonlightDiscoveredHost
import com.ambient.tv.moonlight.MoonlightDiscovery
import com.ambient.tv.moonlight.MoonlightHost
import com.ambient.tv.moonlight.MoonlightHostAddress
import com.ambient.tv.moonlight.MoonlightServerInfoClient
import com.ambient.tv.moonlight.MoonlightSystemTransport
import com.ambient.tv.moonlight.MoonlightPairResult
import com.ambient.tv.moonlight.MoonlightApp
import com.ambient.tv.moonlight.MoonlightLibraryStore
import com.ambient.tv.moonlight.MoonlightStreamProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class MoonlightSettingsUiState(
    val enabled: Boolean = false,
    val hosts: List<MoonlightHost> = emptyList(),
    val discovered: List<MoonlightDiscoveredHost> = emptyList(),
    val scanning: Boolean = false,
    val checkingId: String? = null,
    val status: Map<String, String> = emptyMap(),
    val nativeAvailable: Boolean = false,
    val streamProfile: MoonlightStreamProfile = MoonlightStreamProfile(),
    val pairedIds: Set<String> = emptySet(),
    val pairingHostId: String? = null,
    val pairingPin: String? = null,
    val pairingBusy: Boolean = false,
    val resetBusy: Boolean = false,
    val appLists: Map<String, List<MoonlightApp>> = emptyMap(),
    val loadingAppsId: String? = null,
    val error: String? = null,
)

/** Sunshine setup. Pairing and the catalog are real only in the explicit native build. */
@HiltViewModel
class MoonlightSettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val discovery: MoonlightDiscovery,
    private val serverInfo: MoonlightServerInfoClient,
    private val transport: MoonlightSystemTransport,
    private val libraryStore: MoonlightLibraryStore,
    private val catalogRepository: com.ambient.tv.moonlight.MoonlightCatalogRepository,
    private val engine: com.ambient.tv.moonlight.MoonlightStreamEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutable = MutableStateFlow(MoonlightSettingsUiState(
        enabled = preferences.moonlightEnabled,
        hosts = preferences.moonlightHosts,
        nativeAvailable = transport.available,
        streamProfile = preferences.moonlightStreamProfile,
        pairedIds = preferences.moonlightHosts.filter { transport.locallyPaired(it.id) }.map { it.id }.toSet(),
    ))
    val state: StateFlow<MoonlightSettingsUiState> = mutable.asStateFlow()
    private var scanJob: Job? = null
    private var pairJob: Job? = null

    fun updateProfile(next: MoonlightStreamProfile) {
        try {
            val profile = next.validated()
            preferences.moonlightStreamProfile = profile
            mutable.update { it.copy(streamProfile = profile, error = null) }
        } catch (_: IllegalArgumentException) {
            mutable.update { it.copy(error = context.getString(R.string.moonlight_profile_invalid)) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (mutable.value.pairingBusy || mutable.value.resetBusy) return
        preferences.moonlightEnabled = enabled
        mutable.update { it.copy(enabled = enabled, error = null) }
        if (!enabled) scanJob?.cancel()
    }

    fun addHost(address: String, portText: String, name: String = "", mac: String = "") {
        val port = portText.toIntOrNull()
        val canonical = port?.let { MoonlightHostAddress.normalize(address, it) }
        if (canonical == null) {
            mutable.update { it.copy(error = context.getString(R.string.moonlight_invalid_host)) }
            return
        }
        val existing = preferences.moonlightHosts
        if (existing.any { it.host == canonical && it.port == port }) {
            mutable.update { it.copy(error = context.getString(R.string.moonlight_duplicate_host)) }
            return
        }
        if (existing.size >= 16) {
            mutable.update { it.copy(error = context.getString(R.string.moonlight_too_many_hosts)) }
            return
        }
        val result = runCatching {
            MoonlightHost(UUID.randomUUID().toString(), canonical, port, name, mac).validated()
        }
        val profile = result.getOrNull()
        if (profile == null) {
            mutable.update { it.copy(error = context.getString(R.string.moonlight_invalid_host)) }
            return
        }
        preferences.moonlightHosts = existing + profile
        mutable.update { it.copy(hosts = preferences.moonlightHosts, error = null) }
    }

    fun removeHost(id: String) {
        if (mutable.value.pairingBusy && mutable.value.pairingHostId == id) return
        // Removing a host is an explicit trust reset; do not retain its pinned certificate.
        transport.forget(id)
        libraryStore.clearHost(id)
        preferences.moonlightHosts = preferences.moonlightHosts.filterNot { it.id == id }
        mutable.update { it.copy(
            hosts = preferences.moonlightHosts, status = it.status - id,
            pairedIds = it.pairedIds - id, appLists = it.appLists - id, error = null,
        ) }
    }

    fun checkHost(id: String) {
        val host = preferences.moonlightHosts.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            mutable.update { it.copy(checkingId = id, error = null) }
            try {
                val info = serverInfo.probe(host)
                mutable.update { current -> if (current.hosts.none { it.id == id }) current
                    else current.copy(status = current.status + (id to context.getString(R.string.moonlight_probe_ok, info.name)))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { current -> if (current.hosts.none { it.id == id }) current
                    else current.copy(status = current.status + (id to context.getString(R.string.moonlight_probe_failed)))
                }
            } finally {
                mutable.update { it.copy(checkingId = null) }
            }
        }
    }

    /** Display the PIN to enter on Sunshine. It is generated by the upstream protocol. */
    fun startPairing(hostId: String) {
        if (!transport.available || !preferences.moonlightEnabled ||
            mutable.value.resetBusy || pairJob?.isActive == true || transport.locallyPaired(hostId)) return
        if (preferences.moonlightHosts.none { it.id == hostId }) return
        val pin = transport.newPairingPin()
        if (!pin.matches(Regex("[0-9]{4}"))) return
        mutable.update { it.copy(pairingBusy = true, pairingHostId = hostId, pairingPin = pin, error = null) }
        pairJob = viewModelScope.launch {
            try {
                val result = transport.pair(hostId, pin)
                val error = when (result) {
                    is MoonlightPairResult.Paired, MoonlightPairResult.AlreadyPaired -> null
                    MoonlightPairResult.IncorrectPin -> R.string.moonlight_pair_wrong_pin
                    MoonlightPairResult.Busy -> R.string.moonlight_pair_busy
                    MoonlightPairResult.Unavailable -> R.string.moonlight_pair_unavailable
                    MoonlightPairResult.Rejected -> R.string.moonlight_pair_failed
                }
                mutable.update { it.copy(
                    pairedIds = it.hosts.filter { host -> transport.locallyPaired(host.id) }.map { host -> host.id }.toSet(),
                    error = error?.let { res -> context.getString(res) },
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SecurityException) {
                mutable.update { it.copy(error = context.getString(R.string.moonlight_identity_changed)) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = context.getString(R.string.moonlight_pair_failed)) }
            } finally {
                mutable.update { it.copy(pairingBusy = false, pairingHostId = null, pairingPin = null) }
            }
            if (transport.locallyPaired(hostId)) loadApplications(hostId)
        }
    }

    fun cancelPairing() { pairJob?.cancel() }

    fun forgetPairing(hostId: String) {
        if (mutable.value.pairingBusy || mutable.value.resetBusy || mutable.value.loadingAppsId != null) return
        transport.forget(hostId)
        libraryStore.clearHost(hostId)
        mutable.update { it.copy(pairedIds = it.pairedIds - hostId, appLists = it.appLists - hostId,
            status = it.status - hostId, error = null) }
    }

    fun resetClientIdentity() {
        if (mutable.value.pairingBusy || mutable.value.resetBusy || mutable.value.loadingAppsId != null ||
            engine.isBusy()) return
        mutable.update { it.copy(resetBusy = true, error = null) }
        viewModelScope.launch {
            try {
                transport.resetClientIdentity()
                preferences.moonlightHosts.forEach { libraryStore.clearHost(it.id) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.copy(error = context.getString(R.string.moonlight_reset_failed)) }
            } finally {
                mutable.update { it.copy(resetBusy = false, pairedIds = preferences.moonlightHosts
                    .filter { host -> transport.locallyPaired(host.id) }.map { host -> host.id }.toSet(),
                    appLists = emptyMap(), status = emptyMap()) }
            }
        }
    }

    /** The application catalog comes exclusively from pinned mutually authenticated HTTPS. */
    fun loadApplications(hostId: String) {
        if (!transport.available || !transport.locallyPaired(hostId) || mutable.value.loadingAppsId != null || mutable.value.resetBusy) return
        viewModelScope.launch {
            mutable.update { it.copy(loadingAppsId = hostId, error = null) }
            try {
                val host = preferences.moonlightHosts.firstOrNull { it.id == hostId }
                    ?: throw SecurityException("Unknown Moonlight host")
                val catalog = catalogRepository.refresh(host, com.ambient.tv.data.browse.BrowseLoadPriority.VISIBLE, forceCatalog = true)
                if (preferences.moonlightHosts.any { it == host }) mutable.update { it.copy(appLists = it.appLists + (hostId to catalog.apps)) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SecurityException) {
                mutable.update { it.copy(appLists = it.appLists - hostId, error = context.getString(R.string.moonlight_identity_changed)) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = context.getString(R.string.moonlight_apps_failed)) }
            } finally {
                mutable.update { it.copy(loadingAppsId = null) }
            }
        }
    }

    fun discover() {
        if (!preferences.moonlightEnabled || scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(scanning = true, discovered = emptyList(), error = null) }
            try {
                withTimeout(10_000L) {
                    discovery.browse().collect { found ->
                        mutable.update { state ->
                            val fresh = state.discovered.filterNot { it.host == found.host && it.port == found.port }
                            state.copy(discovered = (fresh + found).take(32))
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Expected bounded discovery window.
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { it.copy(error = context.getString(R.string.moonlight_discovery_failed)) }
            } finally {
                mutable.update { it.copy(scanning = false) }
            }
        }
    }
}

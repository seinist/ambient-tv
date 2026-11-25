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
    val error: String? = null,
)

/** Host settings only. No streaming or pairing controls until the native transport is available. */
@HiltViewModel
class MoonlightSettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val discovery: MoonlightDiscovery,
    private val serverInfo: MoonlightServerInfoClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutable = MutableStateFlow(MoonlightSettingsUiState(
        enabled = preferences.moonlightEnabled,
        hosts = preferences.moonlightHosts,
    ))
    val state: StateFlow<MoonlightSettingsUiState> = mutable.asStateFlow()
    private var scanJob: Job? = null

    fun setEnabled(enabled: Boolean) {
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
        preferences.moonlightHosts = preferences.moonlightHosts.filterNot { it.id == id }
        mutable.update { it.copy(hosts = preferences.moonlightHosts, status = it.status - id, error = null) }
    }

    fun checkHost(id: String) {
        val host = preferences.moonlightHosts.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            mutable.update { it.copy(checkingId = id, error = null) }
            try {
                val info = serverInfo.probe(host)
                mutable.update { it.copy(
                    status = it.status + (id to context.getString(R.string.moonlight_probe_ok, info.name)),
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { it.copy(status = it.status + (id to context.getString(R.string.moonlight_probe_failed))) }
            } finally {
                mutable.update { it.copy(checkingId = null) }
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

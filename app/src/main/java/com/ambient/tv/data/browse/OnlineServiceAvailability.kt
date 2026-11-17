package com.ambient.tv.data.browse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber

/** Public internet services only: Plex, local IPTV, NAS and LAN sources are intentionally excluded. */
internal val ONLINE_SERVICE_KEYS = setOf("youtube", "radio", "weather", "telegram", "trailers")
internal enum class InternetReachability { UNKNOWN, AVAILABLE, UNAVAILABLE }

internal data class OnlineServiceSnapshot(
    val internet: InternetReachability = InternetReachability.UNKNOWN,
    val hasNetwork: Boolean = false,
    val reachable: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
) {
    fun unavailableServices(configured: Set<String>): Set<String> {
        val onlineOnly = configured.intersect(ONLINE_SERVICE_KEYS)
        // A successful public API response proves internet access even when the platform's
        // connectivity probe is blocked. Without a route, no such override is possible.
        val internetBlocked = if (internet == InternetReachability.UNAVAILABLE &&
            (!hasNetwork || reachable.isEmpty())) onlineOnly else emptySet()
        return internetBlocked + failed.intersect(onlineOnly)
    }
}

/** Distinguish an unreachable API from auth, rate limits, empty feeds and cancelled requests. */
internal fun onlineServiceRequestFailed(error: Throwable): Boolean {
    var cause: Throwable? = error
    repeat(8) {
        val current = cause ?: return false
        when (current) {
            is CancellationException -> return false
            is HttpException -> return current.code() == 408 || current.code() in 500..599
            is IOException -> return true
        }
        cause = current.cause
    }
    return false
}

/**
 * Observes the default route's validated INTERNET capability, not just Wi-Fi/LAN presence.
 * No speculative requests at startup: each service reports only from its existing visible work.
 * A successful service response overrides a missing Android validation until the network changes.
 */
@Singleton
class OnlineServiceAvailability @Inject constructor(@ApplicationContext context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(OnlineServiceSnapshot())
    internal val state = mutable.asStateFlow()
    private var networkId: String? = null
    private var settleJob: Job? = null
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRead()
        override fun onLost(network: Network) = scheduleNetworkRead()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = scheduleNetworkRead()
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { observeNotices() }
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            .onFailure { Timber.w(it, "Could not observe internet availability") }
        scheduleNetworkRead()
    }

    /** A reply must come from a real request, never a cached catalogue entry. */
    fun succeeded(service: String) {
        if (service !in ONLINE_SERVICE_KEYS) return
        mutable.update { it.copy(reachable = it.reachable + service, failed = it.failed - service) }
    }

    /** Only a failed live request is evidence of service unavailability. */
    fun failed(service: String, error: Throwable) {
        if (service !in ONLINE_SERVICE_KEYS || !onlineServiceRequestFailed(error)) return
        unavailable(service)
    }

    /** Called when the last mirror returned a transient server error or a forecast is unavailable. */
    fun unavailable(service: String) {
        if (service !in ONLINE_SERVICE_KEYS) return
        mutable.update { it.copy(reachable = it.reachable - service, failed = it.failed + service) }
    }

    private fun scheduleNetworkRead() {
        synchronized(this) {
            settleJob?.cancel()
            settleJob = scope.launch {
                // Validation may arrive shortly after onAvailable. Ignore short transport changes.
                delay(1_500)
                val network = connectivity?.activeNetwork
                val capabilities = network?.let { connectivity?.getNetworkCapabilities(it) }
                val reachability = when {
                    network == null -> InternetReachability.UNAVAILABLE
                    capabilities == null -> InternetReachability.UNKNOWN
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> InternetReachability.AVAILABLE
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> InternetReachability.UNAVAILABLE
                    else -> InternetReachability.UNAVAILABLE
                }
                val id = network?.toString()
                synchronized(this@OnlineServiceAvailability) {
                    val previous = mutable.value
                    val changedRoute = networkId != id
                    val restored = previous.internet == InternetReachability.UNAVAILABLE &&
                        reachability == InternetReachability.AVAILABLE
                    val lost = previous.internet == InternetReachability.AVAILABLE &&
                        reachability == InternetReachability.UNAVAILABLE
                    // Successes from the old route/online period cannot mask a new outage.
                    networkId = id
                    mutable.value = if (changedRoute || restored || lost) OnlineServiceSnapshot(
                        internet = reachability, hasNetwork = network != null,
                    ) else previous.copy(internet = reachability, hasNetwork = network != null)
                }
            }
        }
    }

    private suspend fun observeNotices() {
        var internetNotified = false
        var servicesFailed = false
        mutable.collect { snapshot ->
            val internetDown = snapshot.internet == InternetReachability.UNAVAILABLE
            // A physically missing LAN is already reported by ResumeConnectivityMonitor.
            val shouldNotifyInternet = internetDown && snapshot.hasNetwork
            if (shouldNotifyInternet && !internetNotified) {
                AppNotifications.post(AppNotice(
                    key = "online:internet", message = R.string.notice_internet_unavailable,
                    phase = NoticePhase.INFO, kind = NoticeKind.CONNECTION,
                    interruptPlayback = false,
                ))
            } else if (!internetDown && internetNotified) {
                AppNotifications.post(AppNotice(
                    key = "online:internet", message = R.string.notice_internet_restored,
                    phase = NoticePhase.SUCCESS, kind = NoticeKind.CONNECTION,
                    interruptPlayback = false,
                ))
            }
            if (internetDown && !snapshot.hasNetwork) AppNotifications.removeConnectivity("online:internet")
            internetNotified = shouldNotifyInternet

            // One shared message, never one notification per failed row, category or provider.
            val failedProviders = snapshot.failed.isNotEmpty() && !internetDown
            if (failedProviders && !servicesFailed) {
                AppNotifications.post(AppNotice(
                    key = "online:services", message = R.string.notice_online_services_unavailable,
                    systems = snapshot.failed.toList(),
                    phase = NoticePhase.INFO, kind = NoticeKind.CONNECTION,
                    interruptPlayback = false,
                ))
            } else if (!failedProviders && servicesFailed && !internetDown) {
                AppNotifications.post(AppNotice(
                    key = "online:services", message = R.string.notice_online_services_restored,
                    phase = NoticePhase.SUCCESS, kind = NoticeKind.CONNECTION,
                    interruptPlayback = false,
                ))
            } else if (internetDown && servicesFailed) {
                AppNotifications.removeConnectivity("online:services")
            }
            servicesFailed = failedProviders
        }
    }
}

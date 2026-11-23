package com.ambient.tv.data.browse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.failureHttpCode
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/** Public internet services only: Plex, local IPTV, NAS and LAN sources are intentionally excluded. */
internal val ONLINE_SERVICE_KEYS = setOf("youtube", "radio", "weather", "telegram", "trailers", "spotify")
internal enum class InternetReachability { UNKNOWN, AVAILABLE, UNAVAILABLE }

internal data class OnlineServiceSnapshot(
    val internet: InternetReachability = InternetReachability.UNKNOWN,
    val hasNetwork: Boolean = false,
    val reachable: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
) {
    /** A current live reply disproves an older platform snapshot, including a missing default route. */
    val internetUnavailable: Boolean
        get() = internet == InternetReachability.UNAVAILABLE && reachable.isEmpty()

    fun unavailableServices(configured: Set<String>): Set<String> {
        val onlineOnly = configured.intersect(ONLINE_SERVICE_KEYS)
        // Known route loss discards old replies and request tokens. A reply accepted since
        // that observation is fresh evidence, even if Android has not announced recovery yet.
        val internetBlocked = if (internetUnavailable) onlineOnly else emptySet()
        return internetBlocked + failed.intersect(onlineOnly)
    }
}

/**
 * Android's validation probe is not proof of an outage: DNS filters (Pi-hole, AdGuard), Private
 * DNS, VPN routes and some devices (NVIDIA Shield) leave a working route permanently unvalidated.
 * Only a missing route or a captive portal blocks every online service at once; anything else is
 * left to the services' own request results.
 */
internal fun internetReachability(
    hasNetwork: Boolean,
    capabilitiesKnown: Boolean,
    validated: Boolean,
    captivePortal: Boolean,
): InternetReachability = when {
    !hasNetwork -> InternetReachability.UNAVAILABLE
    !capabilitiesKnown -> InternetReachability.UNKNOWN
    validated -> InternetReachability.AVAILABLE
    captivePortal -> InternetReachability.UNAVAILABLE
    else -> InternetReachability.UNKNOWN
}

/** Distinguish an unreachable API from auth, rate limits, empty feeds and cancelled requests. */
internal fun onlineServiceRequestFailed(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    // SmartTube also wraps cancellations and HTTP statuses in generic IO/reflective errors.
    // Inspect the cause chain before treating an IOException wrapper as a transport outage.
    if (causes.any { it is CancellationException }) return false
    failureHttpCode(error)?.let { return it == 408 || it in 500..599 }
    return causes.any { it is IOException }
}

/** An HTTP rejection still proves the API answered; a local sign-in/cache check does not. */
internal fun onlineServiceRequestReached(error: Throwable): Boolean {
    if (generateSequence(error) { it.cause }.take(8).any { it is CancellationException }) return false
    val code = failureHttpCode(error) ?: return false
    return code in 100..499 && code != 408
}

/**
 * Observes the default route independently of local media-server reachability.
 * No speculative requests at startup: each service reports only from its existing visible work.
 * A successful service response overrides a missing Android validation until the network changes.
 */
@Singleton
class OnlineServiceAvailability @Inject constructor(@ApplicationContext context: Context) : DefaultLifecycleObserver {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val reports = OnlineServiceReports()
    internal val state = reports.state
    private var settleJob: Job? = null
    private var networkReadGeneration = 0L
    private var started = false
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRead()
        override fun onLost(network: Network) {
            reports.networkLost(network.toString())
            scheduleNetworkRead()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = scheduleNetworkRead()
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        // Establish the route before the first feed starts. Do not discard early request
        // evidence when the initial callback's settled read arrives later.
        readNetwork()?.let { (id, reachability) -> reports.networkChanged(id, reachability) }
        scope.launch { observeNotices() }
        registerNetworkCallback()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scheduleNetworkRead()
    }

    override fun onStart(owner: LifecycleOwner) {
        // Reconcile after sleep/foreground even if the platform missed a callback. Retry a
        // failed registration here, without adding a polling loop or probing public services.
        registerNetworkCallback()
        scheduleNetworkRead()
    }

    @Synchronized private fun registerNetworkCallback() {
        if (callbackRegistered) return
        val manager = connectivity ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            callbackRegistered = true
        }.onFailure { Timber.w(it, "Could not observe internet availability") }
    }

    /** Capture before existing provider work, after local cache and rate-limit checks. */
    internal fun begin(service: String): OnlineServiceRequest = reports.begin(service)

    /** Report using the original request token so a late reply cannot undo a real route loss. */
    internal fun succeeded(request: OnlineServiceRequest, confirmsInternet: Boolean = true) =
        reports.succeeded(request, confirmsInternet)

    /** Begin inside the actual provider work, after any browse-queue or cache lookup. */
    internal suspend fun <T> request(
        service: String,
        isFailure: (Throwable) -> Boolean = ::onlineServiceRequestFailed,
        block: suspend (OnlineServiceRequest) -> T,
    ): T = reports.request(service, isFailure, block)

    /** Only a failed live request is evidence of service unavailability. */
    internal fun failed(request: OnlineServiceRequest, error: Throwable) = reports.failed(request, error)

    /** Called when the last mirror returned a transient server error or a forecast is unavailable. */
    internal fun unavailable(request: OnlineServiceRequest) = reports.unavailable(request)

    private fun readNetwork(): Pair<String?, InternetReachability>? = runCatching {
        val manager = connectivity ?: return null
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        network?.toString() to internetReachability(
            hasNetwork = network != null,
            capabilitiesKnown = capabilities != null,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        )
    }.onFailure { Timber.w(it, "Could not read internet availability") }.getOrNull()

    private fun scheduleNetworkRead() {
        synchronized(this) {
            settleJob?.cancel()
            val generation = ++networkReadGeneration
            settleJob = scope.launch {
                // Validation may arrive shortly after onAvailable. Ignore short transport changes.
                delay(1_500)
                val (id, reachability) = readNetwork() ?: return@launch
                synchronized(this@OnlineServiceAvailability) {
                    // Cancellation cannot interrupt a platform read; discard its stale result
                    // if a newer network callback arrived while that read was in progress.
                    if (generation == networkReadGeneration) reports.networkChanged(id, reachability)
                }
            }
        }
    }

    private suspend fun observeNotices() {
        var internetNotified = false
        var servicesFailed = false
        state.collect { snapshot ->
            val internetDown = snapshot.internetUnavailable
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

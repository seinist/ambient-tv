package com.ambient.tv.data.browse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Evidence belongs to the route and request that produced it, not to a view's completion order. */
internal data class OnlineServiceRequest(
    val service: String,
    val networkGeneration: Long,
    val started: Long,
)

/** Serializes network observations and service replies so neither can overwrite a newer reply. */
internal class OnlineServiceReports {
    private val mutable = MutableStateFlow(OnlineServiceSnapshot())
    val state = mutable.asStateFlow()
    private var networkObserved = false
    private var networkId: String? = null
    private var networkGeneration = 0L
    private var clock = 0L
    private val lastSuccess = mutableMapOf<String, Long>()

    @Synchronized fun begin(service: String): OnlineServiceRequest =
        OnlineServiceRequest(service, networkGeneration, ++clock)

    /** A request may succeed in part; only an actual reply advances its success barrier. */
    suspend fun <T> request(
        service: String,
        isFailure: (Throwable) -> Boolean = ::onlineServiceRequestFailed,
        block: suspend (OnlineServiceRequest) -> T,
    ): T {
        val request = begin(service)
        return try {
            block(request)
        } catch (error: Exception) {
            failed(request, error, isFailure)
            throw error
        }
    }

    @Synchronized fun succeeded(request: OnlineServiceRequest, confirmsInternet: Boolean = true) {
        if (!current(request)) return
        lastSuccess[request.service] = ++clock
        val previous = mutable.value
        mutable.value = previous.copy(
            // Some native providers can complete from their own cache. That can clear their
            // request error, but cannot disprove an observed Internet/default-route outage.
            reachable = if (confirmsInternet) previous.reachable + request.service else previous.reachable,
            failed = previous.failed - request.service,
        )
    }

    fun failed(
        request: OnlineServiceRequest,
        error: Throwable,
        isFailure: (Throwable) -> Boolean = ::onlineServiceRequestFailed,
    ): Boolean {
        if (onlineServiceRequestReached(error)) {
            succeeded(request)
            return false
        }
        return isFailure(error) && unavailable(request)
    }

    /** True only if this request can still establish an outage in the current online period. */
    @Synchronized fun unavailable(request: OnlineServiceRequest): Boolean {
        if (!current(request) || request.started <= (lastSuccess[request.service] ?: 0L)) return false
        val previous = mutable.value
        mutable.value = previous.copy(
            reachable = previous.reachable - request.service,
            failed = previous.failed + request.service,
        )
        return true
    }

    private fun current(request: OnlineServiceRequest): Boolean =
        request.service in ONLINE_SERVICE_KEYS && request.networkGeneration == networkGeneration

    /** A real default-network loss invalidates replies even when platform reads still return null. */
    @Synchronized fun networkLost(id: String) {
        // A late callback for the outgoing route must not discard the replacement's evidence.
        if (networkId != null && networkId != id) return
        networkGeneration++
        lastSuccess.clear()
        mutable.value = mutable.value.copy(reachable = emptySet())
    }

    @Synchronized fun networkChanged(id: String?, internet: InternetReachability) {
        val previous = mutable.value
        val changedRoute = networkObserved && networkId != id
        val outageChanged = networkObserved &&
            (previous.internet == InternetReachability.UNAVAILABLE) !=
            (internet == InternetReachability.UNAVAILABLE)
        val validated = previous.internet != InternetReachability.AVAILABLE &&
            internet == InternetReachability.AVAILABLE
        // Initial observation is not a replacement route: a feed may already have replied.
        // A missing route and every real outage boundary invalidate old successful replies.
        val reset = changedRoute || outageChanged || (!networkObserved && id == null)
        if (reset || (networkObserved && validated)) {
            networkGeneration++
            lastSuccess.clear()
        }
        networkObserved = true
        networkId = id
        mutable.value = if (reset) OnlineServiceSnapshot(internet = internet, hasNetwork = id != null)
        else previous.copy(
            internet = internet,
            hasNetwork = id != null,
            // Startup DNS/transport failures are stale once this route becomes validated.
            failed = if (validated) emptySet() else previous.failed,
        )
    }
}

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
            if (onlineServiceRequestReached(error)) succeeded(request)
            else if (isFailure(error)) unavailable(request)
            throw error
        }
    }

    @Synchronized fun succeeded(request: OnlineServiceRequest) {
        if (!current(request)) return
        lastSuccess[request.service] = ++clock
        val previous = mutable.value
        mutable.value = previous.copy(
            reachable = previous.reachable + request.service,
            failed = previous.failed - request.service,
        )
    }

    @Synchronized fun unavailable(request: OnlineServiceRequest) {
        if (!current(request) || request.started <= (lastSuccess[request.service] ?: 0L)) return
        val previous = mutable.value
        mutable.value = previous.copy(
            reachable = previous.reachable - request.service,
            failed = previous.failed + request.service,
        )
    }

    private fun current(request: OnlineServiceRequest): Boolean =
        request.service in ONLINE_SERVICE_KEYS && request.networkGeneration == networkGeneration

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
        val reset = changedRoute || outageChanged || id == null
        if (reset || (networkObserved && validated)) {
            networkGeneration++
            lastSuccess.clear()
        }
        networkObserved = true
        networkId = id
        mutable.value = if (reset) OnlineServiceSnapshot(internet = internet, hasNetwork = id != null)
        else previous.copy(
            internet = internet,
            hasNetwork = true,
            // Startup DNS/transport failures are stale once this route becomes validated.
            failed = if (validated) emptySet() else previous.failed,
        )
    }
}

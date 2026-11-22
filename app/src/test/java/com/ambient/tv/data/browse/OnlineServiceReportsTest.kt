package com.ambient.tv.data.browse

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.connectivity.HostHealthStore
import com.ambient.tv.ui.navigation.sidebarOfflineSources
import com.ambient.tv.youtube.IncompleteYouTubeFeedException
import com.ambient.tv.youtube.SmartTubeSignInRequiredException
import com.ambient.tv.youtube.smartTubeServiceRequestFailed
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class OnlineServiceReportsTest {
    @Test fun aSleepingNasDoesNotMarkSmartTubeOffline() {
        val hosts = HostHealthStore()
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        hosts.offline(hosts.begin("nas"))

        val offline = sidebarOfflineSources(setOf("plex", "jellyfin", "files:share")) {
            hosts.current("nas").health
        } + reports.state.value.unavailableServices(setOf("youtube", "weather", "radio"))

        assertEquals(setOf("plex", "jellyfin", "files:share"), offline)
        assertEquals(HostHealth.OFFLINE, hosts.current("nas").health)
        assertFalse("youtube" in offline)
    }

    @Test fun hostReportsCannotContaminatePublicServiceEvidence() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        listOf("plex", "jellyfin", "tvheadend", "files:share", "dlna:server").forEach {
            val request = reports.begin(it)
            reports.unavailable(request)
            reports.succeeded(request)
        }

        assertTrue(reports.state.value.failed.isEmpty())
        assertTrue(reports.state.value.reachable.isEmpty())
        assertTrue(reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
    }

    @Test fun firstRouteObservationPreservesAnAlreadySuccessfulLiveResponse() {
        val reports = OnlineServiceReports()
        reports.succeeded(reports.begin("youtube"))

        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)

        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertTrue(reports.state.value.hasNetwork)
        assertTrue(reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
    }

    @Test fun firstMissingRouteDiscardsEvidenceAndPendingReportsFromStartup() {
        val reports = OnlineServiceReports()
        val pending = reports.begin("youtube")
        reports.succeeded(reports.begin("radio"))

        reports.networkChanged(null, InternetReachability.UNAVAILABLE)
        reports.succeeded(pending)

        assertFalse(reports.state.value.hasNetwork)
        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(ONLINE_SERVICE_KEYS, reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test fun aFreshRadioOrYouTubeReplyDisprovesAStaleMissingRouteButKeepsOtherFailuresScoped() = runBlocking {
        listOf("radio", "youtube").forEach { service ->
            val reports = OnlineServiceReports()
            reports.networkChanged(null, InternetReachability.UNAVAILABLE)
            val other = if (service == "radio") "youtube" else "radio"
            reports.unavailable(reports.begin(other))
            assertTrue(reports.state.value.internetUnavailable)

            reports.request(service) { request -> reports.succeeded(request) }

            assertFalse("The platform snapshot has not changed", reports.state.value.hasNetwork)
            assertFalse(reports.state.value.internetUnavailable)
            assertEquals(setOf(service), reports.state.value.reachable)
            assertEquals(setOf(other), reports.state.value.failed)
            assertEquals(setOf(other), reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
        }
    }

    @Test fun repeatedMissingRouteSnapshotsDoNotEraseAReplyReceivedSinceTheOutageObservation() {
        val reports = OnlineServiceReports()
        reports.networkChanged(null, InternetReachability.UNAVAILABLE)
        val fresh = reports.begin("radio")
        reports.succeeded(fresh)

        repeat(3) { reports.networkChanged(null, InternetReachability.UNAVAILABLE) }
        reports.unavailable(fresh)

        assertFalse(reports.state.value.hasNetwork)
        assertFalse(reports.state.value.internetUnavailable)
        assertEquals(setOf("radio"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
        assertTrue(reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
    }

    @Test fun aCachedTelegramPageClearsOnlyItsFailureWithoutProvingInternetRecovery() {
        val reports = OnlineServiceReports()
        reports.networkChanged(null, InternetReachability.UNAVAILABLE)
        reports.unavailable(reports.begin("telegram"))
        reports.unavailable(reports.begin("weather"))
        val olderPage = reports.begin("telegram")
        val cachedPage = reports.begin("telegram")

        reports.succeeded(cachedPage, confirmsInternet = false)

        assertEquals(setOf("weather"), reports.state.value.failed)
        assertTrue(reports.state.value.reachable.isEmpty())
        assertTrue(reports.state.value.internetUnavailable)
        assertEquals(ONLINE_SERVICE_KEYS, reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
        assertFalse(reports.unavailable(olderPage))
        assertEquals(setOf("weather"), reports.state.value.failed)
        assertTrue(reports.state.value.internetUnavailable)
    }

    @Test fun anExplicitRouteLossRejectsOlderRepliesAndPreservesSpecificFailuresUntilTheSettledRead() {
        val reports = OnlineServiceReports()
        reports.networkChanged("wifi", InternetReachability.UNKNOWN)
        reports.succeeded(reports.begin("radio"))
        reports.unavailable(reports.begin("weather"))
        val oldSuccess = reports.begin("youtube")
        val oldFailure = reports.begin("radio")

        reports.networkLost("wifi")
        reports.succeeded(oldSuccess)
        reports.unavailable(oldFailure)

        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(setOf("weather"), reports.state.value.failed)
        reports.networkChanged(null, InternetReachability.UNAVAILABLE)
        assertTrue(reports.state.value.internetUnavailable)
        assertEquals(ONLINE_SERVICE_KEYS, reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test fun anExplicitLossAfterAMissingRouteSnapshotDiscardsItsFreshRepliesAndPendingTokens() {
        val reports = OnlineServiceReports()
        reports.networkChanged(null, InternetReachability.UNAVAILABLE)
        reports.succeeded(reports.begin("radio"))
        reports.unavailable(reports.begin("weather"))
        val pending = reports.begin("youtube")

        reports.networkLost("wifi")
        reports.succeeded(pending)

        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(setOf("weather"), reports.state.value.failed)
        assertTrue(reports.state.value.internetUnavailable)
        assertEquals(ONLINE_SERVICE_KEYS, reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
        // Actual replies begun after this newer loss remain valid evidence of recovery.
        reports.succeeded(reports.begin("youtube"))
        assertFalse(reports.state.value.internetUnavailable)
        assertEquals(setOf("weather"), reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test fun aLateLossCallbackForTheReplacedRouteCannotEraseNewRouteRepliesOrTokens() {
        val reports = OnlineServiceReports()
        reports.networkChanged("wifi", InternetReachability.UNKNOWN)
        reports.networkChanged("ethernet", InternetReachability.AVAILABLE)
        reports.succeeded(reports.begin("youtube"))
        val replacementRequest = reports.begin("radio")

        reports.networkLost("wifi")
        reports.succeeded(replacementRequest)

        assertEquals(setOf("youtube", "radio"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
        assertFalse(reports.state.value.internetUnavailable)
        assertTrue(reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
    }

    @Test fun partialSuccessPreventsItsLaterFeedFailureFromMarkingTheServiceOffline() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val feed = reports.begin("youtube")

        reports.succeeded(feed)
        reports.unavailable(feed)

        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test fun anotherRequestAlreadyInFlightCannotUndoANewerSuccessfulReply() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val firstFeed = reports.begin("youtube")
        val secondFeed = reports.begin("youtube")

        reports.succeeded(firstFeed)
        reports.unavailable(secondFeed)

        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test fun anOlderFailedRequestCannotUndoANewerSuccessfulReply() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val oldFeed = reports.begin("youtube")

        reports.succeeded(reports.begin("youtube"))
        reports.unavailable(oldFeed)

        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test fun aRequestStartedAfterSuccessCanConfirmANewServiceOutage() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        reports.succeeded(reports.begin("youtube"))

        reports.unavailable(reports.begin("youtube"))

        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(setOf("youtube"), reports.state.value.failed)
        assertEquals(setOf("youtube"), reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test fun aSuccessfulReplyClearsAnEarlierFailureOnTheSameRoute() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        reports.unavailable(reports.begin("youtube"))

        reports.succeeded(reports.begin("youtube"))

        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test fun oneServiceSuccessDoesNotInvalidateAnotherServicesFailure() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val youtube = reports.begin("youtube")

        reports.succeeded(reports.begin("radio"))
        reports.unavailable(youtube)

        assertEquals(setOf("radio"), reports.state.value.reachable)
        assertEquals(setOf("youtube"), reports.state.value.failed)
        assertEquals(setOf("youtube"), reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test fun changingPhysicalRoutesDiscardsOldSuccessesAndIgnoresOldRequests() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val pendingSuccess = reports.begin("youtube")
        val pendingFailure = reports.begin("radio")
        reports.succeeded(reports.begin("weather"))

        reports.networkChanged("wifi", InternetReachability.UNKNOWN)
        reports.succeeded(pendingSuccess)
        reports.unavailable(pendingFailure)

        assertTrue(reports.state.value.reachable.isEmpty())
        assertTrue(reports.state.value.failed.isEmpty())
        assertTrue(reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
        reports.unavailable(reports.begin("youtube"))
        assertEquals(setOf("youtube"), reports.state.value.failed)
    }

    @Test fun losingAnUnvalidatedRouteCannotReuseItsEarlierSuccess() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val pending = reports.begin("youtube")
        reports.succeeded(reports.begin("radio"))

        reports.networkChanged("ethernet", InternetReachability.UNAVAILABLE)
        reports.succeeded(pending)

        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(ONLINE_SERVICE_KEYS, reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test fun unvalidatedRecoveryClearsOutageEvidenceEvenWithoutAndroidValidation() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNAVAILABLE)
        val failedDuringOutage = reports.begin("youtube")
        reports.unavailable(failedDuringOutage)

        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        reports.unavailable(failedDuringOutage)

        assertTrue(reports.state.value.failed.isEmpty())
        assertTrue(reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
    }

    @Test fun validationClearsStartupFailuresWhilePreservingSuccessfulServices() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        reports.unavailable(reports.begin("youtube"))
        reports.succeeded(reports.begin("radio"))

        reports.networkChanged("ethernet", InternetReachability.AVAILABLE)

        assertTrue(reports.state.value.failed.isEmpty())
        assertEquals(setOf("radio"), reports.state.value.reachable)
        assertTrue(reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS).isEmpty())
    }

    @Test fun validationPreventsAnInFlightStartupFailureFromRestoringTheOldOutage() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val startupRequest = reports.begin("youtube")
        reports.unavailable(reports.begin("youtube"))

        reports.networkChanged("ethernet", InternetReachability.AVAILABLE)
        reports.unavailable(startupRequest)

        assertTrue(reports.state.value.failed.isEmpty())
        reports.unavailable(reports.begin("youtube"))
        assertEquals(setOf("youtube"), reports.state.value.failed)
    }

    @Test fun aPartialReplyFollowedByARealTransportErrorStillProvesServiceReachability() = runBlocking {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val incomplete = IncompleteYouTubeFeedException(ConnectException("Last feed page timed out"))

        val result = runCatching {
            reports.request("youtube") { request ->
                reports.succeeded(request)
                throw incomplete
            }
        }

        assertSame(incomplete, result.exceptionOrNull())
        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test(timeout = 5_000) fun overlappingCoroutineFailureCannotUndoANewerReply() = runBlocking {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val outage = ConnectException("Old feed request failed")
        val old = async {
            runCatching {
                reports.request("youtube") {
                    started.complete(Unit)
                    release.await()
                    throw outage
                }
            }
        }
        started.await()

        reports.request("youtube") { request -> reports.succeeded(request) }
        release.complete(Unit)

        assertSame(outage, old.await().exceptionOrNull())
        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test fun aLiveRequestWithoutAnyReplyReportsItsConnectionFailureAndRethrowsIt() = runBlocking {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val outage = ConnectException("Connection refused")

        val result = runCatching { reports.request("youtube") { throw outage } }

        assertSame(outage, result.exceptionOrNull())
        assertEquals(setOf("youtube"), reports.state.value.failed)
        assertTrue(reports.state.value.reachable.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutAddingOfflineEvidence() = runBlocking {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val cancelled = CancellationException("User left this feed")

        val result = runCatching { reports.request("youtube") { throw cancelled } }

        assertSame(cancelled, result.exceptionOrNull())
        assertTrue(reports.state.value.failed.isEmpty())
        assertTrue(reports.state.value.reachable.isEmpty())
    }

    @Test fun smartTubeCredentialRejectionIsNotAConnectionOutage() = runBlocking {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        val rejected = IOException("Feed failed", IOException("invalid_grant"))

        val result = runCatching {
            reports.request("youtube", isFailure = ::smartTubeServiceRequestFailed) { throw rejected }
        }

        assertSame(rejected, result.exceptionOrNull())
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test fun aRealHttpRejectionClearsStaleOfflineEvidenceButStillReachesTheCaller() = runBlocking {
        listOf(401, 403, 429).forEach { status ->
            val reports = OnlineServiceReports()
            reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
            runCatching { reports.request("youtube") { throw ConnectException("Startup DNS unavailable") } }
            assertEquals(setOf("youtube"), reports.state.value.failed)
            val rejected = HttpException(Response.error<Unit>(status, "Rejected".toResponseBody()))
            val incomplete = IncompleteYouTubeFeedException(rejected)

            val result = runCatching {
                reports.request("youtube", isFailure = ::smartTubeServiceRequestFailed) {
                    throw incomplete
                }
            }

            assertSame(incomplete, result.exceptionOrNull())
            assertEquals("HTTP $status", setOf("youtube"), reports.state.value.reachable)
            assertTrue("HTTP $status", reports.state.value.failed.isEmpty())
        }
    }

    @Test fun aLocalCacheReturnDoesNotClearAnUnconfirmedServiceOutage() = runBlocking {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        reports.unavailable(reports.begin("youtube"))

        assertEquals("cached rows", reports.request("youtube") { "cached rows" })

        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(setOf("youtube"), reports.state.value.failed)
    }

    @Test fun aLocalSignInRequirementDoesNotPretendToBeASuccessfulNetworkReply() = runBlocking {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
        reports.unavailable(reports.begin("youtube"))
        val signIn = SmartTubeSignInRequiredException()

        val result = runCatching {
            reports.request("youtube", isFailure = ::smartTubeServiceRequestFailed) { throw signIn }
        }

        assertSame(signIn, result.exceptionOrNull())
        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(setOf("youtube"), reports.state.value.failed)
    }

    @Test fun unchangedNetworkCallbacksPreserveConfirmedServiceOutages() {
        val reports = OnlineServiceReports()
        reports.networkChanged("ethernet", InternetReachability.AVAILABLE)
        reports.unavailable(reports.begin("youtube"))

        reports.networkChanged("ethernet", InternetReachability.AVAILABLE)

        assertEquals(setOf("youtube"), reports.state.value.failed)
        assertEquals(setOf("youtube"), reports.state.value.unavailableServices(ONLINE_SERVICE_KEYS))
    }

    @Test(timeout = 10_000) fun concurrentNetworkReadsCannotOverwriteASuccessfulRequest() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val reports = OnlineServiceReports()
                reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
                val request = reports.begin("youtube")
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val network = workers.submit {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    reports.networkChanged("ethernet", InternetReachability.UNKNOWN)
                }
                val success = workers.submit {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    reports.succeeded(request)
                }
                check(ready.await(2, TimeUnit.SECONDS))
                start.countDown()
                network.get(2, TimeUnit.SECONDS)
                success.get(2, TimeUnit.SECONDS)

                assertEquals(setOf("youtube"), reports.state.value.reachable)
                assertTrue(reports.state.value.failed.isEmpty())
            }
        } finally {
            workers.shutdownNow()
        }
    }
}

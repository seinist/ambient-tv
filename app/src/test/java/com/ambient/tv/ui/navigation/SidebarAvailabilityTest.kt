package com.ambient.tv.ui.navigation

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.connectivity.HostHealthStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SidebarAvailabilityTest {
    @Test fun confirmedOutageMarksAllConfiguredServicesOnTheSameHost() {
        val hosts = HostHealthStore()
        hosts.offline(hosts.begin("nas"))
        val sources = setOf("plex", "jellyfin", "emby", "tvheadend", "files:share", "dlna:library")

        assertEquals(sources, sidebarOfflineSources(sources) { hosts.current("nas").health })
    }

    @Test fun unknownAndAnsweringHostsAreNotReportedOffline() {
        val hosts = HostHealthStore()
        // HTTP responses, including an access rejection, establish reachability, not a login.
        hosts.online("answering")
        assertEquals(emptySet<String>(), sidebarOfflineSources(listOf("unknown", "answering")) {
            hosts.current(it).health
        })
    }

    @Test fun oneNasOutageDoesNotMarkOtherServersOrTheWholeFilesGroup() {
        val health = mapOf(
            "files:asleep" to HostHealth.OFFLINE,
            "files:available" to HostHealth.ONLINE,
            "dlna:unprobed" to HostHealth.UNKNOWN,
        )
        assertEquals(setOf("files:asleep"), sidebarOfflineSources(health.keys) { health.getValue(it) })
    }

    @Test(timeout = 5_000) fun recoveredHostClearsMenuStatusAndRejectsAnOlderFailure() = runBlocking {
        val hosts = HostHealthStore()
        hosts.offline(hosts.begin("nas"))
        val stale = hosts.begin("nas")
        val states = mutableListOf<Set<String>>()
        val job = launch {
            observeSidebarAvailability(hosts.states) {
                sidebarOfflineSources(listOf("plex", "tvheadend")) { hosts.current("nas").health }
            }.take(2).toList(states)
        }
        while (states.isEmpty()) yield()
        hosts.online("nas")
        assertFalse(hosts.offline(stale))
        job.join()
        assertEquals(listOf(setOf("plex", "tvheadend"), emptySet<String>()), states)
    }

    @Test(timeout = 5_000) fun sourceReplacementClearsOldStatusWithoutAnotherHealthEvent() = runBlocking {
        val hosts = HostHealthStore()
        hosts.offline(hosts.begin("files:old"))
        val configured = MutableStateFlow(setOf("files:old"))
        val states = mutableListOf<Set<String>>()
        val job = launch {
            observeSidebarAvailability(hosts.states, configured) {
                sidebarOfflineSources(configured.value) { hosts.current(it).health }
            }.take(2).toList(states)
        }
        while (states.isEmpty()) yield()
        configured.value = setOf("files:new")
        job.join()
        assertEquals(listOf(setOf("files:old"), emptySet<String>()), states)
    }

    @Test(timeout = 5_000) fun loginCompletedBeforeObservationIsShownWithoutAnotherChange() = runBlocking {
        val login = MutableStateFlow(false)
        login.value = true
        assertEquals(listOf(true), observeSidebarAvailability(login) { listOf(login.value) }.first())
    }

    @Test(timeout = 5_000) fun currentConfigurationIsReadEvenWithoutAnInitialPreferenceEvent() = runBlocking {
        assertEquals(listOf(true), observeSidebarAvailability(emptyFlow<Unit>()) { listOf(true) }.first())
    }

    @Test(timeout = 5_000) fun subsequentLoginAndLogoutUpdateVisibility() = runBlocking {
        val login = MutableStateFlow(false)
        val states = mutableListOf<List<Boolean>>()
        val job = launch {
            observeSidebarAvailability(login) { listOf(login.value) }.take(3).toList(states)
        }
        while (states.isEmpty()) yield()
        login.value = true
        while (states.size < 2) yield()
        login.value = false
        job.join()
        assertEquals(listOf(listOf(false), listOf(true), listOf(false)), states)
    }
}

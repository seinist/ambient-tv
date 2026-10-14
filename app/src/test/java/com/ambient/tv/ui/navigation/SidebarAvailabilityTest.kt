package com.ambient.tv.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarAvailabilityTest {
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

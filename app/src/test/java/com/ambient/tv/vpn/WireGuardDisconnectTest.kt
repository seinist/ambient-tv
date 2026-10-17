package com.ambient.tv.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.companion.CompanionConnectionStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class WireGuardDisconnectTest {
    private fun controller(): WireGuardRouteController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AppPreferences(memoryPreferences())
        return WireGuardRouteController(context, preferences,
            TokenStore(memoryPreferences(), preferences), CompanionConnectionStore(context))
    }

    @Test fun disconnectClosesRunningTunnelBeforeReportingIdle() {
        val route = controller()
        route.note(WireGuardRouteState.Up)
        var closed = false
        route.stopRunningTunnel = {
            assertEquals(WireGuardRouteState.Up, route.status.value)
            closed = true
        }
        route.disconnect()
        assertTrue(closed)
        assertEquals(WireGuardRouteState.Idle, route.status.value)
        assertFalse(route.connectionAllowed)
    }

    @Test fun disconnectDuringHandshakeAlsoClosesTunnel() {
        val route = controller()
        route.note(WireGuardRouteState.Connecting)
        var closed = false
        route.stopRunningTunnel = { closed = true }
        route.disconnect()
        assertTrue(closed)
        assertEquals(WireGuardRouteState.Idle, route.status.value)
        assertFalse(route.connectionAllowed)
    }

    @Test fun explicitConnectAllowsStartsAgain() = runBlocking {
        val route = controller()
        route.disconnect()
        route.ensureActive()
        assertTrue(route.connectionAllowed)
    }
}

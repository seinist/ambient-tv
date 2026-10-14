package com.ambient.tv.vpn

sealed interface WireGuardRouteState {
    data object Idle : WireGuardRouteState
    data object Connecting : WireGuardRouteState
    data object Up : WireGuardRouteState
    data class Failed(val message: String) : WireGuardRouteState
}

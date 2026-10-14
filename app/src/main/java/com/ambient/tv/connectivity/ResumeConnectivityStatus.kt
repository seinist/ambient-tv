package com.ambient.tv.connectivity

import com.ambient.tv.ui.common.MediaSystem

sealed interface ResumeConnectivityStatus {
    data object Idle : ResumeConnectivityStatus
    data object NoNetwork : ResumeConnectivityStatus
    data object Checking : ResumeConnectivityStatus
    data class Progress(
        val networkOk: Boolean,
        val vpnLabel: String?,
        val servers: List<ServerProgress>,
    ) : ResumeConnectivityStatus

    data class ServerProgress(
        val system: MediaSystem,
        val phase: ServerPhase,
        val detail: String? = null,
    )

    enum class ServerPhase { SKIPPED, CHECKING, WAKING, ONLINE, OFFLINE, ERROR }
}

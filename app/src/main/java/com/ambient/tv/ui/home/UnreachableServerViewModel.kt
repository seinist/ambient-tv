package com.ambient.tv.ui.home

import androidx.lifecycle.ViewModel
import com.ambient.tv.connectivity.UnreachableServerGate
import com.ambient.tv.connectivity.ConnectionStatusActions
import com.ambient.tv.notifications.AppNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class UnreachableServerViewModel @Inject constructor(
    private val gate: UnreachableServerGate,
    private val connectionActions: ConnectionStatusActions,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
) : ViewModel() {
    val state = gate.state
    val nextAutomaticCheckAtElapsedMs = gate.nextAutomaticCheckAtElapsedMs
    fun retry() = gate.retry()
    fun retrySystem(system: String) = gate.retrySystem(system)
    fun onOpenSystem(system: String) = gate.onOpenSystem(system)

    /** Startup wake without a machine card (connection observer); stops every in-flight server wake. */
    fun cancelStartupWake() = gate.cancelStartupWake()

    /** Same machine key as the wake notification; stop jobs rather than dismissing the card. */
    fun cancelWake(machine: String) {
        connectionActions.cancelMachine(machine)
    }

    fun cancelConnection(notice: AppNotice) = connectionActions.cancel(notice)
    fun retryConnection(notice: AppNotice) = connectionActions.retry(notice)
    fun canRetryConnection(notice: AppNotice): Boolean = connectionActions.canRetry(notice)
}

package com.ambient.tv.ui.home

import androidx.lifecycle.ViewModel
import com.ambient.tv.connectivity.UnreachableServerGate
import com.ambient.tv.ui.common.PlexRefreshBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class UnreachableServerViewModel @Inject constructor(
    private val gate: UnreachableServerGate,
    private val refreshBus: PlexRefreshBus,
) : ViewModel() {
    val state = gate.state
    fun confirm() = gate.confirm()
    fun retry() = gate.retry(refreshBus)
}

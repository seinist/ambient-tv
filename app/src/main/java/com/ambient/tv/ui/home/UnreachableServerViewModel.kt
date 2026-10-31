package com.ambient.tv.ui.home

import androidx.lifecycle.ViewModel
import com.ambient.tv.connectivity.UnreachableServerGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class UnreachableServerViewModel @Inject constructor(
    private val gate: UnreachableServerGate,
) : ViewModel() {
    val state = gate.state
    val nextAutomaticCheckAtElapsedMs = gate.nextAutomaticCheckAtElapsedMs
    fun retry() = gate.retry()
    fun retrySystem(system: String) = gate.retrySystem(system)
}

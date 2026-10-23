package com.ambient.tv.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HostHealth { UNKNOWN, ONLINE, OFFLINE }
data class HostObservation(val health: HostHealth = HostHealth.UNKNOWN, val generation: Long = 0)
data class HostCheck(val machine: String, val generation: Long)

/** A success invalidates every failure that was already in flight for this machine. */
class HostHealthStore {
    private val mutable = MutableStateFlow<Map<String, HostObservation>>(emptyMap())
    val states = mutable.asStateFlow()
    @Synchronized fun begin(machine: String) = HostCheck(machine, current(machine).generation)
    @Synchronized fun current(machine: String) = mutable.value[machine] ?: HostObservation()
    @Synchronized fun online(machine: String) {
        mutable.value = mutable.value + (machine to HostObservation(HostHealth.ONLINE, current(machine).generation + 1))
    }
    @Synchronized fun offline(check: HostCheck): Boolean {
        if (current(check.machine).generation != check.generation) return false
        mutable.value = mutable.value + (check.machine to HostObservation(HostHealth.OFFLINE, check.generation + 1))
        return true
    }
}

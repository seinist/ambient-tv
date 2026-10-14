package com.ambient.tv.ui.server

import androidx.lifecycle.ViewModel
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ServerSelectUiState(
    val servers: List<ServerProfile>,
    val activeServerId: String?,
    val shouldChooseAtStartup: Boolean,
)

@HiltViewModel
class ServerSelectViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val mediaRepository: MediaRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ServerSelectUiState(
            preferences.serverProfiles,
            preferences.activeServerId,
            preferences.chooseServerOnStartup && preferences.serverProfiles.size > 1,
        ),
    )
    val uiState: StateFlow<ServerSelectUiState> = _uiState.asStateFlow()

    fun activate(profile: ServerProfile) {
        if (preferences.activeServerId != profile.id) {
            preferences.activateServer(profile.id)
            mediaRepository.invalidateCaches()
        }
        _uiState.value = _uiState.value.copy(activeServerId = profile.id)
    }
}

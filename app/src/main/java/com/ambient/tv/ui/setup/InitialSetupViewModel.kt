package com.ambient.tv.ui.setup

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeContentCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InitialSetupUiState(
    val youtubeAvailable: Boolean = true,
    val youtubeLoading: Boolean = false,
    val youtubeSignedIn: Boolean = false,
    val youtubeSignInCode: String? = null,
    val youtubeError: String? = null,
    val youtubeErrorRes: Int? = null,
)

@HiltViewModel
class InitialSetupViewModel @Inject constructor(
    private val youtubeEngine: SmartTubeEngine,
    private val youtubeContentCache: YouTubeContentCache,
) : ViewModel() {
    private val _uiState = MutableStateFlow(InitialSetupUiState(youtubeAvailable = youtubeEngine.available))
    val uiState: StateFlow<InitialSetupUiState> = _uiState.asStateFlow()
    private var signInWatchJob: Job? = null
    private var signInRequestJob: Job? = null

    fun requestYouTubeSignIn() {
        if (!youtubeEngine.available || _uiState.value.youtubeSignedIn || _uiState.value.youtubeLoading) return
        signInWatchJob?.cancel()
        signInRequestJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(youtubeLoading = true, youtubeError = null, youtubeErrorRes = null, youtubeSignInCode = null)
            suspendResult { if (youtubeEngine.isSignedIn()) null else youtubeEngine.requestSignInCode() }
                .onSuccess { code ->
                    val signed = suspendResult { youtubeEngine.isSignedIn() }.getOrDefault(false)
                    _uiState.value = _uiState.value.copy(
                        youtubeLoading = false,
                        youtubeSignedIn = signed,
                        youtubeSignInCode = if (signed) null else code,
                        youtubeErrorRes = if (!signed && code == null) R.string.youtube_error_login_start else null,
                    )
                    if (!signed && code != null) watchForYouTubeSignIn()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        youtubeLoading = false,
                        youtubeError = error.message?.takeIf { it.isNotBlank() },
                        youtubeErrorRes = if (error.message.isNullOrBlank()) R.string.youtube_error_login_failed else null,
                    )
                }
        }
    }

    fun cancelYouTubeSignIn() {
        signInRequestJob?.cancel()
        signInWatchJob?.cancel()
        _uiState.value = InitialSetupUiState(youtubeAvailable = youtubeEngine.available)
    }

    private fun watchForYouTubeSignIn() {
        signInWatchJob?.cancel()
        signInWatchJob = viewModelScope.launch {
            repeat(300) {
                delay(1_000)
                if (suspendResult { youtubeEngine.isSignedIn() }.getOrDefault(false)) {
                    youtubeContentCache.clear()
                    _uiState.value = _uiState.value.copy(
                        youtubeSignedIn = true,
                        youtubeSignInCode = null,
                        youtubeError = null,
                        youtubeErrorRes = null,
                        youtubeLoading = false,
                    )
                    return@launch
                }
            }
            _uiState.value = _uiState.value.copy(
                youtubeLoading = false,
                youtubeError = null,
                youtubeErrorRes = R.string.youtube_error_login_expired,
            )
        }
    }

    override fun onCleared() {
        signInWatchJob?.cancel()
        super.onCleared()
    }
}

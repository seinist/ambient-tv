package com.ambient.tv.ui.romm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.romm.RommAccountStore
import com.ambient.tv.romm.RommClient
import com.ambient.tv.romm.RommKeys
import com.ambient.tv.romm.RommLibrary
import com.ambient.tv.romm.RommRom
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RommGameDetailUiState(
    val loading: Boolean = true,
    val game: LibraryGame? = null,
    val rom: RommRom? = null,
    val platform: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

/** A RomM game's page: the device's copy at once, the server's current details when it answers. */
@HiltViewModel
class RommGameDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val romm: RommLibrary,
    private val client: RommClient,
    private val accounts: RommAccountStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val fileId = saved.get<String>("fileId").orEmpty()
    private val mutable = MutableStateFlow(RommGameDetailUiState())
    val state: StateFlow<RommGameDetailUiState> = mutable.asStateFlow()
    private var fresh: RommRom? = null

    init {
        viewModelScope.launch {
            romm.state.collectLatest { reload() }
        }
        refresh()
    }

    private suspend fun reload() {
        val (game, rom, platform) = withContext(Dispatchers.IO) {
            val rom = fresh ?: romm.rom(fileId)
            Triple(romm.game(fileId), rom, rom?.let { romm.platform(it.platformId)?.title }.orEmpty())
        }
        mutable.update { it.copy(loading = false, game = game, rom = rom, platform = platform) }
    }

    /** Current summary and details from the server; the device's copy stays when it does not answer. */
    fun refresh() {
        val romId = RommKeys.parse(fileId)?.second ?: return
        viewModelScope.launch {
            val account = accounts.account() ?: return@launch
            mutable.update { it.copy(busy = true, error = null) }
            try {
                fresh = client.rom(account, romId)
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(error = rommErrorMessage(context, error)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun favorite() {
        val game = mutable.value.game ?: return
        viewModelScope.launch(Dispatchers.IO) { romm.setFavorite(game.fileId, !game.favorite) }
    }

    fun screenshots(): List<String> {
        val account = accounts.account() ?: return emptyList()
        val rom = mutable.value.rom ?: return emptyList()
        return romm.screenshotUrls(account, rom)
    }
}

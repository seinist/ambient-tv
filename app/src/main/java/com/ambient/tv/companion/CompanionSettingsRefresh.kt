package com.ambient.tv.companion

import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeContentCache
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CompanionSettingsRefresh @Inject constructor(
    private val media: MediaRepository,
    private val tvheadend: TvheadendRepository,
    private val youtube: YouTubeContentCache,
    private val engine: SmartTubeEngine,
) {
    suspend fun refresh() {
        media.invalidateCaches()
        tvheadend.invalidateAll()
        youtube.clear()
        // An unavailable provider must not undo an already confirmed, successfully stored import.
        withContext(Dispatchers.IO) { runCatching { engine.applyMetadataLanguage() } }
    }
}

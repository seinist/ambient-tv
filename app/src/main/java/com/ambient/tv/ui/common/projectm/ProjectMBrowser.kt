package com.ambient.tv.ui.common.projectm

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.player.VideoImagePreferencesEntryPoint
import dagger.hilt.android.EntryPointAccessors

val LocalProjectMBrowser = staticCompositionLocalOf<ProjectMBrowser?> { null }

class ProjectMBrowser(
    context: Context,
    initialFilter: String = "all",
) {
    private val repository = ProjectMPresetRepository(context)
    private val favoritesStore = ProjectMFavorites(context)
    private val preferences: AppPreferences = EntryPointAccessors.fromApplication(
        context.applicationContext,
        VideoImagePreferencesEntryPoint::class.java,
    ).preferences()
    var filter by mutableStateOf(
        if ((initialFilter.takeIf { it.isNotBlank() } ?: preferences.projectmPresetCategoryKey) == "favorites") {
            "favorites"
        } else {
            "all"
        },
    )
    private var showPresetNameState by mutableStateOf(preferences.projectmShowPresetName)
    var showPresetName: Boolean
        get() = showPresetNameState
        set(value) {
            showPresetNameState = value
            preferences.projectmShowPresetName = value
        }
    var intervalSeconds by mutableIntStateOf(preferences.projectmPresetIntervalSeconds)
    var transitionSeconds by mutableIntStateOf(preferences.projectmTransitionSeconds)
    var qualityKey by mutableStateOf(preferences.projectmQualityKey)
    var resolutionKey by mutableStateOf(preferences.projectmResolutionKey)
    private var hdr4kAllowedState by mutableStateOf(preferences.projectmHdr4kAllowed)
    var hdr4kAllowed: Boolean
        get() = hdr4kAllowedState
        set(value) {
            hdr4kAllowedState = value
            preferences.projectmHdr4kAllowed = value
        }
    var currentPath by mutableStateOf<String?>(null)
    var locked by mutableStateOf(false)
    var pendingPath by mutableStateOf<String?>(null)
    var pendingSmooth by mutableStateOf(true)
    private val revision = mutableIntStateOf(0)

    fun applyFilter(value: String) {
        filter = if (value == "favorites") "favorites" else "all"
        preferences.projectmPresetCategoryKey = filter
    }

    fun setInterval(seconds: Int) {
        intervalSeconds = seconds.coerceIn(10, 600)
        preferences.projectmPresetIntervalSeconds = intervalSeconds
    }

    fun setTransition(seconds: Int) {
        transitionSeconds = seconds.coerceIn(0, 10)
        preferences.projectmTransitionSeconds = transitionSeconds
    }

    fun setQuality(value: String) {
        qualityKey = value
        preferences.projectmQualityKey = value
    }

    fun setResolution(value: String) {
        resolutionKey = ProjectMRenderResolution.normalize(value)
        preferences.projectmResolutionKey = resolutionKey
    }

    fun presets(): List<ProjectMPreset> {
        revision.intValue
        return if (filter == "favorites") {
            val fav = favoritesStore.all()
            repository.presets.filter { it.assetPath in fav }
        } else repository.presets
    }

    fun isFavorite(path: String): Boolean {
        revision.intValue
        return favoritesStore.contains(path)
    }

    fun toggleFavorite(path: String) {
        favoritesStore.toggle(path)
        revision.intValue++
    }

    fun select(preset: ProjectMPreset, smooth: Boolean = true) {
        currentPath = preset.assetPath
        pendingPath = preset.assetPath
        pendingSmooth = smooth
        locked = true
    }

    fun consumePending(): Pair<ProjectMPreset, Boolean>? {
        val path = pendingPath ?: return null
        val preset = repository.presets.firstOrNull { it.assetPath == path } ?: return null
        pendingPath = null
        return preset to pendingSmooth
    }

    fun presetData(preset: ProjectMPreset): String? = repository.readPreset(preset)

    fun textureDirectory() = repository.textureDirectory()

    fun indexOf(path: String?): Int = presets().indexOfFirst { it.assetPath == path }.takeIf { it >= 0 } ?: 0
}

@Composable
fun rememberProjectMBrowser(categoryKey: String): ProjectMBrowser {
    val context = LocalContext.current
    return remember(categoryKey) { ProjectMBrowser(context, categoryKey) }
}

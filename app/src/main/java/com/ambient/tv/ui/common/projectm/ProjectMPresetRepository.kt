package com.ambient.tv.ui.common.projectm

import android.content.Context
import android.content.SharedPreferences
import java.io.File

data class ProjectMPreset(val assetPath: String) {
    val category: String = assetPath.split('/').let { parts ->
        if (parts.size >= 3 && parts.first() in setOf("cream", "classic", "original")) parts[1] else parts.firstOrNull() ?: "All"
    }
    val displayName: String = assetPath.substringAfterLast('/').removeSuffix(".milk")
}

internal class ProjectMPresetRepository(private val context: Context) {
    private val assetManager get() = context.assets

    val presets: List<ProjectMPreset> by lazy {
        runCatching {
            assetManager.open("projectm/index.txt").bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotBlank() && it.endsWith(".milk", ignoreCase = true) }
                    .map(::ProjectMPreset)
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun filtered(category: String): List<ProjectMPreset> = when {
        category.isBlank() || category.equals("all", true) -> presets
        else -> presets.filter { it.category.equals(category, true) }
    }

    fun readPreset(preset: ProjectMPreset): String? = runCatching {
        assetManager.open("projectm/presets/${preset.assetPath}").bufferedReader().use { it.readText() }
    }.getOrNull()

    fun textureDirectory(): File? {
        val target = File(context.filesDir, "projectm/textures")
        val marker = File(target, ".ready")
        if (marker.isFile) return target
        return runCatching {
            target.mkdirs()
            copyAssetTree("projectm/textures", target)
            marker.writeText("ok")
            target
        }.getOrNull()
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input -> target.outputStream().use(input::copyTo) }
            return
        }
        target.mkdirs()
        children.forEach { name -> copyAssetTree("$assetPath/$name", File(target, name)) }
    }
}

internal class ProjectMFavorites private constructor(
    private val prefs: SharedPreferences,
    loadPresetPaths: () -> Set<String>,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("projectm_visualizer", Context.MODE_PRIVATE),
        { ProjectMPresetRepository(context).presets.map { it.assetPath }.toSet() },
    )

    internal constructor(prefs: SharedPreferences, builtInPaths: Set<String>) : this(prefs, { builtInPaths })

    private val builtInPaths by lazy(loadPresetPaths)

    fun contains(path: String) = path in builtInPaths && path in prefs.getStringSet("favorites", emptySet()).orEmpty()
    fun toggle(path: String): Boolean {
        if (path !in builtInPaths) return false
        val set = all().toMutableSet()
        val nowFavorite = if (path in set) { set.remove(path); false } else { set.add(path); true }
        replace(set)
        return nowFavorite
    }

    /** Only bundled preset identifiers travel in settings; never a local path or external asset. */
    fun all(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().intersect(builtInPaths)

    fun replace(paths: Set<String>) {
        prefs.edit().putStringSet("favorites", paths.intersect(builtInPaths)).apply()
    }
}

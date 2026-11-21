package com.ambient.tv.games.library

import com.ambient.tv.games.GameRomPreparer
import com.ambient.tv.games.GameSystems

/**
 * A .m3u is a playable multi-disc game, not one extra game per listed disc.
 * A .cue/.gdi owns its tracks. This only changes presentation in Games; FileSource
 * identifiers and the underlying files remain untouched.
 */
internal object GameDiscGrouping {
    private val types = setOf("m3u", "cue", "gdi")

    /** Relative, bounded references; malformed descriptors never hide a real game. */
    fun referenced(descriptor: String, text: String): Set<String> {
        val ext = GameSystems.extension(descriptor)
        if (ext !in types || text.length > 256 * 1024) return emptySet()
        val relatives = when (ext) {
            "m3u" -> GameRomPreparer.m3uFiles(text)
            "cue" -> GameRomPreparer.cueFiles(text)
            else -> GameRomPreparer.gdiFiles(text)
        }
        if (relatives.size !in 1..128) return emptySet()
        val parent = descriptor.substringBeforeLast('/', "")
        return relatives.mapNotNull { GameRomPreparer.safeRelativePath(it) }
            .map { if (parent.isBlank()) it else "$parent/$it" }
            .toSet()
    }

    /** A higher-level playlist hides its referenced disc entries in this Games index. */
    fun visiblePaths(paths: Set<String>, references: Map<String, Set<String>>): Set<String> {
        val hidden = hashSetOf<String>()
        // Suppress only references whose parent descriptor is actually in the scan results.
        // A broken missing playlist never deletes independent games from the library.
        references.forEach { (descriptor, children) ->
            // A partial playlist is not playable as a complete multi-disc title.
            // Retain each standalone disc if any referenced disc is missing.
            if (GameSystems.extension(descriptor) == "m3u" && descriptor in paths &&
                children.isNotEmpty() && children.all { it in paths }) {
                hidden += children.filter { it != descriptor }
            }
        }
        return paths - hidden
    }
}

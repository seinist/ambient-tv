package com.ambient.tv.games.metadata

import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileStore
import com.ambient.tv.data.files.GameScanFileSession
import com.ambient.tv.data.files.SeekableMedia
import com.ambient.tv.games.library.GameLibraryRepository
import com.ambient.tv.games.library.LibraryGame
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Network paths must match exactly. SAF may expose opaque IDs for a bare, unique ROM name. */
internal fun gameSidecarFilenameFallback(reference: String, localSaf: Boolean): String? {
    if (!localSaf) return null
    return gameSidecarRelativePath(reference)?.takeIf { '/' !in it }
}

/**
 * Imports sidecars only during an explicit folder scan. One source lease and cached directory
 * listings are reused. Images are copied into app ownership; their originals are never changed.
 */
@Singleton
class GameMetadataLocalImporter @Inject constructor(
    private val library: GameLibraryRepository,
    private val files: FileStore,
    private val access: FileAccess,
) {
    suspend fun importFolder(folderId: String, systemId: String? = null, onProgress: () -> Unit = {}) {
        val folder = library.folders().firstOrNull { it.id == folderId } ?: return
        val source = files.sources().firstOrNull { it.id == folder.sourceId } ?: return
        val originToken = library.folderToken(folderId) ?: return
        access.gameScanSession(source).use { session ->
            val resolver = MediaResolver(session, folder.path, folderId, originToken)
            val roots = resolver.directory(folder.path)
            val xml = roots.values.firstOrNull { !it.directory && it.name.equals("gamelist.xml", true) }
            val mediaDirectories = roots.values.filter { it.directory && it.name.lowercase(Locale.ROOT) in
                setOf("images", "covers", "screenshots", "media", "downloaded_media") }
            if (xml == null && mediaDirectories.isEmpty()) return
            if (xml != null) {
                session.open(xml.path).use { media ->
                    if (media.size > GameGamelistReader.MAX_BYTES) throw IOException("Game metadata XML exceeds 8 MiB")
                    GameGamelistReader.parse(SeekableInput(media)) { entry ->
                        currentCoroutineContext().ensureActive()
                        val relative = gameSidecarRelativePath(entry.path) ?: return@parse
                        val path = if (folder.path.isEmpty()) relative else folder.path.trimEnd('/') + "/" + relative
                        val game = library.folderGameByPath(folderId, path)
                            ?: gameSidecarFilenameFallback(relative, source.kind == "local")
                                ?.let { library.folderGameByUniqueFilename(folderId, it) }
                            ?: return@parse
                        if (systemId != null && game.systemId != systemId) return@parse
                        if (files.sources().none { it.id == source.id && it.sameLocation(source) }) return@parse
                        val ticket = library.metadataTicket(game.fileId, folderId, originToken) ?: return@parse
                        val previous = library.metadata(game.fileId)
                        val imported = mergeLocalFields(previous, entry.metadata)
                        library.saveMetadata(ticket, imported, imported.lockedFields)
                        importImage(game, GameArtworkKind.COVER, entry.cover, resolver)
                        importImage(game, GameArtworkKind.BACKDROP, entry.backdrop, resolver)
                        onProgress()
                    }
                }
            }
            // ES-DE keeps media outside gamelist.xml. Support the conventional directories when
            // they are inside this explicitly configured folder; never walk a parent NAS folder.
            if (mediaDirectories.isNotEmpty()) {
                var after: String? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (library.folderToken(folderId) != originToken) break
                    val page = library.folderGames(folderId, after, 256, systemId)
                    if (page.isEmpty()) break
                    for (game in page) {
                        currentCoroutineContext().ensureActive()
                        val relative = relativeGamePath(game, folder.path)
                        // Opaque SAF IDs cannot identify the media subfolder. A duplicate
                        // basename must not attach one region's image to a different ROM.
                        if (relative == game.filename && source.kind == "local" &&
                            library.folderGameByUniqueFilename(folderId, game.filename)?.fileId != game.fileId) continue
                        val base = gameSidecarRelativePath(relative)?.substringBeforeLast('.') ?: continue
                        for (kind in listOf(GameArtworkKind.COVER, GameArtworkKind.BACKDROP)) {
                            if (library.artworkPath(game.fileId, kind) != null) continue
                            val directory = if (kind == GameArtworkKind.COVER) "covers" else "screenshots"
                            val alternatives = buildList {
                                for (ext in listOf("png", "jpg", "jpeg", "webp")) {
                                    add("$directory/$base.$ext")
                                    add("media/$directory/$base.$ext")
                                    add("downloaded_media/${game.systemId}/$directory/$base.$ext")
                                    if (kind == GameArtworkKind.COVER) add("images/$base-image.$ext")
                                }
                            }
                            for (reference in alternatives) if (importImage(game, kind, reference, resolver)) break
                        }
                        onProgress()
                    }
                    after = page.last().fileId
                }
            }
        }
    }

    private fun relativeGamePath(game: LibraryGame, folderPath: String): String {
        if (folderPath.isEmpty()) return game.path
        val prefix = folderPath.trimEnd('/') + "/"
        return if (game.path.startsWith(prefix)) game.path.removePrefix(prefix) else game.filename
    }

    private suspend fun importImage(
        game: LibraryGame, kind: GameArtworkKind, reference: String?, resolver: MediaResolver,
    ): Boolean {
        if (reference.isNullOrBlank()) return false
        if (library.artworkPinned(game.fileId, kind)) return true
        val relative = gameSidecarRelativePath(reference) ?: return false
        val image = resolver.resolve(relative) ?: return false
        if (image.directory || image.size > GameArtworkFiles.MAX_IMAGE_BYTES) return false
        val ticket = library.metadataTicket(game.fileId, resolver.folderId, resolver.originToken) ?: return false
        return try {
            resolver.session.open(image.path).use { media ->
                if (media.size > GameArtworkFiles.MAX_IMAGE_BYTES) return@use false
                val staged = library.artworkFiles.stage(SeekableInput(media))
                try {
                    currentCoroutineContext().ensureActive()
                    if (!library.setArtwork(ticket, kind, staged, pinned = true)) return@use false
                    val previous = library.metadata(game.fileId) ?: GameMetadata(title = game.title)
                    val updated = previous.withArtwork(kind, null, null, null).copy(
                        lockedFields = previous.lockedFields + gameArtworkLock(kind))
                    library.saveMetadata(ticket, updated, updated.lockedFields)
                } finally { library.artworkFiles.discard(staged) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: IOException) { false } // One invalid picture does not discard the imported games.
    }

    private class MediaResolver(val session: GameScanFileSession, private val root: String,
                                val folderId: String, val originToken: String) {
        private val directories = object : LinkedHashMap<String, Map<String, FileEntry>>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, FileEntry>>?): Boolean = size > 24
        }
        suspend fun directory(path: String): Map<String, FileEntry> {
            directories[path]?.let { return it }
            val listing = session.list(path, 20_000) { entry ->
                entry.directory || entry.name.equals("gamelist.xml", true) ||
                    entry.name.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp")
            }
            if (listing.more) throw IOException("The metadata media directory contains too many entries")
            return listing.entries.associateBy { it.name }.also { directories[path] = it }
        }
        suspend fun resolve(relative: String): FileEntry? {
            val parts = gameSidecarRelativePath(relative)?.split('/') ?: return null
            var parent = root
            for ((index, name) in parts.withIndex()) {
                val child = directory(parent)[name] ?: return null
                if (index == parts.lastIndex) return child
                if (!child.directory) return null
                parent = child.path
            }
            return null
        }
    }
}

/** Curated local values replace automatic fields while preserving explicit user locks. */
internal fun mergeLocalFields(previous: GameMetadata?, imported: GameMetadata): GameMetadata {
    val current = previous ?: imported
    val fields = buildSet {
        if (imported.title.isNotBlank()) add("title")
        if (imported.summary.isNotBlank()) add("summary")
        if (imported.year != null) add("year")
        if (imported.genres.isNotEmpty()) add("genres")
        if (imported.developer.isNotBlank()) add("developer")
        if (imported.publisher.isNotBlank()) add("publisher")
        if (imported.players.isNotBlank()) add("players")
        if (imported.region.isNotBlank()) add("region")
    }
    fun takes(field: String): Boolean = field in fields && field !in current.lockedFields
    val merged = imported.copy(
        title = if (takes("title")) imported.title else current.title,
        summary = if (takes("summary")) imported.summary else current.summary,
        year = if (takes("year")) imported.year else current.year,
        genres = if (takes("genres")) imported.genres else current.genres,
        developer = if (takes("developer")) imported.developer else current.developer,
        publisher = if (takes("publisher")) imported.publisher else current.publisher,
        players = if (takes("players")) imported.players else current.players,
        region = if (takes("region")) imported.region else current.region,
        provider = current.provider.ifBlank { "local" }, providerGameId = current.providerGameId,
        canonicalTitle = current.canonicalTitle.ifBlank { imported.canonicalTitle }, matchLocked = current.matchLocked,
        coverUrl = current.coverUrl, backdropUrl = current.backdropUrl, titleScreenUrl = current.titleScreenUrl,
        sourceUrl = current.sourceUrl, artworkProviders = current.artworkProviders, artworkSources = current.artworkSources,
        lockedFields = current.lockedFields + fields, updatedAt = System.currentTimeMillis())
    return merged.bounded()
}

private class SeekableInput(private val media: SeekableMedia) : InputStream() {
    private var position = 0L
    private val one = ByteArray(1)
    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= media.size) return -1
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        val count = minOf(length.toLong(), media.size - position).toInt()
        val target = if (offset == 0) buffer else ByteArray(count)
        val read = media.read(position, target, count)
        if (read <= 0) throw IOException("Incomplete metadata file")
        if (offset != 0) target.copyInto(buffer, offset, 0, read)
        position += read
        return read
    }
}

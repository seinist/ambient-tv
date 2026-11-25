package com.ambient.tv.games.metadata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameArtworkThumbnailsTest {
    private val hash = "a".repeat(64)

    @Test fun storedCoversUseTheSmallCopyUnderEitherStorageAlias() {
        for (base in listOf("/data/user/0/com.ambient.tv", "/data/data/com.ambient.tv")) {
            val path = "$base/files/game_artwork/$hash/cover-0f8fad5b-d9cb-469f-a165-70867728950e.png"
            assertEquals(File(path), gameArtworkLocalFile(File(path)))
            assertEquals(File(path), gameArtworkLocalFile(path))
        }
    }

    @Test fun otherImagesKeepTheirOwnLoading() {
        assertNull(gameArtworkLocalFile(File("/data/user/0/com.ambient.tv/files/photos/a.png")))
        assertNull(gameArtworkLocalFile("/data/user/0/com.ambient.tv/files/game_artwork/not-a-hash/cover.png"))
        assertNull(gameArtworkLocalFile("https://romm.local/assets/cover.png"))
        assertNull(gameArtworkLocalFile(null))
    }
}

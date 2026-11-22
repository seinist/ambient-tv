package com.ambient.tv.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalPlaybackRowsTest {
    private data class Progress(val position: Long, val duration: Long, val completed: Boolean = false)
    private fun encoded(value: Progress) = "${value.position}|${value.duration}|${value.completed}"
    private fun row(key: String, value: Progress, order: Long = 0, scope: String = "old") =
        LocalPlaybackRow(key, "source", scope, order, encoded(value))
    private fun rows(database: LocalPlaybackDatabase, initial: List<LocalPlaybackRow> = emptyList(), limit: Int = 500) =
        LocalPlaybackRows(database, "test_progress", limit, legacy = { initial }, cleanup = {},
            decode = { raw -> raw.split('|').let { Progress(it[0].toLong(), it[1].toLong(), it[2].toBoolean()) } },
            encode = ::encoded, changed = {})

    @Test fun tenThousandColdReportsCoalesceAndRetainUnreportedLegacyFields() = runBlocking {
        val database = LocalPlaybackDatabase.memory()
        val values = rows(database, listOf(row("movie", Progress(50, 120_000, true))))
        repeat(10_000) { position ->
            values.update("movie", "source", "old", position.toLong(), pendingFields = setOf("position", "order")) {
                Progress(position.toLong(), it?.duration ?: 0, it?.completed ?: false)
            }
        }
        assertEquals(1, values.pendingEditCount)
        values.awaitReady()
        assertEquals(Progress(9_999, 120_000, true), values.get("movie"))
        assertEquals(1L, database.rowWriteCount)
    }

    @Test fun coldMutationsKeepTheirGlobalRecencyOrderAcrossKeys() = runBlocking {
        val values = rows(LocalPlaybackDatabase.memory())
        values.update("a", "source", "old", 1) { Progress(1, 100) }
        values.update("b", "source", "old", 2) { Progress(2, 100) }
        values.update("a", "source", "old", 3) { Progress(3, 100) }
        values.awaitReady()
        assertEquals(listOf(3L, 2L), values.all().map { it.position })
    }

    @Test fun sourceRemovalDuringColdLoadingCannotResurrectDiskOrEarlyRows() = runBlocking {
        val database = LocalPlaybackDatabase.memory()
        val values = rows(database, listOf(row("movie", Progress(50, 100))))
        values.update("other", "source", "old", 1) { Progress(30, 100) }
        values.retainScopes(mapOf("source" to "replacement"))
        values.awaitReady()
        assertTrue(values.all().isEmpty())
        val reopened = rows(database)
        reopened.awaitReady()
        assertTrue(reopened.all().isEmpty())
    }

    @Test fun replacementScopeCannotInheritUnspecifiedOldFields() = runBlocking {
        val values = rows(LocalPlaybackDatabase.memory(), listOf(row("movie", Progress(50, 100, true))))
        values.retainScopes(mapOf("source" to "replacement"))
        values.update("movie", "source", "replacement", 1, pendingFields = setOf("position")) {
            Progress(25, it?.duration ?: 0, it?.completed ?: false)
        }
        values.awaitReady()
        assertEquals(Progress(25, 0), values.get("movie"))
    }

    @Test fun oneWarmReportEncodesAndWritesOneOfFiveHundredRecords() = runBlocking {
        val database = LocalPlaybackDatabase.memory()
        var encodes = 0
        val values = LocalPlaybackRows(database, "test_progress", 500,
            legacy = { (1..500).map { row("movie-$it", Progress(it.toLong(), 100_000), it.toLong()) } }, cleanup = {},
            decode = { raw -> raw.split('|').let { Progress(it[0].toLong(), it[1].toLong(), it[2].toBoolean()) } },
            encode = { encodes++; encoded(it) }, changed = {})
        values.awaitReady()
        val before = database.rowWriteCount
        assertSame(values.all(), values.all())
        values.update("movie-250", "source", "old", 1_000) { requireNotNull(it).copy(position = 90_000) }
        assertEquals(1L, database.rowWriteCount - before)
        assertEquals(1, encodes)
        assertEquals(500, values.all().size)
        assertEquals(90_000L, values.all().first().position)
        assertEquals(100_000L, values.get("movie-249")?.duration)
    }

    @Test fun completionEditPreservesRecencyAndCapacityEvictsOnlyTheOldest() = runBlocking {
        val values = rows(LocalPlaybackDatabase.memory(), listOf(row("a", Progress(1, 100), 1), row("b", Progress(2, 100), 2)), limit = 2)
        values.awaitReady()
        values.update("a", "source", "old", 0, keepOrder = true) { it?.copy(completed = true) }
        assertEquals(listOf(2L, 1L), values.all().map { it.position })
        values.update("c", "source", "old", 3) { Progress(3, 100) }
        assertNull(values.get("a"))
        assertEquals(listOf(3L, 2L), values.all().map { it.position })
    }
}

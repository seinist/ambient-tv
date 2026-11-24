package com.ambient.tv.games.metadata

import java.io.IOException

/** Content errors are isolated per ROM, then reported after the remaining games were attempted. */
internal class GameMetadataBatchException(
    val failedGames: Int,
    val processedGames: Int,
    val firstFailure: IOException,
) : IOException("Metadata failed for $failedGames of $processedGames games", firstFailure)

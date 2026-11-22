package com.ambient.tv.ui.games

import android.content.Context
import androidx.annotation.StringRes
import com.ambient.tv.R
import com.ambient.tv.data.files.ServerKeyChangedException
import com.ambient.tv.data.files.UnverifiedServerKeyException
import com.ambient.tv.data.files.WireGuardUnavailableException
import com.ambient.tv.games.metadata.GameArtworkFailure
import com.ambient.tv.games.metadata.GameArtworkValidationException
import com.ambient.tv.games.metadata.GameMetadataFailure
import com.ambient.tv.games.metadata.GameMetadataProviderException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import timber.log.Timber

/** Keep diagnostics in the log and show a localized, actionable message in game screens. */
internal fun gameOperationError(context: Context, error: Throwable, @StringRes fallback: Int): String {
    Timber.w(error, "Game operation failed")
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val provider = chain.filterIsInstance<GameMetadataProviderException>().firstOrNull()
    val artwork = chain.filterIsInstance<GameArtworkValidationException>().firstOrNull()
    val message = when {
        provider != null -> when (provider.kind) {
            GameMetadataFailure.CREDENTIALS -> R.string.game_error_credentials
            GameMetadataFailure.RATE_LIMIT -> R.string.game_error_request_limit
            GameMetadataFailure.NETWORK -> R.string.game_error_network
            GameMetadataFailure.INVALID_RESPONSE -> R.string.game_error_provider_response
            GameMetadataFailure.UNSUPPORTED -> R.string.game_error_provider_system
        }
        artwork != null -> when (artwork.kind) {
            GameArtworkFailure.FORMAT -> R.string.game_error_artwork_format
            GameArtworkFailure.BYTES -> R.string.game_error_artwork_bytes
            GameArtworkFailure.DIMENSIONS -> R.string.game_error_artwork_dimensions
        }
        chain.any { it is WireGuardUnavailableException } -> R.string.files_wireguard_error
        chain.any { it is UnverifiedServerKeyException } -> R.string.files_server_key_unconfirmed
        chain.any { it is ServerKeyChangedException } -> R.string.files_server_key_changed
        chain.any { it is SecurityException || it.message.orEmpty().contains("STATUS_LOGON_FAILURE") ||
            it.message.orEmpty().contains("STATUS_ACCESS_DENIED") } -> R.string.files_permission_error
        chain.any { it is FileNotFoundException } -> R.string.game_error_file_missing
        chain.any { it is UnknownHostException || it is SocketException || it is InterruptedIOException } ->
            R.string.game_error_network
        chain.any { it is IllegalArgumentException && it.message == "This folder is already assigned" } ->
            R.string.game_error_folder_assigned
        chain.any { it is IllegalArgumentException && it.message == "Too many game folders" } ->
            R.string.game_error_folder_limit
        chain.any { it is IllegalArgumentException && it.message in setOf(
            "Game folder scan limit reached", "Game library item limit reached") ||
            it is IOException && it.message == "Directory exceeds 200000 entries; the existing catalog was not changed" } ->
            R.string.game_error_scan_limit
        // The repository already uses this localized setting explanation when an explicit
        // information refresh is requested with online providers disabled.
        chain.any { it.message == context.getString(R.string.game_metadata_settings_off_hint) } ->
            R.string.game_metadata_settings_off_hint
        else -> fallback
    }
    return context.getString(message)
}

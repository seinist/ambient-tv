package com.ambient.tv.ui.games

import android.content.Context
import android.graphics.Bitmap
import com.ambient.tv.R
import com.ambient.tv.data.files.ServerKeyChangedException
import com.ambient.tv.data.files.UnverifiedServerKeyException
import com.ambient.tv.data.files.WireGuardUnavailableException
import com.ambient.tv.games.metadata.GameArtworkFailure
import com.ambient.tv.games.metadata.GameArtworkFiles
import com.ambient.tv.games.metadata.GameArtworkValidationException
import com.ambient.tv.games.metadata.GameMetadataFailure
import com.ambient.tv.games.metadata.GameMetadataProviderException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameOperationErrorsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val staging get() = File(context.cacheDir, "game_artwork_staging")

    @Before fun clearStaging() { staging.deleteRecursively() }

    @Test fun unsupportedArtworkKeepsTheFormatRecoveryAndCleansTheRejectedFile() {
        assertArtworkFailure("not an image".toByteArray(), GameArtworkFailure.FORMAT,
            R.string.game_error_artwork_format)
    }

    @Test fun oversizedArtworkKeepsTheSizeRecoveryAndCleansTheRejectedFile() {
        assertArtworkFailure(ByteArray(GameArtworkFiles.MAX_IMAGE_BYTES.toInt() + 1),
            GameArtworkFailure.BYTES, R.string.game_error_artwork_bytes)
    }

    @Test fun excessiveImageDimensionsHaveTheirOwnRecovery() {
        val bitmap = Bitmap.createBitmap(8193, 1, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        assertArtworkFailure(bytes, GameArtworkFailure.DIMENSIONS, R.string.game_error_artwork_dimensions)
    }

    @Test fun ordinaryArtworkStorageFailureDoesNotBecomeAnImageValidationError() {
        staging.writeText("block creation of the staging directory")
        val error = runCatching { GameArtworkFiles(context).stage(byteArrayOf(1).inputStream()) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error is GameArtworkValidationException)
        assertEquals(context.getString(R.string.game_error_update),
            gameOperationError(context, error!!, R.string.game_error_update))
    }

    @Test fun wrappedProviderFailuresRetainTheirSpecificRecovery() {
        val cases = mapOf(
            GameMetadataFailure.CREDENTIALS to R.string.game_error_credentials,
            GameMetadataFailure.RATE_LIMIT to R.string.game_error_request_limit,
            GameMetadataFailure.NETWORK to R.string.game_error_network,
            GameMetadataFailure.INVALID_RESPONSE to R.string.game_error_provider_response,
            GameMetadataFailure.UNSUPPORTED to R.string.game_error_provider_system,
        )
        cases.forEach { (kind, expected) -> assertMapped(
            IllegalStateException("provider operation", GameMetadataProviderException(kind)), expected) }
    }

    @Test fun nestedAccessTrustAndConnectivityErrorsKeepActionableRecovery() {
        val cases = listOf(
            WireGuardUnavailableException("route unavailable") to R.string.files_wireguard_error,
            UnverifiedServerKeyException("fingerprint", false) to R.string.files_server_key_unconfirmed,
            ServerKeyChangedException("fingerprint", true) to R.string.files_server_key_changed,
            SecurityException("permission rejected") to R.string.files_permission_error,
            IOException("STATUS_LOGON_FAILURE") to R.string.files_permission_error,
            IOException("STATUS_ACCESS_DENIED") to R.string.files_permission_error,
            FileNotFoundException("private file path") to R.string.game_error_file_missing,
            UnknownHostException("private host") to R.string.game_error_network,
            SocketException("reset") to R.string.game_error_network,
            SocketTimeoutException("timed out") to R.string.game_error_network,
        )
        cases.forEach { (cause, expected) -> assertMapped(IOException("outer operation", cause), expected) }
    }

    @Test fun knownScanLimitsRequestANarrowerFolderInsteadOfRepeatingTheScan() {
        listOf(
            IllegalArgumentException("Game folder scan limit reached"),
            IllegalArgumentException("Game library item limit reached"),
            IOException("Directory exceeds 200000 entries; the existing catalog was not changed"),
        ).forEach { assertMapped(IOException("scan failed", it), R.string.game_error_scan_limit) }
    }

    @Test fun unknownErrorsUseTheOperationFallbackWithoutExposingDiagnostics() {
        val error = IOException("Internal database path /private/game-library; request token=secret")
        for (fallback in listOf(R.string.game_error_scan, R.string.game_error_import, R.string.game_error_update)) {
            val message = gameOperationError(context, error, fallback)
            assertEquals(context.getString(fallback), message)
            assertFalse(message.contains("secret"))
            assertFalse(message.contains("/private/"))
        }
    }

    private fun assertArtworkFailure(bytes: ByteArray, kind: GameArtworkFailure, expected: Int) {
        val error = runCatching { GameArtworkFiles(context).stage(bytes.inputStream()) }.exceptionOrNull()
        assertTrue("Staging must reject the image", error is GameArtworkValidationException)
        assertEquals(kind, (error as GameArtworkValidationException).kind)
        assertMapped(IOException("image selection", error), expected)
        assertTrue("Rejected artwork must not leave temporary files", staging.listFiles().orEmpty().isEmpty())
    }

    private fun assertMapped(error: Throwable, expected: Int) {
        assertEquals(context.getString(expected), gameOperationError(context, error, R.string.game_error_update))
    }
}

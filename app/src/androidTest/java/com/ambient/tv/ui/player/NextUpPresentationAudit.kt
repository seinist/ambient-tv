package com.ambient.tv.ui.player

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File

/** Deterministic local artwork verifies fallback geometry without contacting a media server. */
class NextUpPresentationAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun settle() { Thread.sleep(900); instrumentation.waitForIdleSync() }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun screenNodes(): List<AccessibilityNodeInfo> {
        // Compose updates text in place; UiAutomation can retain nodes from the previous state.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun texts() = screenNodes().mapNotNull { it.text?.toString() }
    private fun label(name: String, vararg args: Any): String {
        val context = instrumentation.targetContext
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "Missing string: $name" }
        return context.getString(id, *args)
    }
    private fun awaitText(expected: String) {
        repeat(25) {
            if (texts().contains(expected)) return
            Thread.sleep(200)
        }
        error("Expected $expected; found ${texts()}")
    }
    private fun assertImageRatio(expected: Float) {
        val image = screenNodes().firstOrNull { it.contentDescription?.toString() == "The Long Way Home" }
            ?: error("Artwork missing from accessibility tree")
        val bounds = Rect(); image.getBoundsInScreen(bounds)
        check(kotlin.math.abs(bounds.width().toFloat() / bounds.height() - expected) < 0.05) { "Wrong artwork geometry: $bounds" }
    }
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun artwork(name: String, width: Int, height: Int): String {
        val file = File(instrumentation.targetContext.filesDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(26, 45, 67))
        val paint = Paint().apply { color = Color.rgb(210, 162, 71) }
        canvas.drawRect(width * 0.12f, height * 0.15f, width * 0.88f, height * 0.85f, paint)
        paint.color = Color.rgb(36, 60, 78)
        canvas.drawCircle(width * 0.5f, height * 0.5f, width * 0.23f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".skipaudit"))
        val landscape = artwork("landscape.png", 640, 360)
        val poster = artwork("poster.png", 240, 360)
        val candidate = mutableStateOf(NextUpCandidate("next", "The Long Way Home", "Example Series", landscape, source = "audit",
            mediaType = "episode", season = 2, episode = 4, durationMs = 2_880_000, contentRating = "DE/12", posterUrl = poster))
        val autoplay = mutableStateOf(true)
        val confirmation = mutableStateOf(false)
        var plays = 0
        var dismisses = 0
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            main { activity.setContent {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize()) {
                        NextUpOverlay(candidate.value, 24, 40, autoplay.value, confirmation.value,
                            onPlayNow = { plays++ }, onDismiss = { dismisses++ },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp), onSkipCredits = {})
                    }
                }
            } }
            settle()
            screenshot("nextup-initial.png")
            awaitText(label("next_up_episode_starts_in", 24))
            check(texts().any { it.contains("S02 · E04") && it.contains("48") && it.contains("FSK 12") })
            assertImageRatio(16f / 9f)
            screenshot("nextup-landscape.png")
            log("PASS landscape artwork, metadata and explicit autoplay countdown")
            main { candidate.value = candidate.value.copy(artworkUrl = "file:/missing-nextup-image.png"); autoplay.value = false }
            settle()
            assertImageRatio(2f / 3f)
            awaitText(label("next_up_autoplay_off"))
            check(!texts().contains(label("next_up_episode_starts_in", 24)))
            screenshot("nextup-poster.png")
            log("PASS failed landscape falls back to uncropped poster; autoplay off has no start countdown")
            main { autoplay.value = true; confirmation.value = true }
            settle()
            screenshot("nextup-confirmation.png")
            awaitText(label("next_up_confirm"))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            settle()
            main { check(plays == 1) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
            main { check(dismisses == 1) }
            log("PASS confirmation status and D-pad actions")
        } finally { main { activity.finish() } }
    }
}

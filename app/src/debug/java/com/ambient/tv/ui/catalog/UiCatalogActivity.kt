package com.ambient.tv.ui.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.common.*
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Device review of production components, without accounts, network or changes to preferences. */
@dagger.hilt.android.AndroidEntryPoint
class UiCatalogActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Match MainActivity: decorative work yields to real remote input in device audits too.
        com.ambient.tv.data.browse.BrowseWorkBudget.interact()
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            when {
                intent.getBooleanExtra("notifications", false) -> NotificationCatalog()
                intent.getBooleanExtra("rendererSettings", false) -> RendererSettingsCatalog()
                else -> Catalog()
            }
        }
    }
}

@Composable
private fun Catalog() {
    var theme by remember { mutableStateOf("dark") }
    var scale by remember { mutableFloatStateOf(1f) }
    var accent by remember { mutableStateOf("#E5A00D") }
    var checked by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(ViewPreview.POSTER) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    AmbientTvTheme(scale, "custom", accent, theme) {
        Box(Modifier.fillMaxSize()) {
            AppBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("UI catalogue · Debug", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton("Theme: $theme", { theme = when (theme) { "dark" -> "oled"; "oled" -> "dim"; else -> "dark" } }, Modifier.focusRequester(first))
                    TvActionButton("Scale: ${(scale * 100).toInt()}%", { scale = when (scale) { 1f -> 1.2f; 1.2f -> .7f; else -> 1f } })
                    TvActionButton("Accent: $accent", { accent = when (accent) { "#E5A00D" -> "#000000"; "#000000" -> "#FFFFFF"; "#FFFFFF" -> "#315ACF"; else -> "#E5A00D" } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.ambient.tv.branding.BrandCombo()
                    com.ambient.tv.branding.BrandWordmark(height = 24.dp)
                    com.ambient.tv.branding.BrandMark(size = 32.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaSystem.entries.forEach { SystemBadge(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton("Primary", {}, selected = true)
                    TvActionButton("Secondary", {})
                    TvActionButton("Unavailable", {}, enabled = false)
                    TvChip("Selected", selected = true, onClick = {})
                }
                TvSettingRow("Setting", "Focus and selected state remain distinct.", checked, { checked = it })
                ViewPreview.entries.take(6).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { mode -> ViewPreviewChoice(ViewOption(mode.name, mode.name, mode), preview == mode, { preview = mode }, Modifier.weight(1f)) }
                    }
                }
                RightPanelValueSlider("Density", 8, 4..12, {}, valueLabel = "8 columns")
                PageUtilityBar({}, {})
            }
        }
    }
}

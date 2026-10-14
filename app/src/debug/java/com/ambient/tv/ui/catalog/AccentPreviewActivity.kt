package com.ambient.tv.ui.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.branding.BrandWordmark
import com.ambient.tv.companion.CompanionMedia
import com.ambient.tv.companion.LocalPlaybackIndicators
import com.ambient.tv.companion.PlaybackBadge
import com.ambient.tv.companion.PlaybackIndicator
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.MediaTile
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.player.TvPlayerSeekBar
import com.ambient.tv.ui.theme.*

/** A launcher-accessible, offline design review using the production media tiles and seek bar. */
@dagger.hilt.android.AndroidEntryPoint
class AccentPreviewActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var preferences: com.ambient.tv.data.local.AppPreferences
    private var appearanceRevision by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        appearanceRevision++
    }

    override fun attachBaseContext(base: android.content.Context) {
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(java.util.Locale.GERMAN)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AccentPreview(preferences, appearanceRevision) }
    }
}

@Composable
private fun AccentPreview(preferences: com.ambient.tv.data.local.AppPreferences, appearanceRevision: Int) {
    val keys = remember { AccentOptions.keys.toList() + "custom" }
    val names = listOf("Gold", "Orange", "Blau", "Türkis", "Grün", "Violett", "Rosa", "Rot", "Eigene Farbe")
    var colorIndex by rememberSaveable(appearanceRevision) { mutableIntStateOf(keys.indexOf(preferences.accentColorKey).coerceAtLeast(0)) }
    val customHex = remember(appearanceRevision) { preferences.customAccentHex }
    val customPlaybackHex = remember(appearanceRevision) { preferences.customPlaybackAccentHex }
    var complementary by rememberSaveable { mutableStateOf(true) }
    var playing by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var position by rememberSaveable { mutableLongStateOf(1_930_000L) }
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    val samples = remember(context) {
        listOf(
            sample(context.packageName, R.drawable.accent_preview_mountains, "Über den Wolken", 0, 1_930_000L, 5_760_000L, "Dokumentation"),
            sample(context.packageName, R.drawable.accent_preview_orbit, "Jenseits des Horizonts", 1, 3_860_000L, 7_020_000L, "Science-Fiction"),
            sample(context.packageName, R.drawable.accent_preview_city, "Die stille Stadt", 2, 900_000L, 6_300_000L, "Drama"),
        )
    }
    val current = samples[selected]
    val duration = current.durationMs!!
    val indicator = PlaybackIndicator(CompanionMedia("device-local", current.ratingKey), emptyList(),
        if (playing) "playing" else "paused", "Vorschau", false)

    AmbientTvTheme(uiScale = .85f, accentColorKey = keys[colorIndex], customAccentHex = customHex,
        customPlaybackAccentHex = customPlaybackHex, complementaryPlayback = complementary) {
        CompositionLocalProvider(
            LocalPlaybackIndicators provides listOf(indicator),
            androidx.tv.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            val scheme = MaterialTheme.colorScheme
            val playback = LocalPlaybackAccent.current
            Column(
                Modifier.fillMaxSize().background(scheme.background)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 44.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BrandWordmark(height = 25.dp)
                        Text("Interaktive Designvorschau · Beispieldaten", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton("Einstellungen", {
                            context.startActivity(android.content.Intent(context, AppearanceSettingsActivity::class.java))
                        })
                        TvActionButton("Farbe: ${names[colorIndex]}", { colorIndex = (colorIndex + 1) % keys.size }, Modifier.focusRequester(first))
                        TvActionButton(if (complementary) "RGB-Komplement" else "Einfarbig", { complementary = !complementary }, selected = complementary)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorRole("Aktionen & Auswahl", accentColorFor(keys[colorIndex], customHex))
                    ColorRole("Wiedergabe & Fortschritt", if (complementary) playbackAccentColorFor(keys[colorIndex], customPlaybackHex, customHex) else accentColorFor(keys[colorIndex], customHex))
                    Spacer(Modifier.weight(1f))
                    Text("Helle Kontur = Fernbedienungsfokus", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(14.dp)).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(current.title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text("2026  ·  ${current.genres.first()}  ·  ${duration / 60_000} Min.", color = scheme.onSurfaceVariant, fontSize = 13.sp)
                        Text("Fortsetzen bei ${com.ambient.tv.companion.playbackTime(position)}", color = playback.text, fontSize = 16.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                        PlaybackBadge(indicator)
                        TvActionButton(if (playing) "Pause" else "Fortsetzen", { playing = !playing }, selected = true,
                            icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Weiterschauen", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        samples.forEachIndexed { index, media ->
                            MediaTile(
                                item = if (index == selected) media.copy(viewOffsetMs = position) else media,
                                onClick = { selected = index; position = media.viewOffsetMs!!; playing = true },
                                style = MediaTileStyle.LANDSCAPE,
                                showDescription = false,
                            )
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Wiedergabe · ${current.title}", fontSize = 14.sp)
                        Text("← → Spulen   ·   OK Pause / Fortsetzen", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    TvPlayerSeekBar(position, duration,
                        onSeekBy = { position = (position + it).coerceIn(0L, duration) }, onInteraction = {},
                        onTogglePlayback = { playing = !playing }, onPlay = { playing = true })
                }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
        }
    }
}

@Composable
private fun ColorRole(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(26.dp).background(color, RoundedCornerShape(6.dp)))
        Column {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("#%06X".format(color.toArgb() and 0xFFFFFF), fontSize = 13.sp)
        }
    }
}

private fun sample(pkg: String, drawable: Int, title: String, id: Int, position: Long, duration: Long, genre: String) = MediaItem(
    ratingKey = "accent-preview-$id", title = title, type = "movie", index = null,
    thumbUrl = "android.resource://$pkg/$drawable", artUrl = "android.resource://$pkg/$drawable",
    year = 2026, summary = null, tagline = null, durationMs = duration, viewOffsetMs = position,
    parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
    contentRating = null, rating = null, studio = null, genres = listOf(genre),
)

package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.annotation.StringRes
import com.ambient.tv.R
import com.ambient.tv.data.local.SystemNameOverrides

/**
 * [logo] is the monochrome symbol used at icon sizes. For a heading there is either a [wordmark] —
 * the provider's full lockup, name included — or, where no such lockup exists, a [coloredLogo]
 * plus [brandColor] from which one is assembled with the name set as text.
 * [brand] is the provider name as the string resources spell it inside longer texts.
 */
enum class MediaSystem(
    val key: String,
    val label: Int,
    val logo: Int?,
    val wordmark: Int? = null,
    val coloredLogo: Int? = null,
    val brandColor: Color? = null,
    val brand: String? = null,
) {
    PLEX("plex", R.string.nav_plex, R.drawable.system_plex, R.drawable.wordmark_plex, brand = "Plex"),
    JELLYFIN("jellyfin", R.string.nav_jellyfin, R.drawable.system_jellyfin, R.drawable.wordmark_jellyfin, brand = "Jellyfin"),
    EMBY(
        "emby", R.string.nav_emby, R.drawable.system_emby,
        coloredLogo = R.drawable.logo_emby, brandColor = Color(0xFF52B54B), brand = "Emby",
    ),
    TVHEADEND(
        "tvheadend", R.string.tvh_title, R.drawable.system_tvheadend,
        coloredLogo = R.drawable.logo_tvheadend, brandColor = Color(0xFF00BCFA), brand = "TVHeadend",
    ),
    YOUTUBE(
        "youtube", R.string.nav_youtube, R.drawable.system_youtube,
        coloredLogo = R.drawable.logo_youtube, brandColor = Color(0xFFFF0000), brand = "SmartTube",
    ),
    SPOTIFY("spotify", R.string.spotify_title, R.drawable.system_spotify, R.drawable.wordmark_spotify, brand = "Spotify"),
    RADIO("radio", R.string.radio_title, R.drawable.system_radio, coloredLogo = R.drawable.logo_radio_browser_official, brandColor = Color(0xFF5A4A8A), brand = "Radio Browser"),
    DLNA("dlna", R.string.dlna_title, R.drawable.system_dlna, R.drawable.wordmark_dlna),
    TRAILERS("trailers", R.string.trailers_title, R.drawable.system_tmdb, R.drawable.wordmark_tmdb),
    IPTV("iptv", R.string.iptv_title, R.drawable.system_iptv),
    FILES("files", R.string.files_title, null);

    /** A provider mark already names its system, so a text label next to it is a duplicate. */
    val hasBrandMark: Boolean get() = logo != null

    /** TMDB's lettering is unreadable at icon size and DLNA's symbol has none, so neither spells its name. */
    val markSpellsName: Boolean get() = hasBrandMark && this !in setOf(TRAILERS, DLNA, IPTV, RADIO)

    /** Name given to this system in Settings; null while it keeps the provider's own name. */
    val customName: String? get() = SystemNameOverrides.names[key]

    /** [text] with the provider name swapped for the [customName], if there is one. */
    fun renamed(text: String): String {
        val custom = customName ?: return text
        return brand?.let { text.replace(it, custom) } ?: text
    }

    companion object {
        private val brands = Regex(entries.mapNotNull { it.brand }.joinToString("|") { Regex.escape(it) })

        /** Logo catalog on first launch and on Settings › Add system. */
        val addOrder: List<MediaSystem> = listOf(
            PLEX, JELLYFIN, EMBY, DLNA, TVHEADEND, IPTV, YOUTUBE, SPOTIFY, RADIO, TRAILERS, FILES,
        )

        fun fromKey(key: String?): MediaSystem? = when (key?.lowercase()) {
            "tv", "tvh", "live_tv" -> TVHEADEND
            "smarttube" -> YOUTUBE
            else -> entries.firstOrNull { it.key == key?.lowercase() }
        }

        /** [renamed] for a text that names several systems. One pass, so a custom name is never renamed again. */
        fun renamedAll(text: String): String {
            if (SystemNameOverrides.names.isEmpty()) return text
            return brands.replace(text) { match ->
                entries.first { it.brand == match.value }.customName ?: match.value
            }
        }
    }
}

/** The name to show for [system]: the one chosen in Settings, else [default]. */
@Composable
fun systemName(system: MediaSystem, default: String = stringResource(system.label)): String = system.customName ?: default

/** A string resource that mentions [system], following its custom name. */
@Composable
fun systemString(system: MediaSystem, @StringRes id: Int): String = system.renamed(stringResource(id))

/** Informational only: provider marks never add a D-pad focus stop or perform network work. */
@Composable
fun SystemLogo(system: MediaSystem, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color? = null) {
    // Every provider mark is drawn monochrome from its alpha silhouette, so the rail and the
    // page headings stay one visual system instead of mixing brand colours with themed icons.
    if (system.logo != null) {
        Icon(painterResource(system.logo), null, modifier.size(size), tint = tint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = .82f))
    } else {
        Icon(Icons.Filled.Folder, null, modifier.size(size), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SystemBadge(
    system: MediaSystem,
    modifier: Modifier = Modifier,
    // A renamed system is no longer spelled out by its mark, so its name is shown next to it.
    showName: Boolean = !system.markSpellsName || system.customName != null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemLogo(system, size = if (showName) 22.dp else 26.dp)
        if (showName) Text(systemName(system), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Page heading for a provider. A [title] that merely repeats the provider name is dropped so the
 * brand mark carries the identity alone; a real content title (a library name) is always kept.
 */
@Composable
fun SystemPageTitle(system: MediaSystem, modifier: Modifier = Modifier, title: String? = null) {
    val provider = stringResource(system.label)
    val custom = system.customName
    val name = custom ?: provider
    val heading = title?.takeIf { it != name && it != provider } ?: name.takeIf {
        // SmartTube's mark is YouTube's, so it does not spell the app name on its own.
        custom != null || !system.markSpellsName || system == MediaSystem.YOUTUBE
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SystemLogo(system, size = if (heading == null) 36.dp else 28.dp)
        if (heading != null) Text(heading, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Heading lockup for a system: the provider's own coloured wordmark where one exists, otherwise
 * the plain name. Decorative only — it never takes a D-pad focus stop.
 */
@Composable
fun SystemWordmark(system: MediaSystem?, fallback: String, modifier: Modifier = Modifier, height: Dp = 22.dp) {
    val wordmark = system?.wordmark
    val coloredLogo = system?.coloredLogo
    val custom = system?.customName
    when {
        // A wordmark spells the provider name, so a renamed system gets its symbol and the new name.
        custom != null ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (coloredLogo != null) {
                    Image(painterResource(coloredLogo), null, Modifier.size(height), contentScale = ContentScale.Fit)
                } else {
                    SystemLogo(system, size = height)
                }
                Text(custom, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        system == MediaSystem.YOUTUBE && coloredLogo != null ->
            Row(
                modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Image(
                    painterResource(coloredLogo),
                    null,
                    Modifier.offset(y = 2.dp).size(height),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    text = "SmartTube",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.8).sp,
                    ),
                    color = Color.White.copy(alpha = 0.96f),
                    maxLines = 1,
                )
            }
        wordmark != null -> {
            // Sized from the wordmark's own aspect ratio so a wide one (TMDB) keeps the full height
            // instead of being shrunk to its intrinsic width.
            val painter = painterResource(wordmark)
            val ratio = painter.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }
            // TMDB's and DLNA's lettering fills the whole drawable height; the other wordmarks carry
            // headroom, so these two are drawn smaller to match their visual size.
            val scale = when (system) { MediaSystem.TRAILERS -> .72f; MediaSystem.DLNA -> .85f; else -> 1f }
            Image(painter, stringResource(system.label), modifier.height(height * scale).aspectRatio(ratio), contentScale = ContentScale.Fit)
        }
        coloredLogo != null ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(coloredLogo), null, Modifier.size(height), contentScale = ContentScale.Fit)
                // [fallback] is the caller's own name for the system, so the heading reads the
                // same as the menu row that opened it.
                Text(
                    fallback,
                    style = MaterialTheme.typography.titleMedium,
                    color = system.brandColor ?: MaterialTheme.colorScheme.onSurface,
                )
            }
        else -> Text(fallback, style = MaterialTheme.typography.titleMedium, modifier = modifier)
    }
}

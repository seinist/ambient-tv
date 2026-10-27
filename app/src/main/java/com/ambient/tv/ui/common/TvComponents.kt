package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.domain.model.MediaItem

/** Solid enough to stay readable over faded hero artwork and bright stills. */
internal val ArtworkChipFill = Color.Black.copy(alpha = 0.82f)
internal val ArtworkChipBorder = Color.White.copy(alpha = 0.30f)

@Composable
fun MetaBadge(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val shape = RoundedCornerShape(6.dp)
    val foreground = if (emphasized) MaterialTheme.colorScheme.primary else Color.White
    Box(
        modifier = modifier
            .background(ArtworkChipFill, shape)
            .then(if (emphasized) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), shape) else Modifier)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = foreground, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), maxLines = 1, softWrap = false)
    }
}

@Composable
fun ContentRatingBadge(
    rating: String,
    modifier: Modifier = Modifier,
) {
    val age = parseContentAge(rating)
    val display = age?.let { "$it+" } ?: rating.trim()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .background(ArtworkChipFill, shape)
            .border(1.dp, ArtworkChipBorder, shape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = display,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = display,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

/** Plex-style "✓ Watched" chip, styled like [ContentRatingBadge] so both share one row. */
@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    val label = androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.media_watched_badge)
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .background(ArtworkChipFill, shape)
            .border(1.dp, ArtworkChipBorder, shape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

/**
 * Extracts an explicit minimum age from common media rating strings. We deliberately do not
 * invent ages for classifications such as PG or R where the label itself isn't a strict age.
 */
internal fun parseGermanFskAge(rating: String): Int? {
    val value = rating.trim()
    val prefixed = Regex(
        pattern = "(?i)^(?:de|fsk(?:\\s*ab)?)[\\/ _-]*(0|6|12|16|18)(?:\\s*(?:jahre?)?\\+?)?$",
    ).matchEntire(value)
    val age = prefixed?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("^(0|6|12|16|18)$").matchEntire(value)?.value?.toIntOrNull()
    return age?.takeIf { it in setOf(0, 6, 12, 16, 18) }
}

internal fun parseContentAge(rating: String): Int? {
    val value = rating.trim()
    val normalized = value.uppercase()

    val explicit = Regex(
        pattern = "(?i)^(?:DE[\\/_ -]*|FSK(?:\\s*AB)?[\\/_ -]*|PEGI[\\/_ -]*|USK[\\/_ -]*)(0|3|6|7|12|16|18)(?:\\s*(?:JAHRE?)?\\+?)?$",
    ).matchEntire(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (explicit != null) return explicit

    Regex("^(0|3|6|7|12|15|16|18)$").matchEntire(normalized)?.value?.toIntOrNull()?.let { return it }
    Regex("^(12A|12|15|18)$").matchEntire(normalized)?.value?.removeSuffix("A")?.toIntOrNull()?.let { return it }

    return when (normalized) {
        "PG-13" -> 13
        "NC-17" -> 17
        "TV-Y7" -> 7
        "TV-14" -> 14
        else -> null
    }
}

internal fun normalizeRatingToFive(score: Double, maxScore: Double): Double? {
    if (!score.isFinite() || !maxScore.isFinite() || maxScore <= 0.0) return null
    return (score / maxScore * 5.0).coerceIn(0.0, 5.0)
}

@Composable
fun RatingScoreBadge(
    score: Double,
    modifier: Modifier = Modifier,
    maxScore: Double = 10.0,
    label: String? = null,
) {
    val fiveStarScore = normalizeRatingToFive(score, maxScore) ?: return
    val roundedStars = kotlin.math.round(fiveStarScore).toInt().coerceIn(0, 5)
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .background(ArtworkChipFill, shape)
            .border(1.dp, ArtworkChipBorder, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(5) { index ->
                Text(
                    text = if (index < roundedStars) "★" else "☆",
                    color = if (index < roundedStars) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
        Text(
            text = "%.1f/5".format(fiveStarScore),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            maxLines = 1,
        )
    }
}


@Composable
fun PrimaryRatingScoreBadge(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    when {
        item.rating != null -> RatingScoreBadge(item.rating, modifier = modifier, maxScore = 10.0)
        item.audienceRating != null -> RatingScoreBadge(item.audienceRating, modifier = modifier, maxScore = 10.0)
        item.criticRating != null -> RatingScoreBadge(item.criticRating, modifier = modifier, maxScore = 100.0)
    }
}

// Badges wrap instead of being clipped: on a phone in portrait, year + runtime + age rating + up
// to three scores are wider than the column beside a cover (detail „more“ dialog).
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaBadgeRow(
    badges: List<String>,
    contentRating: String? = null,
    rating: Double? = null,
    audienceRating: Double? = null,
    criticRating: Double? = null,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty() && contentRating.isNullOrBlank() && rating == null && audienceRating == null && criticRating == null) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        badges.take(6).forEach { MetaBadge(it) }
        contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
        rating?.let { RatingScoreBadge(it) }
        audienceRating?.let {
            RatingScoreBadge(
                score = it,
                maxScore = 10.0,
                label = androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.score_audience),
            )
        }
        criticRating?.let {
            RatingScoreBadge(
                score = it,
                maxScore = 100.0,
                label = androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.score_critics),
            )
        }
    }
}

private fun normalizeDynamicRange(value: String?, profile: String?): String? {
    val text = listOfNotNull(value, profile).joinToString(" ").lowercase()
    return when {
        "dolby vision" in text || "dovi" in text || "dvhe" in text -> "DOLBY VISION"
        "hdr10+" in text || "hdr10plus" in text -> "HDR10+"
        "hdr" in text -> "HDR"
        else -> null
    }
}

private fun normalizeVideoCodec(codec: String?): String? = when (codec?.lowercase()) {
    "hevc", "h265" -> "HEVC"
    "h264", "avc" -> "H.264"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    else -> codec?.uppercase()?.takeIf { it.isNotBlank() }
}

private fun normalizeAudioCodec(codec: String?, profile: String?): String? {
    val c = codec?.lowercase().orEmpty()
    val p = profile?.lowercase().orEmpty()
    return when {
        "truehd" in c || "truehd" in p -> "TRUEHD"
        c in setOf("eac3", "e-ac-3", "ec-3") -> "E-AC3"
        c in setOf("ac3", "ac-3") -> "AC3"
        "dts" in c && ("ma" in p || "hd" in p) -> "DTS-HD"
        "dts" in c || c == "dca" -> "DTS"
        c == "aac" -> "AAC"
        c == "opus" -> "OPUS"
        else -> codec?.uppercase()?.takeIf { it.isNotBlank() }
    }
}

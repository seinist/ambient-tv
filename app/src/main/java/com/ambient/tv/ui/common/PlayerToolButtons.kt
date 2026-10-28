package com.ambient.tv.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.ui.player.AbRepeat
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.LyricsSettings
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.PlaybackBookmarks
import com.ambient.tv.ui.player.PlayerToolsWork
import com.ambient.tv.ui.player.saveVideoScreenshot
import com.ambient.tv.ui.player.stepFrame
import com.ambient.tv.ui.player.videoImagePreferences
import kotlinx.coroutines.launch

/** Icon of an action-bar button, also shown next to it in the OSD button list. */
internal val OsdButton.icon: ImageVector get() = when (this) {
    OsdButton.SEEK -> Icons.Filled.FastForward
    OsdButton.STOP -> Icons.Filled.Stop
    OsdButton.NEXT -> Icons.Filled.SkipNext
    OsdButton.ZAP -> Icons.Filled.SwapVert
    OsdButton.RECORD -> Icons.Filled.FiberManualRecord
    OsdButton.GO_LIVE -> Icons.Filled.LiveTv
    OsdButton.EXIT -> Icons.Filled.FullscreenExit
    OsdButton.CHAPTERS -> Icons.AutoMirrored.Filled.ViewList
    OsdButton.CHANNEL -> Icons.Filled.AccountCircle
    OsdButton.SUBSCRIBE -> Icons.Filled.Subscriptions
    OsdButton.AUDIO, OsdButton.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
    OsdButton.SUBTITLES -> Icons.Filled.Subtitles
    OsdButton.VIDEO -> Icons.Filled.Movie
    OsdButton.VISUALIZER -> Icons.Filled.GraphicEq
    OsdButton.SLEEP -> Icons.Filled.Bedtime
    OsdButton.SHUFFLE -> Icons.Filled.Shuffle
    OsdButton.REPEAT -> Icons.Filled.Repeat
    OsdButton.STATS -> Icons.Filled.Info
    OsdButton.FPS -> Icons.Filled.Speed
    OsdButton.SCREENSHOT -> Icons.Filled.PhotoCamera
    OsdButton.BOOKMARK -> Icons.Filled.BookmarkAdd
    OsdButton.AB_REPEAT -> Icons.Filled.Loop
    OsdButton.FRAME_STEP -> Icons.Filled.ChevronRight
    OsdButton.LYRICS -> Icons.AutoMirrored.Filled.Subject
    OsdButton.TOOLS -> Icons.Filled.Build
    OsdButton.TELETEXT -> Icons.AutoMirrored.Filled.Article
    OsdButton.TELETEXT_SUBTITLES -> Icons.Filled.ClosedCaption
    OsdButton.HBBTV -> Icons.Filled.RadioButtonChecked
}

/**
 * Werkzeuge as opt-in action-bar buttons, next to the diagnostics toggles. Each one only
 * appears where its tool applies; the Werkzeuge button opens the full panel.
 */
@Composable
fun PlayerToolButtons() {
    val context = LocalContext.current
    val handle = BackgroundPlaybackSession.activeHandle() ?: return
    val metadata = BackgroundPlaybackSession.snapshot.collectAsState().value.metadata
    val live = metadata?.isLive == true
    val video = metadata?.hasVideo == true
    val navigation = LocalPlayerPanelNavigation.current
    if (video) PlayerIconButton(OsdButton.SCREENSHOT.icon, stringResource(R.string.player_tools_screenshot_save), {
        if (!PlayerToolsWork.savingScreenshot) {
            PlayerToolsWork.savingScreenshot = true
            PlayerToolsWork.scope.launch {
                try { saveVideoScreenshot(context.applicationContext, metadata?.title) }
                finally { PlayerToolsWork.savingScreenshot = false }
            }
        }
    }, osd = OsdButton.SCREENSHOT)
    PlaybackBookmarks.keyFor(metadata)?.let { key ->
        PlayerIconButton(OsdButton.BOOKMARK.icon, stringResource(R.string.player_tools_bookmark_add), {
            val position = handle.status().positionMs
            PlaybackBookmarks.add(context, key, position)
            AppNotifications.post(AppNotice(
                "bookmark", R.string.player_tools_bookmark_added, subject = metadata?.title,
                phase = NoticePhase.SUCCESS, kind = NoticeKind.PLAYBACK,
                args = listOf(formatPlaybackClock(position)), interruptPlayback = false,
            ))
        }, osd = OsdButton.BOOKMARK)
    }
    if (!live) {
        // One button walks through A, B and off; it stays marked while a range is set.
        val label = when {
            AbRepeat.active -> R.string.player_tools_ab_clear
            AbRepeat.pointA != null -> R.string.player_tools_set_b
            else -> R.string.player_tools_set_a
        }
        PlayerIconButton(OsdButton.AB_REPEAT.icon, stringResource(label), {
            when {
                AbRepeat.active -> AbRepeat.clear()
                AbRepeat.pointA != null -> AbRepeat.setB(handle)
                else -> AbRepeat.setA(handle)
            }
        }, selected = AbRepeat.pointA != null, osd = OsdButton.AB_REPEAT)
    }
    if (video && !live) {
        PlayerIconButton(Icons.Filled.ChevronLeft, stringResource(R.string.player_tools_frame_back),
            { stepFrame(handle, forward = false) }, osd = OsdButton.FRAME_STEP)
        PlayerIconButton(Icons.Filled.ChevronRight, stringResource(R.string.player_tools_frame_forward),
            { stepFrame(handle, forward = true) }, osd = OsdButton.FRAME_STEP)
    }
    if (!video && !live) {
        val revision = LyricsSettings.revision
        val preferences = remember(context) { videoImagePreferences(context) }
        val showLyrics = remember(revision.intValue) { preferences.lyricsVisible }
        PlayerIconButton(OsdButton.LYRICS.icon, stringResource(R.string.player_tools_lyrics_show), {
            preferences.lyricsVisible = !showLyrics
            revision.intValue++
        }, selected = showLyrics, osd = OsdButton.LYRICS)
    }
    if (navigation != null) {
        PlayerIconButton(OsdButton.TOOLS.icon, stringResource(R.string.player_tools), {
            navigation.open(UnifiedPlayerPanel.TOOLS)
        }, selected = navigation.panel == UnifiedPlayerPanel.TOOLS, osd = OsdButton.TOOLS)
    }
}

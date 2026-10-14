package com.ambient.tv.ui.common.projectm

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The native engines feed decoded PCM into the same visualizer and browser as Media3. */
@Composable
internal fun NativeProjectMVisualizer(
    browser: ProjectMBrowser,
    audioSource: Int,
    showControls: Boolean,
    modifier: Modifier = Modifier,
) = ProjectMVisualizer(
    analyzer = null,
    intervalSeconds = browser.intervalSeconds,
    transitionSeconds = browser.transitionSeconds,
    qualityKey = browser.qualityKey,
    categoryKey = browser.filter,
    showControls = showControls,
    modifier = modifier,
    browser = browser,
    nativeAudioSource = audioSource,
)

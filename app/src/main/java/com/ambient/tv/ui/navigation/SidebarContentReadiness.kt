package com.ambient.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf

/** Destination reports when its first usable content is on screen. */
val LocalSidebarContentReadyReporter = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
val LocalSidebarContentPending = staticCompositionLocalOf { false }

@Composable
fun ReportSidebarContentReady(ready: Boolean) {
    val report = LocalSidebarContentReadyReporter.current
    LaunchedEffect(report, ready) { report(ready) }
}

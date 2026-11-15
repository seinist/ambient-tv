package com.ambient.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.SystemNameOverrides
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.systemName

internal const val WEATHER_MENU_ID = "weather"

/** Navigation names identify the provider; a page title such as "Live TV" may name its content. */
@Composable
internal fun menuSystemDefaultName(system: MediaSystem): String =
    system.brand ?: stringResource(if (system == MediaSystem.FILES) R.string.nav_files else system.label)

/** Shared by the TV menu, handheld sources and their editor, including user-chosen names. */
@Composable
internal fun menuSystemName(system: MediaSystem): String = systemName(system, menuSystemDefaultName(system))

/** Weather is a direct destination, with the same naming rules as the media groups. */
@Composable
internal fun weatherMenuName(): String =
    SystemNameOverrides.names[WEATHER_MENU_ID] ?: stringResource(R.string.weather_title)

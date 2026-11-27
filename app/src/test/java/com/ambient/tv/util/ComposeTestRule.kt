package com.ambient.tv.util

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule as createComposeRuleV2
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Uses the Compose v2 rule with the eager effect dispatcher these tests were written for. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
internal inline fun <reified A : ComponentActivity> createAndroidComposeRule() =
    createComposeRuleV2<A>(config = ComposeUiTestConfig(UnconfinedTestDispatcher()))

package com.ambient.tv.ui.settings

import android.app.admin.DevicePolicyManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.ui.player.TvSleepStandby

/** Opt-in immediate screen-off: no controls or device-admin request on handheld devices. */
@Composable
internal fun TvSleepStandbySettings() {
    val context = LocalContext.current
    if (!isTelevision(context)) return

    var enabled by remember(context) { mutableStateOf(TvSleepStandby.isEnabled(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = TvSleepStandby.isEnabled(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = TvSleepStandby.isEnabled(context)
    }

    Text(stringResource(R.string.settings_sleep_standby_title), style = MaterialTheme.typography.titleSmall)
    if (!TvSleepStandby.isSupported(context)) {
        Text(stringResource(R.string.settings_sleep_standby_unavailable), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(stringResource(R.string.settings_sleep_standby_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TvActionButton(
        text = stringResource(if (enabled) R.string.settings_sleep_standby_disable else R.string.settings_sleep_standby_enable),
        onClick = {
            if (!isTelevision(context)) return@TvActionButton
            if (enabled) {
                runCatching {
                    context.getSystemService(DevicePolicyManager::class.java)
                        ?.removeActiveAdmin(TvSleepStandby.component(context))
                }
                enabled = TvSleepStandby.isEnabled(context)
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, TvSleepStandby.component(context))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        context.getString(R.string.settings_sleep_standby_explanation))
                runCatching { launcher.launch(intent) }
            }
        },
    )
}

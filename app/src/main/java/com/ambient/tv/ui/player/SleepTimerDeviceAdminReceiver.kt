package com.ambient.tv.ui.player

import android.app.admin.DeviceAdminReceiver

/**
 * Optional device-admin capability used exclusively for Android TV sleep timer standby.
 * All runtime entry points also check the TV device class before requesting screen-off.
 */
class SleepTimerDeviceAdminReceiver : DeviceAdminReceiver()

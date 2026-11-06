package com.ambient.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.ambient.tv.R
import androidx.core.content.ContextCompat
import com.ambient.tv.companion.CompanionDirectShareService
import com.ambient.tv.util.LocalNetworkAccess
import com.ambient.tv.ui.mobile.isTelevision

/**
 * Android requires an Activity as the sharesheet/Direct Share target. This no-display trampoline
 * immediately hands a named TV share to the short-lived background service and finishes, so the
 * source app (YouTube, browser, etc.) remains on screen. No Ambient TV UI or task is opened.
 *
 * The generic "Ambient TV" share entry, without a selected direct-share shortcut, still opens
 * the phone UI to let the user choose a paired TV. Named shares never open the UI, even if
 * permissions or background-service restrictions prevent the send.
 */
class ShareToTvActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isTelevision(this)) {
            finish()
            return
        }

        val incoming = intent
        val shortcutId = incoming.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        if (incoming.action == Intent.ACTION_SEND && incoming.type == "text/plain" &&
            shortcutId != null
        ) {
            if (!LocalNetworkAccess.isGranted(this)) {
                Toast.makeText(this, R.string.companion_needs_local_network, Toast.LENGTH_LONG).show()
            } else {
                val outgoing = Intent(this, CompanionDirectShareService::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SHORTCUT_ID, shortcutId)
                    .putExtra(Intent.EXTRA_TEXT, incoming.getCharSequenceExtra(Intent.EXTRA_TEXT))
                // A share is an explicit user gesture, so it can start the connected-device
                // transfer even though Ambient TV itself has no visible UI.
                if (runCatching { ContextCompat.startForegroundService(this, outgoing) }.isFailure) {
                    Toast.makeText(this, R.string.companion_background_failed, Toast.LENGTH_LONG).show()
                }
            }
            // Even on failure, never replace YouTube/the originating app with Ambient TV.
            finish()
            return
        }

        // Without a named recipient, preserve the interactive device picker.
        startActivity(Intent(incoming).setClass(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}

package com.ambient.tv.spotify

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ambient.tv.ui.MainActivity
import com.ambient.tv.ui.mobile.HandheldActivity
import com.ambient.tv.ui.mobile.isTelevision
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@AndroidEntryPoint
class SpotifyAuthCallbackActivity : ComponentActivity() {
    @Inject lateinit var authManager: SpotifyAuthManager

    private var handlingRedirect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        SpotifyPlaybackTakeoverUiPresence.onActivityStarted()
    }

    override fun onStop() {
        SpotifyPlaybackTakeoverUiPresence.onActivityStopped()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (handlingRedirect) return
        val redirect = intent?.data
        if (redirect == null || !authManager.isRedirect(redirect)) {
            finish()
            return
        }
        handlingRedirect = true
        lifecycleScope.launch {
            try {
                authManager.handleRedirect(redirect)
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            val target = if (isTelevision(this@SpotifyAuthCallbackActivity)) MainActivity::class.java else HandheldActivity::class.java
            startActivity(Intent(this@SpotifyAuthCallbackActivity, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
    }
}

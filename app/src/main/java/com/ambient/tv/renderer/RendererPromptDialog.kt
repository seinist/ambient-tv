package com.ambient.tv.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton

/** "Play from this sender?" — shown while a DLNA sender that is not trusted wants to start playback. */
@Composable
fun RendererPromptDialog() {
    val prompt by RendererPrompts.current.collectAsState()
    val request = prompt ?: return
    AppDialog(
        // Back or outside declines; the sender sees the transport return to stopped.
        onDismiss = { request.onAnswer(RendererAnswer.DENY) },
        title = stringResource(R.string.renderer_prompt_title),
        footer = {
            TvActionButton(stringResource(R.string.renderer_prompt_play), { request.onAnswer(RendererAnswer.ONCE) }, selected = true)
            TvActionButton(stringResource(R.string.renderer_prompt_always), { request.onAnswer(RendererAnswer.ALWAYS) })
            TvActionButton(stringResource(R.string.renderer_prompt_deny), { request.onAnswer(RendererAnswer.DENY) })
        },
    ) {
        Text(stringResource(R.string.renderer_prompt_message, request.title, request.sender), style = MaterialTheme.typography.bodyLarge)
    }
}

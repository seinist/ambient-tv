package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.BackupEncryption
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField

/**
 * Passphrase for an export with credentials ([confirm] = true, entered twice), for an optional
 * protection of a normal export ([optional] = true: empty exports plain JSON), or for importing an
 * encrypted backup. The passphrase is never stored; it only protects that one file.
 */
@Composable
internal fun BackupPassphraseDialog(
    confirm: Boolean,
    wrongPassphrase: Boolean,
    optional: Boolean = false,
    onDismiss: () -> Unit,
    onPassphrase: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val longEnough = passphrase.length >= BackupEncryption.MIN_PASSPHRASE_LENGTH
    val matches = !confirm || passphrase == repeated
    val skip = optional && passphrase.isEmpty() && repeated.isEmpty()
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(when {
            optional -> R.string.settings_backup_passphrase_optional
            confirm -> R.string.settings_backup_passphrase_set
            else -> R.string.settings_backup_passphrase_enter
        }),
        hint = stringResource(when {
            optional -> R.string.settings_backup_passphrase_optional_hint
            confirm -> R.string.settings_backup_passphrase_set_hint
            else -> R.string.settings_backup_passphrase_enter_hint
        }),
        footer = {
            TvActionButton(
                text = stringResource(when {
                    optional -> if (skip) R.string.settings_backup_export_plain else R.string.settings_backup_export_encrypted
                    confirm -> R.string.settings_export_with_credentials
                    else -> R.string.settings_import
                }),
                onClick = { onPassphrase(passphrase.toCharArray()); passphrase = ""; repeated = "" },
                enabled = skip || (longEnough && matches),
            )
            TvActionButton(text = stringResource(R.string.files_cancel), onClick = onDismiss)
        },
    ) {
        TvTextField(passphrase, { passphrase = it }, stringResource(R.string.settings_backup_passphrase),
            Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        if (confirm) {
            TvTextField(repeated, { repeated = it }, stringResource(R.string.settings_backup_passphrase_repeat),
                Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        }
        val problem = when {
            wrongPassphrase -> R.string.settings_backup_passphrase_wrong
            passphrase.isNotEmpty() && !longEnough -> R.string.settings_backup_passphrase_short
            confirm && repeated.isNotEmpty() && !matches -> R.string.settings_backup_passphrase_mismatch
            else -> null
        }
        problem?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

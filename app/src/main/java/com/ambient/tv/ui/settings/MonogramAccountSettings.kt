package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.telegram.MonogramLoginStep
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.ui.common.QrCodeDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.mobile.LocalHandheldUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@HiltViewModel
class MonogramAccountViewModel @Inject constructor(val repository: TelegramRepository) : ViewModel() {
    val login = repository.login
    fun initialize() { viewModelScope.launch { repository.restoreIfNeeded() } }
    private var pollJob: Job? = null
    fun qr() {
        viewModelScope.launch { repository.requestQr(); watchQr() }
    }
    private fun watchQr() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (repository.login.value.step == MonogramLoginStep.QR) {
                delay(1_800)
                if (repository.login.value.step == MonogramLoginStep.QR) repository.pollQr()
            }
        }
    }
    fun phone(phone: String) { viewModelScope.launch { repository.requestPhone(phone) } }
    fun code(code: String) { viewModelScope.launch { repository.confirmCode(code) } }
    fun resend() { viewModelScope.launch { repository.resendCode() } }
    fun password(password: String) { viewModelScope.launch { repository.confirmPassword(password) } }
    fun logout() { pollJob?.cancel(); viewModelScope.launch { repository.signOut() } }
    override fun onCleared() { pollJob?.cancel(); super.onCleared() }
}

@Composable
internal fun MonogramAccountSettings(viewModel: MonogramAccountViewModel = hiltViewModel()) {
    val login by viewModel.login.collectAsState()
    LaunchedEffect(Unit) { viewModel.initialize() }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var qrDismissed by remember(login.qrUrl) { mutableStateOf(false) }
    LaunchedEffect(login.step) {
        if (login.step == MonogramLoginStep.AUTHORIZED) { code = ""; password = "" }
    }
    SettingsGroup(title = stringResource(R.string.telegram_account), card = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (login.step) {
                MonogramLoginStep.UNAVAILABLE -> Text(
                    stringResource(R.string.settings_telegram_native_missing),
                    style = MaterialTheme.typography.bodyMedium,
                )
                MonogramLoginStep.AUTHORIZED -> {
                    Text(stringResource(R.string.monogram_connected, login.account ?: "Monogram"))
                    TvActionButton(text = stringResource(R.string.action_sign_out),
                        onClick = viewModel::logout, enabled = !login.busy, loading = login.busy)
                }
                else -> {
                    // A phone cannot scan a QR displayed on its own screen.
                    if (!LocalHandheldUi.current) {
                        TvActionButton(
                            text = stringResource(R.string.monogram_sign_in_qr),
                            onClick = viewModel::qr,
                            enabled = !login.busy,
                            loading = login.busy,
                        )
                    }
                    TvTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = stringResource(R.string.monogram_phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvActionButton(
                        text = stringResource(R.string.monogram_sign_in_phone),
                        onClick = { viewModel.phone(phone.trim()) },
                        enabled = !login.busy && phone.trim().startsWith("+") && phone.length >= 7,
                        loading = login.busy,
                    )
                    if (login.step == MonogramLoginStep.CODE) {
                        TvTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = stringResource(R.string.monogram_code),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TvActionButton(text = stringResource(R.string.monogram_verify_code),
                            enabled = !login.busy && code.isNotBlank(),
                            loading = login.busy,
                            onClick = { viewModel.code(code.trim()) })
                        TvActionButton(text = stringResource(R.string.monogram_resend_code),
                            enabled = !login.busy, onClick = viewModel::resend)
                    }
                    if (login.step == MonogramLoginStep.PASSWORD) {
                        TvTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.monogram_password),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TvActionButton(text = stringResource(R.string.monogram_verify_password),
                            enabled = !login.busy && password.isNotBlank(),
                            loading = login.busy,
                            onClick = { viewModel.password(password) })
                    }
                    if (login.step == MonogramLoginStep.QR) {
                        Text(stringResource(R.string.monogram_qr_pending))
                    }
                    login.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    val qr = login.qrUrl
    if (login.step == MonogramLoginStep.QR && !qr.isNullOrBlank() && !qrDismissed) {
        QrCodeDialog(
            title = stringResource(R.string.monogram_sign_in_qr),
            hint = stringResource(R.string.monogram_qr_hint),
            payload = qr,
            onDismiss = { qrDismissed = true },
            footer = {
                TvActionButton(text = stringResource(R.string.action_close),
                    onClick = { qrDismissed = true })
            },
        )
    }
}

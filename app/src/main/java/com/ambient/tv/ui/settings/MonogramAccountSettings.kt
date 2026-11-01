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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@HiltViewModel
class MonogramAccountViewModel @Inject constructor(val repository: TelegramRepository) : ViewModel() {
    val login = repository.login
    private val mutableCredentialsRevision = MutableStateFlow(0)
    val credentialsRevision = mutableCredentialsRevision.asStateFlow()
    private val mutableCredentialsError = MutableStateFlow<String?>(null)
    val credentialsError = mutableCredentialsError.asStateFlow()
    private val mutableSavingCredentials = MutableStateFlow(false)
    val savingCredentials = mutableSavingCredentials.asStateFlow()

    fun saveApiCredentials(id: String, hash: String) {
        if (mutableSavingCredentials.value) return
        mutableSavingCredentials.value = true
        mutableCredentialsError.value = null
        viewModelScope.launch {
            try {
                repository.configureApiCredentials(id, hash)
                mutableCredentialsRevision.value++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Validation errors contain no secrets. Never include user-supplied values.
                mutableCredentialsError.value = when (failure) {
                    is IllegalArgumentException -> "Invalid Monogram API ID or hash"
                    else -> "Sign out before changing the Monogram application credentials"
                }
            } finally {
                mutableSavingCredentials.value = false
            }
        }
    }
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
    val revision by viewModel.credentialsRevision.collectAsState()
    val saveError by viewModel.credentialsError.collectAsState()
    val saving by viewModel.savingCredentials.collectAsState()
    var apiId by remember(viewModel) { mutableStateOf(viewModel.repository.configuredApiId) }
    var apiHash by remember(viewModel) { mutableStateOf(viewModel.repository.configuredApiHash) }
    val configured = remember(revision) { viewModel.repository.apiCredentialsReady }
    val canEditCredentials = login.step in setOf(
        MonogramLoginStep.READY, MonogramLoginStep.UNAVAILABLE) && !login.busy && !saving
    val validId = apiId.toIntOrNull()?.let { it > 0 } == true
    val validHash = apiHash.matches(Regex("[A-Fa-f0-9]{32}"))
    LaunchedEffect(Unit) { viewModel.initialize() }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var qrDismissed by remember(login.qrUrl) { mutableStateOf(false) }
    LaunchedEffect(login.step) {
        if (login.step == MonogramLoginStep.AUTHORIZED) { code = ""; password = "" }
    }
    SettingsGroup(title = stringResource(R.string.monogram_api_settings), card = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.monogram_api_setup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvTextField(
                value = apiId,
                onValueChange = { apiId = it.filter(Char::isDigit).take(10) },
                label = stringResource(R.string.monogram_api_id),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                enabled = canEditCredentials,
                modifier = Modifier.fillMaxWidth(),
            )
            TvTextField(
                value = apiHash,
                onValueChange = { apiHash = it.trim().take(32) },
                label = stringResource(R.string.monogram_api_hash),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                enabled = canEditCredentials,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(if (configured) R.string.monogram_api_ready else R.string.monogram_api_required),
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (login.step == MonogramLoginStep.AUTHORIZED) {
                Text(stringResource(R.string.monogram_api_sign_out_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TvActionButton(
                text = stringResource(R.string.monogram_api_save),
                onClick = { viewModel.saveApiCredentials(apiId, apiHash) },
                enabled = canEditCredentials && validId && validHash &&
                    (apiId != viewModel.repository.configuredApiId ||
                     !apiHash.equals(viewModel.repository.configuredApiHash, ignoreCase = true)),
                loading = saving,
            )
        }
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
                            enabled = !login.busy && configured,
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
                        enabled = !login.busy && configured && phone.trim().startsWith("+") && phone.length >= 7,
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
                            enabled = !login.busy && configured && code.isNotBlank(),
                            loading = login.busy,
                            onClick = { viewModel.code(code.trim()) })
                        TvActionButton(text = stringResource(R.string.monogram_resend_code),
                            enabled = !login.busy && configured, onClick = viewModel::resend)
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
                            enabled = !login.busy && configured && password.isNotBlank(),
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

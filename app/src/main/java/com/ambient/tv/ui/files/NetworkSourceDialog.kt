package com.ambient.tv.ui.files

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.notifications.OperationFeedback
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.ui.common.FeedbackLayout
import com.ambient.tv.data.files.FileKinds
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.discoverNas
import androidx.compose.ui.platform.LocalContext
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.settings.TextFileImportDialog
import com.ambient.tv.ui.settings.TextFileImportSource
import com.ambient.tv.ui.settings.isUsableWakeMac

/** SSH key handling for an SFTP source; null when the host screen offers no key import. */
internal class SshKeySupport(
    /** A private key is stored for the edited source. */
    val stored: Boolean,
    val browse: (onKey: (String?) -> Unit) -> TextFileImportSource,
    /** System document picker, where the device has one. */
    val documentPicker: ((onKey: (String?) -> Unit) -> Unit)?,
)

@Composable
internal fun NetworkSourceDialog(
    source: FileSource, saving: Boolean, testing: Boolean, feedback: OperationFeedback?, wireGuardAvailable: Boolean, onDismiss: () -> Unit,
    onTest: (FileSource, String, Boolean, String?) -> Unit, onSave: (FileSource, String, Boolean, String?) -> Unit,
    networkOnly: Boolean = false,
    keyChallenge: ServerKeyChallenge? = null,
    sshKeys: SshKeySupport? = null,
) {
    val busy = saving || testing
    var kind by rememberSaveable(source.id) { mutableStateOf(source.kind) }
    var name by rememberSaveable(source.id) { mutableStateOf(source.name) }
    var host by rememberSaveable(source.id) { mutableStateOf(source.host) }
    var port by rememberSaveable(source.id) { mutableStateOf(source.port.toString()) }
    var share by rememberSaveable(source.id) { mutableStateOf(source.share) }
    var root by rememberSaveable(source.id) { mutableStateOf(source.rootPath) }
    var username by rememberSaveable(source.id) { mutableStateOf(source.username) }
    // Passwords are deliberately excluded from saved instance state and UI logs.
    var password by remember(source.id) { mutableStateOf("") }
    var passwordEdited by remember(source.id) { mutableStateOf(false) }
    var domain by rememberSaveable(source.id) { mutableStateOf(source.domain) }
    var guest by rememberSaveable(source.id) { mutableStateOf(source.guest) }
    var encrypt by rememberSaveable(source.id) { mutableStateOf(source.encrypt) }
    var smbVersion by rememberSaveable(source.id) { mutableStateOf(source.smbVersion) }
    var nfsVersion by rememberSaveable(source.id) { mutableStateOf(source.nfsVersion) }
    var uid by rememberSaveable(source.id) { mutableStateOf(source.nfsUid.toString()) }
    var gid by rememberSaveable(source.id) { mutableStateOf(source.nfsGid.toString()) }
    var mountPort by rememberSaveable(source.id) { mutableStateOf(source.nfsMountPort.toString()) }
    var secure by rememberSaveable(source.id) { mutableStateOf(source.secure) }
    var serverKey by rememberSaveable(source.id) { mutableStateOf(source.serverKey) }
    // SSH key: null = keep the stored one, "" = remove it, text = a newly loaded key. Never saved state.
    var sshKey by remember(source.id) { mutableStateOf<String?>(null) }
    var keyPaste by remember(source.id) { mutableStateOf<String?>(null) }
    var keyBrowser by remember(source.id) { mutableStateOf(false) }
    var keyRejected by remember(source.id) { mutableStateOf(false) }
    var mac by rememberSaveable(source.id) { mutableStateOf(source.wolMac) }
    var broadcast by rememberSaveable(source.id) { mutableStateOf(source.wolBroadcast) }
    var wolPort by rememberSaveable(source.id) { mutableStateOf(source.wolPort.toString()) }
    var waitSeconds by rememberSaveable(source.id) { mutableStateOf(source.wolWaitSeconds.toString()) }
    var autoWake by rememberSaveable(source.id) { mutableStateOf(source.wolAuto) }
    var wireGuard by rememberSaveable(source.id) { mutableStateOf(source.wireGuard) }
    val remotePath = kind == FileKinds.WEBDAV || kind == FileKinds.SFTP
    // "https://nas:5006/dav" (WebDAV) or "nas:2222" in the host field supply scheme, port and path.
    val address = if (kind == FileKinds.WEBDAV) parseWebDavAddress(host) else null
    val hostPort = if (address == null) splitHostPort(host) else null
    val edited = source.copy(name = name.trim(), kind = kind, host = address?.host ?: hostPort?.first ?: cleanHost(host),
        port = address?.port ?: hostPort?.second ?: port.toIntOrNull() ?: -1,
        share = share.trim().ifEmpty { address?.path.orEmpty() }, rootPath = if (kind == "smb") root else "",
        username = username, domain = if (kind == "smb") domain else "", guest = guest && kind == "smb",
        encrypt = encrypt && smbVersion != 1, smbVersion = smbVersion, nfsVersion = nfsVersion,
        nfsUid = uid.toIntOrNull() ?: -1, nfsGid = gid.toIntOrNull() ?: -1, nfsMountPort = mountPort.toIntOrNull() ?: -1,
        secure = address?.secure ?: (secure || kind != FileKinds.WEBDAV), serverKey = if (remotePath) serverKey else "",
        wolMac = mac.trim(), wolBroadcast = broadcast.trim(), wolPort = wolPort.toIntOrNull() ?: -1,
        wolWaitSeconds = waitSeconds.toIntOrNull() ?: -1, wolAuto = autoWake && isUsableWakeMac(mac), wireGuard = wireGuard)
    val form = if (networkOnly) source.copy(wolMac = edited.wolMac, wolBroadcast = edited.wolBroadcast,
        wolPort = edited.wolPort, wolWaitSeconds = edited.wolWaitSeconds, wolAuto = edited.wolAuto,
        wireGuard = edited.wireGuard) else edited
    val keepPassword = !passwordEdited && source.host.isNotBlank() && kind in FileKinds.WITH_PASSWORD && !(kind == "smb" && guest)
    val keyForForm = if (kind == FileKinds.SFTP) sshKey else null
    val keyActive = kind == FileKinds.SFTP && (sshKey?.isNotEmpty() ?: (sshKeys?.stored == true))
    // What the last test ran against: its result is only shown while the form still matches.
    var tested by remember(source.id) { mutableStateOf<Triple<FileSource, String, String?>?>(null) }
    val current = Triple(form, password, keyForForm)
    AppDialog(
        onDismiss = onDismiss,
        title = if (networkOnly) source.name + " · " + source.host else stringResource(R.string.files_network_source),
        fillHeight = true,
        header = {
            if (tested == current) feedback?.let {
                OperationFeedbackContent(it, Modifier.fillMaxWidth(), FeedbackLayout.FIELD)
            }
        },
        footer = {
            TvActionButton(stringResource(R.string.files_test), { tested = current; onTest(form, password, keepPassword, keyForForm) },
                enabled = !saving, loading = testing)
            TvActionButton(stringResource(R.string.files_save), { tested = current; onSave(form, password, keepPassword, keyForForm) }, enabled = !testing, loading = saving)
            TvActionButton(stringResource(R.string.files_cancel), onDismiss)
        },
    ) {
        if (!networkOnly) {
        val context = LocalContext.current
        // Switching kind resets port and path; dialect/version toggles below keep them.
        fun choose(newKind: String, newSecure: Boolean = secure) {
            if (kind != newKind || secure != newSecure) {
                if (kind != newKind) { share = ""; serverKey = "" }
                kind = newKind; secure = newSecure
                port = FileKinds.defaultPort(newKind, newSecure).toString()
            }
        }
        // Without Android 17's local-network permission NSD finds nothing: say so instead of searching forever.
        if (source.host.isBlank() && !com.ambient.tv.util.LocalNetworkAccess.isGranted(context)) {
            Text(stringResource(R.string.local_network_missing), style = MaterialTheme.typography.bodySmall)
        } else if (source.host.isBlank()) {
            val found by remember { discoverNas(context.applicationContext) }.collectAsState(emptyList())
            Text(stringResource(if (found.isEmpty()) R.string.files_discovery_searching else R.string.files_discovery_found),
                style = MaterialTheme.typography.bodySmall)
            found.forEach { nas ->
                TvActionButton("${nas.name} · ${FileKinds.label(nas.kind)} · ${nas.host}", {
                    choose(nas.kind, nas.secure); host = nas.host
                    nas.port.takeIf { it > 0 }?.let { port = it.toString() }
                    if (name.isBlank()) name = nas.name
                }, selected = host == nas.host && kind == nas.kind, enabled = !busy)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvActionButton("SMB", { choose(FileKinds.SMB) }, selected = kind == FileKinds.SMB, enabled = !busy)
            TvActionButton("NFS", { choose(FileKinds.NFS) }, selected = kind == FileKinds.NFS, enabled = !busy)
            TvActionButton("WebDAV", { choose(FileKinds.WEBDAV, true) }, selected = kind == FileKinds.WEBDAV, enabled = !busy)
            TvActionButton("SFTP", { choose(FileKinds.SFTP) }, selected = kind == FileKinds.SFTP, enabled = !busy)
        }
        SourceField(R.string.files_source_name, name, { name = it }, !busy)
        SourceField(if (kind == FileKinds.WEBDAV) R.string.files_webdav_address else R.string.files_host, host, { host = it }, !busy)
        // The field keeps what was typed; a full address or host:port is read when the form is built.
        if (address != null || hostPort != null) Text(stringResource(R.string.files_address_used, edited.host + ":" + edited.port +
            (if (address != null && edited.share.isNotEmpty()) edited.share else "")), style = MaterialTheme.typography.bodySmall)
        else SourceField(R.string.files_port, port, { port = it }, !busy)
        when (kind) {
            FileKinds.SMB -> SourceField(R.string.files_share, share, { share = it }, !busy)
            FileKinds.NFS -> SourceField(R.string.files_export, share, { share = it }, !busy)
            FileKinds.WEBDAV -> SourceField(R.string.files_webdav_path, share, { share = it }, !busy)
            FileKinds.SFTP -> SourceField(R.string.files_sftp_path, share, { share = it }, !busy)
        }
        when (kind) {
            FileKinds.SMB -> {
                TvSettingRow(stringResource(R.string.files_smb1), stringResource(R.string.files_smb1_hint), smbVersion == 1,
                    { if (!busy) smbVersion = if (it) 1 else 2 })
                SourceField(R.string.files_subfolder, root, { root = it }, !busy)
                TvSettingRow(stringResource(R.string.files_guest), stringResource(R.string.files_guest_hint), guest, { if (!busy) guest = it })
                if (!guest) {
                    SourceField(R.string.files_username, username, { username = it }, !busy)
                    SourceField(R.string.files_password, password, { password = it; passwordEdited = true }, !busy, secret = true)
                    if (source.host.isNotBlank()) Text(stringResource(R.string.files_password_keep), style = MaterialTheme.typography.bodySmall)
                    SourceField(R.string.files_domain, domain, { domain = it }, !busy)
                }
                if (smbVersion != 1) TvSettingRow(stringResource(R.string.files_encrypt), stringResource(R.string.files_encrypt_hint), encrypt, { if (!busy) encrypt = it })
            }
            FileKinds.NFS -> {
                TvSettingRow(stringResource(R.string.files_nfs4), stringResource(R.string.files_nfs4_hint), nfsVersion == 4,
                    { if (!busy) nfsVersion = if (it) 4 else 3 })
                if (nfsVersion != 4) Text(stringResource(R.string.files_nfs_hint), style = MaterialTheme.typography.bodySmall)
                SourceField(R.string.files_uid, uid, { uid = it }, !busy)
                SourceField(R.string.files_gid, gid, { gid = it }, !busy)
                if (nfsVersion != 4) SourceField(R.string.files_mount_port, mountPort, { mountPort = it }, !busy)
            }
            FileKinds.WEBDAV -> {
                // A typed https:// or http:// address already decides it.
                if (address == null) TvSettingRow(stringResource(R.string.files_webdav_https), stringResource(R.string.files_webdav_https_hint), secure, {
                    if (!busy) { if (port == FileKinds.defaultPort(kind, secure).toString()) port = FileKinds.defaultPort(kind, it).toString(); secure = it }
                })
                SourceField(R.string.files_username, username, { username = it }, !busy)
                SourceField(R.string.files_password, password, { password = it; passwordEdited = true }, !busy, secret = true)
                if (source.host.isNotBlank()) Text(stringResource(R.string.files_password_keep), style = MaterialTheme.typography.bodySmall)
                if (!edited.secure && username.isNotBlank()) Text(stringResource(R.string.files_webdav_cleartext), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            FileKinds.SFTP -> {
                SourceField(R.string.files_username, username, { username = it }, !busy)
                SshKeySection(
                    stored = sshKeys?.stored == true, key = sshKey, busy = busy, rejected = keyRejected,
                    canImport = sshKeys != null,
                    onBrowse = { keyBrowser = true },
                    onPaste = { keyPaste = "" },
                    onRemove = { sshKey = ""; keyRejected = false },
                )
                SourceField(if (keyActive) R.string.files_ssh_passphrase else R.string.files_password, password,
                    { password = it; passwordEdited = true }, !busy, secret = true)
                if (source.host.isNotBlank()) Text(stringResource(R.string.files_password_keep), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (remotePath) ServerKeySection(serverKey, keyChallenge?.takeIf { it.sourceId == source.id }, busy,
            onTrust = { serverKey = it }, onReset = { serverKey = "" })
        }
        if (wireGuardAvailable || wireGuard) {
            TvSettingRow(stringResource(R.string.files_wireguard), stringResource(R.string.files_wireguard_hint), wireGuard,
                { if (!busy) wireGuard = it })
        }
        Text(stringResource(R.string.files_wol_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.files_wol_hint), style = MaterialTheme.typography.bodySmall)
        SourceField(R.string.files_mac, mac, { mac = com.ambient.tv.wol.formatMacAddressInput(it) }, !busy)
        if (mac.isNotBlank() && !isUsableWakeMac(mac)) {
            Text(stringResource(R.string.settings_status_mac_invalid), color = MaterialTheme.colorScheme.error)
        }
        if (mac.isNotBlank()) {
            SourceField(R.string.files_broadcast, broadcast, { broadcast = it }, !busy)
            SourceField(R.string.files_wol_port, wolPort, { wolPort = it }, !busy)
            SourceField(R.string.files_wait_seconds, waitSeconds, { waitSeconds = it }, !busy)
        }
        val macValid = isUsableWakeMac(mac)
        TvSettingRow(stringResource(R.string.files_auto_wake),
            stringResource(if (macValid) R.string.files_auto_wake_hint else R.string.settings_auto_wake_needs_mac),
            autoWake && macValid, { if (!busy) autoWake = it }, enabled = macValid)
    }
    val acceptKey: (String?) -> Unit = { loaded -> if (loaded != null) { sshKey = loaded; keyRejected = false } else keyRejected = true }
    if (keyBrowser && sshKeys != null) {
        val importSource = remember(source.id) { sshKeys.browse(acceptKey) }
        TextFileImportDialog(
            importSource, stringResource(R.string.files_ssh_key_load), stringResource(R.string.files_ssh_key_empty),
            onSystemPicker = sshKeys.documentPicker?.let { picker -> { keyBrowser = false; picker(acceptKey) } },
            onDismiss = { keyBrowser = false },
        )
    }
    keyPaste?.let { draft ->
        AppDialog(
            onDismiss = { keyPaste = null },
            title = stringResource(R.string.files_ssh_key_paste),
            footer = {
                TvActionButton(stringResource(R.string.files_save), {
                    val key = draft.trim()
                    if (com.ambient.tv.data.files.looksLikePrivateKey(key)) { sshKey = key; keyRejected = false; keyPaste = null } else keyRejected = true
                }, enabled = draft.isNotBlank())
                TvActionButton(stringResource(R.string.files_cancel), { keyPaste = null })
            },
        ) {
            TvTextField(draft, { keyPaste = it.take(com.ambient.tv.data.files.SSH_KEY_LIMIT) }, stringResource(R.string.files_ssh_key_paste),
                Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp), singleLine = false)
            if (keyRejected) Text(stringResource(R.string.files_ssh_key_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SshKeySection(
    stored: Boolean, key: String?, busy: Boolean, rejected: Boolean, canImport: Boolean,
    onBrowse: () -> Unit, onPaste: () -> Unit, onRemove: () -> Unit,
) {
    Text(stringResource(R.string.files_ssh_key_title), style = MaterialTheme.typography.titleMedium)
    val status = when {
        key == null && stored -> R.string.files_ssh_key_stored
        key.isNullOrEmpty() -> R.string.files_ssh_key_none
        else -> R.string.files_ssh_key_loaded
    }
    Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
    if (rejected) Text(stringResource(R.string.files_ssh_key_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canImport) TvActionButton(stringResource(R.string.files_ssh_key_load), onBrowse, enabled = !busy)
        TvActionButton(stringResource(R.string.files_ssh_key_paste), onPaste, enabled = !busy)
        if (key?.isNotEmpty() == true || (key == null && stored)) TvActionButton(stringResource(R.string.files_ssh_key_remove), onRemove, enabled = !busy)
    }
}

/** Shows the confirmed server identity, or offers to confirm the one the last test met. */
@Composable
private fun ServerKeySection(confirmed: String, challenge: ServerKeyChallenge?, busy: Boolean, onTrust: (String) -> Unit, onReset: () -> Unit) {
    val pending = challenge?.takeIf { it.fingerprint != confirmed }
    if (pending != null) {
        val text = when {
            pending.changed -> R.string.files_server_key_changed_detail
            pending.certificate -> R.string.files_server_cert_unconfirmed_detail
            else -> R.string.files_server_key_unconfirmed_detail
        }
        Text(stringResource(text, pending.fingerprint),
            color = if (pending.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall)
        TvActionButton(stringResource(R.string.files_server_key_trust), { onTrust(pending.fingerprint) }, enabled = !busy, fillWidth = true)
    } else if (confirmed.isNotEmpty()) {
        Text(stringResource(R.string.files_server_key_confirmed, confirmed), style = MaterialTheme.typography.bodySmall)
        TvActionButton(stringResource(R.string.files_server_key_reset), onReset, enabled = !busy)
    }
}

private fun cleanHost(value: String): String =
    value.trim().removePrefix("smb://").removePrefix("nfs://").removePrefix("sftp://").removePrefix("ssh://")
        .removePrefix("https://").removePrefix("http://").substringBefore('/')

/** "nas:2222" or "sftp://nas:2222" -> host and port; null for anything else (bare IPv6 keeps its colons). */
internal fun splitHostPort(value: String): Pair<String, Int>? {
    val bare = cleanHost(value)
    val match = Regex("""^([^:\[\]\s]+):(\d{1,5})$""").matchEntire(bare)
        ?: Regex("""^\[([0-9A-Fa-f:.]+)]:(\d{1,5})$""").matchEntire(bare) ?: return null
    val port = match.groupValues[2].toInt().takeIf { it in 1..65535 } ?: return null
    return match.groupValues[1] to port
}

internal data class WebDavAddress(val host: String, val port: Int, val secure: Boolean, val path: String)

/** "https://nas:5006/dav/media" -> host, port, scheme and base path; null for a plain host name. */
internal fun parseWebDavAddress(value: String): WebDavAddress? {
    val trimmed = value.trim()
    val scheme = trimmed.substringBefore("://", "").lowercase()
    if (scheme !in setOf("http", "https", "dav", "davs", "webdav", "webdavs")) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    val host = uri.host?.takeIf(String::isNotBlank) ?: return null
    val secure = scheme == "https" || scheme == "davs" || scheme == "webdavs"
    val port = uri.port.takeIf { it > 0 } ?: if (secure) 443 else 80
    val path = uri.path.orEmpty().trimEnd('/').let { if (it.isNotEmpty() && !it.startsWith('/')) "/$it" else it }
    return WebDavAddress(host, port, secure, path)
}

@Composable
private fun SourceField(label: Int, value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier, secret: Boolean = false) {
    // TvTextField keeps the IME closed until the field is clicked/activated, unlike a raw OutlinedTextField.
    TvTextField(value, { if (enabled) onChange(it) }, stringResource(label), modifier.fillMaxWidth(), requireExplicitEditing = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

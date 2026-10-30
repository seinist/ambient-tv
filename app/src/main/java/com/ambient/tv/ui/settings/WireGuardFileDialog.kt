package com.ambient.tv.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.files.FileEntry
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.DeviceStorageRoot
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.delay

internal enum class WireGuardBrowseBack { Up, Roots, Dismiss }

internal fun wireGuardBrowseBack(pathDepth: Int, browsing: Boolean): WireGuardBrowseBack = when {
    pathDepth > 1 -> WireGuardBrowseBack.Up
    browsing -> WireGuardBrowseBack.Roots
    else -> WireGuardBrowseBack.Dismiss
}

private sealed interface WireGuardBrowseTarget {
    val title: String
    data class Device(override val title: String) : WireGuardBrowseTarget
    data class Remote(val source: FileSource) : WireGuardBrowseTarget { override val title get() = source.name }
}

/** Where a text-file picker finds its files; the settings and Files screens supply their own. */
internal class TextFileImportSource(
    val sources: List<FileSource>,
    val deviceRoots: List<DeviceStorageRoot>,
    val hasDeviceAccess: () -> Boolean,
    val root: (FileSource) -> String,
    val listDevice: suspend (String) -> Result<List<FileEntry>>,
    val listRemote: suspend (FileSource, String) -> Result<List<FileEntry>>,
    val pickDevice: (String) -> Unit,
    val pickRemote: (FileSource, String) -> Unit,
)

/**
 * Picks a wg-quick `.conf` from device/USB storage, the folders/NAS shares configured under Files,
 * or the system document picker when one exists (Android TV usually ships without DocumentsUI).
 */
@Composable
internal fun WireGuardFileDialog(
    viewModel: SettingsViewModel,
    onSystemPicker: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val source = remember {
        TextFileImportSource(
            sources = viewModel.wireGuardFileSources(),
            deviceRoots = viewModel.wireGuardDeviceRoots(),
            hasDeviceAccess = viewModel::hasWireGuardDeviceAccess,
            root = viewModel::wireGuardFileRoot,
            listDevice = viewModel::listWireGuardDeviceFiles,
            listRemote = viewModel::listWireGuardFiles,
            pickDevice = viewModel::importWireGuardDeviceFile,
            pickRemote = viewModel::importWireGuardFile,
        )
    }
    TextFileImportDialog(source, stringResource(R.string.wg_file_title), stringResource(R.string.wg_file_empty), onSystemPicker, onDismiss)
}

/** Browses device/USB storage and Files sources for one small text file (WireGuard config, SSH key). */
@Composable
internal fun TextFileImportDialog(
    importSource: TextFileImportSource,
    title: String,
    emptyText: String,
    onSystemPicker: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sources = importSource.sources
    val deviceRoots = importSource.deviceRoots
    var target by remember { mutableStateOf<WireGuardBrowseTarget?>(null) }
    // Local document ids are not hierarchical paths, so "up" walks a stack instead of trimming a string.
    val pathStack = remember { mutableStateListOf<String>() }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var accessMissing by remember { mutableStateOf(false) }
    // Root waiting for the storage permission; opened once access comes back granted.
    var pendingRoot by remember { mutableStateOf<DeviceStorageRoot?>(null) }
    val first = remember { FocusRequester() }
    // Going up focuses the folder (or root/source) that was opened, following the click path back.
    val itemFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun itemRequester(key: String) = itemFocus.getOrPut(key) { FocusRequester() }
    var returnFocusKey by remember { mutableStateOf<String?>(null) }
    var rootKey by remember { mutableStateOf<String?>(null) }
    fun goUp() {
        when (wireGuardBrowseBack(pathStack.size, target != null)) {
            WireGuardBrowseBack.Up -> returnFocusKey = "entry:" + pathStack.removeAt(pathStack.lastIndex)
            WireGuardBrowseBack.Roots -> { returnFocusKey = rootKey; target = null; pathStack.clear() }
            WireGuardBrowseBack.Dismiss -> onDismiss()
        }
    }

    fun openDevice(root: DeviceStorageRoot, title: String) {
        rootKey = "root:" + root.path
        target = WireGuardBrowseTarget.Device(title)
        pathStack.clear(); pathStack += root.path
    }
    val internalTitle = stringResource(R.string.wg_file_device)
    fun rootTitle(root: DeviceStorageRoot) = root.label ?: internalTitle
    fun afterAccessRequest() {
        val root = pendingRoot ?: return
        pendingRoot = null
        if (importSource.hasDeviceAccess()) { accessMissing = false; openDevice(root, rootTitle(root)) } else accessMissing = true
    }
    val allFilesAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterAccessRequest() }
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterAccessRequest() }
    fun requestAccess(root: DeviceStorageRoot) {
        pendingRoot = root
        if (Build.VERSION.SDK_INT >= 30) {
            val intents = listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            val launched = intents.any { intent -> runCatching { allFilesAccess.launch(intent) }.isSuccess }
            if (!launched) { pendingRoot = null; accessMissing = true }
        } else {
            readPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val current = target
    val path = pathStack.lastOrNull()
    LaunchedEffect(current, path) {
        entries = emptyList(); failed = false
        if (current != null && path != null) {
            loading = true
            val result = when (current) {
                is WireGuardBrowseTarget.Device -> importSource.listDevice(path)
                is WireGuardBrowseTarget.Remote -> importSource.listRemote(current.source, path)
            }
            result.onSuccess { entries = it }.onFailure { failed = true }
            loading = false
        }
        delay(80)
        val back = returnFocusKey?.let(itemFocus::get)
        returnFocusKey = null
        if (back == null || !runCatching { back.requestFocus() }.getOrDefault(false)) runCatching { first.requestFocus() }
    }

    AppDialog(
        onDismiss = onDismiss,
        title = current?.title ?: title,
        fillHeight = true,
        captureInitialFocus = false,
        onDismissRequest = ::goUp,
        footer = {
            TvActionButton(stringResource(R.string.files_cancel), onDismiss)
        },
    ) {
        if (current == null) {
            var firstAssigned = false
            fun focusFirst(): Modifier = if (firstAssigned) Modifier else { firstAssigned = true; Modifier.focusRequester(first) }
            deviceRoots.forEach { root ->
                TvActionButton(
                    rootTitle(root),
                    { if (importSource.hasDeviceAccess()) openDevice(root, rootTitle(root)) else requestAccess(root) },
                    focusFirst().focusRequester(itemRequester("root:" + root.path)),
                    icon = if (root.label == null) Icons.Default.PhoneAndroid else Icons.Default.Usb,
                    fillWidth = true,
                )
            }
            if (accessMissing) {
                Text(stringResource(R.string.wg_file_access_needed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            sources.forEach { item ->
                TvActionButton(
                    item.name + " · " + if (item.kind == "local") stringResource(R.string.files_local) else com.ambient.tv.data.files.FileKinds.label(item.kind),
                    {
                        rootKey = "source:" + item.name
                        target = WireGuardBrowseTarget.Remote(item)
                        pathStack.clear(); pathStack += importSource.root(item)
                    },
                    focusFirst().focusRequester(itemRequester("source:" + item.name)),
                    icon = if (item.kind == "local") Icons.Default.Folder else Icons.Default.Storage,
                    fillWidth = true,
                )
            }
            if (onSystemPicker != null) {
                TvActionButton(stringResource(R.string.wg_file_system_picker), onSystemPicker, focusFirst(), icon = Icons.Default.Description)
            }
        } else {
            TvActionButton(stringResource(R.string.files_back), ::goUp, Modifier.focusRequester(first))
            when {
                loading -> Text(stringResource(R.string.files_connecting), style = MaterialTheme.typography.bodySmall)
                failed -> Text(stringResource(R.string.files_connection_error), color = MaterialTheme.colorScheme.error)
                entries.isEmpty() -> Text(emptyText, style = MaterialTheme.typography.bodySmall)
            }
            entries.forEach { entry ->
                TvActionButton(
                    entry.name,
                    {
                        when {
                            entry.directory -> pathStack += entry.path
                            current is WireGuardBrowseTarget.Device -> { importSource.pickDevice(entry.path); onDismiss() }
                            current is WireGuardBrowseTarget.Remote -> { importSource.pickRemote(current.source, entry.path); onDismiss() }
                        }
                    },
                    Modifier.focusRequester(itemRequester("entry:" + entry.path)),
                    icon = if (entry.directory) Icons.Default.Folder else Icons.Default.Description,
                    fillWidth = true,
                )
            }
        }
    }
}

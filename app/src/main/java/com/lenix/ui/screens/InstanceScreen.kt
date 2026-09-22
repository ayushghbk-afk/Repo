package com.lenix.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lenix.installer.RootfsCatalog
import com.lenix.ui.HomeUiState
import com.lenix.vm.VmInstance
import com.lenix.vm.VmState
import com.lenix.vm.isBusy

/**
 * Instance manager: the persisted list of Linux environments (Phase 2).
 *
 * Every row is a real instance loaded from `filesDir/instances/<id>/config.json`;
 * create / rename / delete act on disk, and tapping a row selects the instance the
 * Home screen controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceScreen(
    state: HomeUiState,
    diskUsage: suspend (String) -> Long,
    onSelect: (String) -> Unit,
    onCreate: (name: String, distroId: String) -> Unit,
    onRename: (id: String, newName: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VmInstance?>(null) }
    var deleteTarget by remember { mutableStateOf<VmInstance?>(null) }

    val usageByInstance by produceState(initialValue = emptyMap<String, Long>(), state.instances) {
        value = state.instances.values.associate { it.id to diskUsage(it.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instances") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create instance")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.message?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (state.instances.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No instances yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Tap + to create one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        state.instances.values.sortedByDescending { it.updatedAt }.toList(),
                        key = { it.id },
                    ) { instance ->
                        InstanceRow(
                            instance = instance,
                            selected = instance.id == state.selectedInstance.id,
                            usageBytes = usageByInstance[instance.id],
                            onClick = { onSelect(instance.id) },
                            onRename = { renameTarget = instance },
                            onDelete = { deleteTarget = instance },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateInstanceDialog(
            onCreate = { name, distroId ->
                onCreate(name, distroId)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { target ->
        RenameInstanceDialog(
            instance = target,
            onConfirm = { newName ->
                onRename(target.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete instance?") },
            text = {
                Text(
                    "'${target.name}' and all of its files will be removed from this " +
                        "device. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InstanceRow(
    instance: VmInstance,
    selected: Boolean,
    usageBytes: Long?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Storage,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${instance.distro} ${instance.version} (${instance.codename}) • " +
                        "${instance.architecture}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${statusLabel(instance.state)} • ${instance.id} • " +
                        formatBytes(usageBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(instance.state),
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = onDelete, enabled = !instance.state.isBusy) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun CreateInstanceDialog(
    onCreate: (name: String, distroId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var distroId by remember { mutableStateOf(RootfsCatalog.options.first { it.enabled }.id) }
    val selectedOption = RootfsCatalog.options.firstOrNull { it.id == distroId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New instance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                RootfsCatalog.options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option.enabled) {
                                if (option.enabled) distroId = option.id
                            },
                    ) {
                        RadioButton(
                            selected = option.id == distroId,
                            onClick = if (option.enabled) {
                                { distroId = option.id }
                            } else {
                                null
                            },
                            enabled = option.enabled,
                        )
                        Column {
                            Text(option.displayName + " " + option.version)
                            Text(
                                text = if (option.enabled) {
                                    option.codename
                                } else {
                                    "Coming soon"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, distroId) },
                enabled = name.isNotBlank() && selectedOption?.enabled == true,
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameInstanceDialog(
    instance: VmInstance,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(instance.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename instance") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun statusLabel(state: VmState): String = when (state) {
    VmState.NOT_INSTALLED -> "Not installed"
    VmState.DOWNLOADING -> "Downloading"
    VmState.VERIFYING -> "Verifying"
    VmState.EXTRACTING -> "Extracting"
    VmState.INSTALLING -> "Installing"
    VmState.READY -> "Ready"
    VmState.STARTING -> "Starting"
    VmState.RUNNING -> "Running"
    VmState.STOPPING -> "Stopping"
    VmState.ERROR -> "Error"
}

@Composable
private fun statusColor(state: VmState) = when (state) {
    VmState.ERROR -> MaterialTheme.colorScheme.error
    VmState.RUNNING, VmState.READY -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
}
